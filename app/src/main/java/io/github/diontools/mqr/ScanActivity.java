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
    private int width, height;
    private volatile int crop; // 読み取り枠のフレーム上での一辺 (px)。0 は未確定
    private volatile boolean paused;
    private boolean asked; // 拒否後に onResume で再要求し続けないため

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(this);

        // 読み取り枠の外側と同じ暗さの帯に、左右 2 等分の文字ボタンを置く
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(DIM);
        bar.addView(button(R.string.image, () -> startActivityForResult(
                new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_IMAGE)),
                new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(button(R.string.generate, () -> startActivity(new Intent(this, GenerateActivity.class))),
                new LinearLayout.LayoutParams(0, -2, 1));

        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        root.addView(preview);
        root.addView(new Overlay());
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
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
        thread = new HandlerThread("camera");
        thread.start();
        handler = new Handler(thread.getLooper());
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
        handler.post(() -> {
            if (camera != null) {
                camera.setPreviewCallbackWithBuffer(null);
                camera.release();
                camera = null;
            }
        });
        thread.quitSafely();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else toast(R.string.no_camera);
    }

    private void startCamera() {
        // カメラのコールバックは open() したスレッドの Looper で届く
        handler.post(() -> {
            SurfaceTexture surface = preview.getSurfaceTexture();
            if (camera != null || surface == null) return;
            try {
                Camera.CameraInfo info = new Camera.CameraInfo();
                int id = 0;
                for (; id < Camera.getNumberOfCameras(); id++) {
                    Camera.getCameraInfo(id, info);
                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) break;
                }
                if (id == Camera.getNumberOfCameras()) id = 0;
                Camera.getCameraInfo(id, info);
                camera = Camera.open(id);

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
                // 縦画面固定なので画面回転は 0 として扱える
                boolean rotated = info.orientation % 180 != 0;
                camera.setDisplayOrientation(info.orientation);
                camera.setPreviewTexture(surface);
                camera.setPreviewCallbackWithBuffer(this);
                camera.addCallbackBuffer(new byte[width * height * 3 / 2]);
                if (!paused) camera.startPreview(); // 結果表示中なら閉じるまで止めておく
                runOnUiThread(() -> fitPreview(rotated ? height : width, rotated ? width : height));
            } catch (Exception e) {
                if (camera != null) camera.release();
                camera = null;
                runOnUiThread(() -> toast(R.string.no_camera));
            }
        });
    }

    // アスペクト比を保ったまま画面いっぱいに表示する (center crop)
    private void fitPreview(int w, int h) {
        float vw = preview.getWidth(), vh = preview.getHeight();
        float scale = Math.max(vw / w, vh / h);
        Matrix m = new Matrix();
        m.setScale(w * scale / vw, h * scale / vh, vw / 2, vh / 2);
        preview.setTransform(m);
        // 枠は中央の正方形なので、回転に関係なくフレーム中央の正方形に対応する
        crop = Math.min((int) (Math.min(vw, vh) * SCAN_RATIO / scale), Math.min(w, h));
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
            guide.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics()));
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            int s = (int) (Math.min(w, h) * SCAN_RATIO);
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
        preview.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setMessage(text)
                .setOnDismissListener(d -> resumeScan())
                .setPositiveButton(R.string.copy, (d, w) -> {
                    getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(null, text));
                })
                .setNeutralButton(R.string.share, (d, w) -> startActivity(Intent.createChooser(
                        new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)));
        // 任意の intent: スキーム等を起動させないため http/https のみ開く
        Uri uri = Uri.parse(text.trim());
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            b.setNegativeButton(R.string.open, (d, w) -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
                } catch (ActivityNotFoundException e) {
                    toast(R.string.failed);
                }
            });
        }
        b.show();
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
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }
}
