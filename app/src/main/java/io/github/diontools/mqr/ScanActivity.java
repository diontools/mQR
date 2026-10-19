package io.github.diontools.mqr;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

@SuppressWarnings("deprecation") // android.hardware.Camera: 最小コードのため旧APIを使う
public class ScanActivity extends Activity implements TextureView.SurfaceTextureListener, Camera.PreviewCallback {
    private static final int REQ_CAMERA = 1;
    private static final int REQ_IMAGE = 2;
    private static final float SCAN_RATIO = 0.7f; // 画面短辺に対する読み取り枠の大きさ
    private static final int DIM = 0x99000000; // 読み取り枠の外側の暗さ

    private final QRCodeReader reader = new QRCodeReader();
    private TextureView preview;
    private HandlerThread thread;
    private Handler handler;
    private Camera camera; // handler スレッドでのみ触る
    private final Camera.CameraInfo info = new Camera.CameraInfo(); // 同上
    private int width, height;
    private int fitW, fitH; // 回転後のプレビューの大きさ。UI スレッドでのみ触る
    private volatile int crop; // 読み取り枠のフレーム上での一辺 (px)。0 は未確定
    private volatile boolean paused;
    private boolean resumed; // 停止中にカメラを開かないため。UI スレッドでのみ触る
    private boolean asked; // 拒否後に onResume で再要求し続けないため
    private LinearLayout bar;
    private float guideSize; // 案内文の文字の大きさ (px)
    private AlertDialog dialog;
    private String result; // 表示中の結果。再生成時に出し直すため保存する

    // 横向きのまま 180° 回したときは再生成されないので、画面の回転を監視する
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayChanged(int id) {
            handler.post(() -> {
                if (camera != null) camera.setDisplayOrientation(displayOrientation());
            });
        }

        @Override
        public void onDisplayAdded(int id) {
        }

        @Override
        public void onDisplayRemoved(int id) {
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // 一時停止→再開が素早くても release() の後に open() するよう、スレッドは 1 本にする
        thread = new HandlerThread("camera");
        thread.start();
        handler = new Handler(thread.getLooper());
        guideSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics());
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(this);

