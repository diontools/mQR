package io.github.diontools.mqr;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.RadioGroup;

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;

/**
 * ストア掲載用スクリーンショットを撮る（scripts/screenshots.sh から am instrument で実行する）。
 * カメラは使わず、プレビューに QR コードを写した合成画像を描く。
 * 保存先はアプリの files/screenshots/（run-as で取り出す）。
 */
public class Screenshots extends Instrumentation {
    private static final String URL = "https://github.com/diontools/mQR";
    private static final ErrorCorrectionLevel LEVEL = ErrorCorrectionLevel.L;

    @Override
    public void onCreate(Bundle args) {
        super.onCreate(args);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            File dir = new File(getTargetContext().getFilesDir(), "screenshots");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("mkdirs: " + dir);
            scan(new File(dir, "scan.png"));
            generate(new File(dir, "generate.png"));
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            StringWriter w = new StringWriter();
            e.printStackTrace(new PrintWriter(w));
            result.putString(REPORT_KEY_STREAMRESULT, w.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        super.callActivityOnCreate(activity, icicle);
        if (activity instanceof ScanActivity) {
            // 権限要求ダイアログを出さず、カメラも開かせない（権限は付与せずにインストールしておく）
            try {
                Field asked = ScanActivity.class.getDeclaredField("asked");
                asked.setAccessible(true);
                asked.setBoolean(activity, true);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void scan(File out) throws Exception {
        Activity a = startActivitySync(new Intent(getTargetContext(), ScanActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        TextureView preview = find(a.getWindow().getDecorView(), TextureView.class);
        long deadline = SystemClock.uptimeMillis() + 10_000;
        while (!preview.isAvailable()) {
            if (SystemClock.uptimeMillis() > deadline) throw new IllegalStateException("TextureView not available");
            SystemClock.sleep(100);
        }
        Bitmap scene = scene(preview.getWidth(), preview.getHeight());
        Canvas c = preview.lockCanvas();
        if (c == null) throw new IllegalStateException("lockCanvas failed");
        c.drawBitmap(scene, 0, 0, null);
        preview.unlockCanvasAndPost(c);
        capture(a, out);
        a.finish();
    }

    private void generate(File out) throws Exception {
        Activity a = startActivitySync(new Intent(getTargetContext(), GenerateActivity.class)
                .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, URL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        runOnMainSync(() -> {
            // 誤り訂正レベルを選ぶ（ラジオボタンは L, M, Q, H の順）
            find(a.getWindow().getDecorView(), RadioGroup.class).check(LEVEL.ordinal() + 1);
            EditText input = find(a.getWindow().getDecorView(), EditText.class);
            input.setCursorVisible(false); // 点滅で結果が変わらないように
            a.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
        });
        capture(a, out);
        a.finish();
    }

    private void capture(Activity a, File out) throws Exception {
        waitForIdleSync();
        SystemClock.sleep(1500); // 描画やキーボードのアニメーションを待つ
        Bitmap shot = getUiAutomation().takeScreenshot();
        if (shot == null) throw new IllegalStateException("takeScreenshot failed");
        shot = shot.copy(Bitmap.Config.ARGB_8888, true);
        // 時計や通知アイコンで毎回変わらないよう、ステータスバーを背景色で塗りつぶす
        // （SystemUI のデモモードはエミュレータで確実に効かないため使わない）
        int top = a.getWindow().getDecorView().getRootWindowInsets().getStableInsetTop();
        Paint bar = new Paint();
        bar.setColor(shot.getPixel(0, 0));
        new Canvas(shot).drawRect(0, 0, shot.getWidth(), top, bar);
        try (OutputStream os = new FileOutputStream(out)) {
            if (!shot.compress(Bitmap.CompressFormat.PNG, 100, os)) throw new IllegalStateException("compress failed");
        }
    }

    private static <T extends View> T find(View v, Class<T> type) {
        if (type.isInstance(v)) return type.cast(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                T found = find(g.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    // 机に置いた紙の QR コードを上から写したような画像
    private static Bitmap scene(int w, int h) throws Exception {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // 机: 中央が明るく周辺が暗い、カメラらしい照明ムラ
        p.setShader(new RadialGradient(w * 0.45f, h * 0.4f, Math.max(w, h) * 0.8f,
                0xffb4b2ad, 0xff5f5d59, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // 紙: 読み取り枠（短辺の 70%）に収まる大きさで少し傾ける
        float size = Math.min(w, h) * 0.5f;
        c.save();
        c.rotate(-4, w / 2f, h / 2f);
        RectF paper = new RectF(w / 2f - size / 2, h / 2f - size / 2, w / 2f + size / 2, h / 2f + size / 2);
        p.setShader(new LinearGradient(paper.left, paper.top, paper.right, paper.bottom,
                0xfff2f1ed, 0xffdcdad5, Shader.TileMode.CLAMP));
        c.drawRect(paper, p);
        p.setShader(null);

        ByteMatrix m = Encoder.encode(URL, LEVEL, null).getMatrix();
        int n = m.getWidth();
        float module = size * 0.8f / n, left = paper.centerX() - module * n / 2, top = paper.centerY() - module * n / 2;
        // 1 つの Path にまとめて塗り、傾けてもモジュールの間に隙間が出ないようにする
        Path qr = new Path();
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (m.get(x, y) == 1) {
                    qr.addRect(left + x * module, top + y * module, left + (x + 1) * module, top + (y + 1) * module, Path.Direction.CW);
                }
            }
        }
        p.setColor(0xff1c1c1c); // 印刷の黒
        c.drawPath(qr, p);
        c.restore();
        return bmp;
    }
}