        // 読み取り枠の外側と同じ暗さの帯に、左右 2 等分の文字ボタンを置く
        bar = new LinearLayout(this);
        bar.setBackgroundColor(DIM);
        bar.addView(button(R.string.image, () -> {
            try {
                startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT)
                        .setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_IMAGE);
            } catch (ActivityNotFoundException e) { // ファイル選択画面が無い端末
                toast(R.string.failed);
            }
        }), new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(button(R.string.generate, () -> startActivity(new Intent(this, GenerateActivity.class))),
                new LinearLayout.LayoutParams(0, -2, 1));

        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        root.addView(preview);
        root.addView(new Overlay());
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);

        // 大画面では縦固定が効かず回転で再生成されるので、結果と権限の要求済みを引き継ぐ
        if (state != null) {
            asked = state.getBoolean("asked");
            String text = state.getString("result");
            if (text != null) {
                paused = true;
                showDialog(text);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("asked", asked);
        state.putString("result", result);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (dialog != null) {
            dialog.setOnDismissListener(null); // 再開はしない
            dialog.dismiss(); // 閉じないと WindowLeaked になる
        }
        thread.quitSafely();
    }

    private Button button(int text, Runnable action) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        getSystemService(DisplayManager.class).registerDisplayListener(displayListener, null);
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else if (!asked) {
            asked = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        getSystemService(DisplayManager.class).unregisterDisplayListener(displayListener);
        handler.post(this::releaseCamera);
    }

    // マルチウィンドウでカメラを他のアプリに奪われた後、こちらが最前面に戻ったら開き直す
    @Override
    public void onTopResumedActivityChanged(boolean top) {
        if (top && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
    }

    // handler スレッドで呼ぶ
    private void releaseCamera() {
        if (camera != null) {
            camera.setPreviewCallbackWithBuffer(null);
            camera.release();
            camera = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else toast(R.string.no_camera);
    }

    private void startCamera() {
        if (!resumed) return; // 再開時に onResume から呼ばれる
        // カメラのコールバックは open() したスレッドの Looper で届く
        handler.post(() -> {
            SurfaceTexture surface = preview.getSurfaceTexture();
            if (camera != null || surface == null) return;
            try {
                int id = 0;
                for (; id < Camera.getNumberOfCameras(); id++) {
                    Camera.getCameraInfo(id, info);
                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) break;
                }
                if (id == Camera.getNumberOfCameras()) id = 0;
                Camera.getCameraInfo(id, info);
                camera = Camera.open(id);
                // 他のアプリに奪われたときなどは解放し、onTopResumedActivityChanged で開き直す
                camera.setErrorCallback((error, cam) -> releaseCamera());

                Camera.Parameters p = camera.getParameters();
                Camera.Size best = null;
                for (Camera.Size s : p.getSupportedPreviewSizes()) {
                    if (best == null || Math.abs(s.width * s.height - 1280 * 720) < Math.abs(best.width * best.height - 1280 * 720)) best = s;
                }
                p.setPreviewSize(best.width, best.height);
                if (p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                }
                camera.setParameters(p);
                width = best.width;
                height = best.height;
                // 大画面では縦固定が無視される (Android 16 以降) ので画面の回転も考慮する
                int orientation = displayOrientation();
                boolean rotated = orientation % 180 != 0;
                camera.setDisplayOrientation(orientation);
                camera.setPreviewTexture(surface);
                camera.setPreviewCallbackWithBuffer(this);
                camera.addCallbackBuffer(new byte[width * height * 3 / 2]);
                if (!paused) camera.startPreview(); // 結果表示中なら閉じるまで止めておく
                int w = rotated ? height : width, h = rotated ? width : height;
                runOnUiThread(() -> {
                    fitW = w;
                    fitH = h;
                    fitPreview();
                });
            } catch (Exception e) {
                if (camera != null) camera.release();
                camera = null;
                runOnUiThread(() -> toast(R.string.no_camera));
            }
        });
    }

    // アスペクト比を保ったまま画面いっぱいに表示する (center crop)
    private void fitPreview() {
        int w = fitW, h = fitH;
        if (w == 0) return;
        float vw = preview.getWidth(), vh = preview.getHeight();
        float scale = Math.max(vw / w, vh / h);
        Matrix m = new Matrix();
        m.setScale(w * scale / vw, h * scale / vh, vw / 2, vh / 2);
        preview.setTransform(m);
        // 枠は中央の正方形なので、回転に関係なくフレーム中央の正方形に対応する
        crop = Math.min((int) (frameSize((int) vw, (int) vh) / scale), Math.min(w, h));
    }

    // 読み取り枠の一辺。高さの小さい画面では、枠の下の案内文が下の帯にかからないよう小さくする
    private int frameSize(int w, int h) {
        int s = (int) (Math.min(w, h) * SCAN_RATIO);
        int room = h - 2 * (bar.getHeight() + (int) (guideSize * 2.5f)); // 案内文は枠の下端から 2 文字分下に出す
        return Math.min(s, Math.max(room, Math.min(w, h) / 3)); // これより小さいと読み取りにくいので重なりを許す
    }

    // カメラの取り付け角度と画面の回転から、プレビューの表示角度を求める
    private int displayOrientation() {
        int degrees = getWindowManager().getDefaultDisplay().getRotation() * 90;
        return info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                ? (360 - (info.orientation + degrees) % 360) % 360 // 前面は鏡像になる
                : (info.orientation - degrees + 360) % 360;
    }

    // 読み取り枠の外側を暗く表示する
    private class Overlay extends View {
        private final Paint dim = new Paint();
        private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String guideText = getString(R.string.guide);

        Overlay() {
            super(ScanActivity.this);
            dim.setColor(DIM);
            frame.setColor(0xffffffff);
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
            guide.setColor(0xffffffff);
            guide.setTextAlign(Paint.Align.CENTER);
            guide.setTextSize(guideSize);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            int s = frameSize(w, h);
            int l = (w - s) / 2, t = (h - s) / 2, r = l + s, b = t + s;
            c.drawRect(0, 0, w, t, dim);
            c.drawRect(0, b, w, h, dim);
            c.drawRect(0, t, l, b, dim);
            c.drawRect(r, t, w, b, dim);
            c.drawRect(l, t, r, b, frame);
            c.drawText(guideText, w / 2f, b + guide.getTextSize() * 2, guide); // 枠の下に案内を出す
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        int c = crop;
        if (!paused && c > 0) {
            // QR の検出は回転に依存しないので中央部分をそのまま渡す
            String text = decode(new PlanarYUVLuminanceSource(data, width, height, (width - c) / 2, (height - c) / 2, c, c, false), false);
            if (text != null) {
                paused = true;
                cam.stopPreview();
                runOnUiThread(() -> showResult(text));
            }
        }
        cam.addCallbackBuffer(data);
    }

    private String decode(LuminanceSource source, boolean tryHarder) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        if (tryHarder) hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        try {
            return reader.decode(new BinaryBitmap(new HybridBinarizer(source)), hints).getText();
        } catch (Exception e) {
            return null;
        } finally {
            reader.reset();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        paused = true;
        Uri uri = data.getData();
        new Thread(() -> {
            String text = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(in, null, o);
                }
                o.inJustDecodeBounds = false;
                o.inSampleSize = 1;
                while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > 2048) o.inSampleSize *= 2;
                Bitmap bmp;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    bmp = BitmapFactory.decodeStream(in, null, o);
                }
                if (bmp != null) {
                    int w = bmp.getWidth(), h = bmp.getHeight();
                    int[] px = new int[w * h];
                    bmp.getPixels(px, 0, w, 0, 0, w, h);
                    bmp.recycle();
                    text = decode(new RGBLuminanceSource(w, h, px), true);
                }
            } catch (Exception ignored) {
            }
            String result = text;
            runOnUiThread(() -> {
                if (result != null) showResult(result);
                else {
                    toast(R.string.not_found);
                    resumeScan();
                }
            });
        }).start();
    }

    private void showResult(String text) {
        if (isFinishing() || isDestroyed()) return; // 読み取り中に閉じられた
        preview.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        showDialog(text);
    }

    private void showDialog(String text) {
        result = text;
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setMessage(text)
                .setOnDismissListener(d -> {
                    dialog = null;
                    result = null;
                    resumeScan();
                })
                .setPositiveButton(R.string.copy, (d, w) -> {
                    getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(null, text));
                })
                .setNeutralButton(R.string.share, (d, w) -> startActivity(Intent.createChooser(
                        new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)));
        // 任意の intent: スキーム等を起動させないため http/https のみ開く
        // インテントフィルタの照合は大文字小文字を区別するので、HTTPS:// などはスキームを小文字にする
        Uri uri = Uri.parse(text.trim()).normalizeScheme();
        String scheme = uri.getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            b.setNegativeButton(R.string.open, (d, w) -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
                } catch (ActivityNotFoundException e) {
                    toast(R.string.failed);
                }
            });
        }
        dialog = b.show();
    }

    private void resumeScan() {
        paused = false;
        handler.post(() -> {
            if (camera != null) camera.startPreview(); // プレビュー中なら何もしない
        });
    }

    private void toast(int text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int w, int h) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int w, int h) {
        fitPreview();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }
}
