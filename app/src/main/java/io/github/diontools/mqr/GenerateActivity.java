package io.github.diontools.mqr;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

public class GenerateActivity extends Activity {
    private static final int REQ_SAVE = 1;
    private static final int QUIET = 4;

    private ImageView image;
    private EditText input;
    private Bitmap qr; // 1 モジュール = 1 ピクセル

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        image = new ImageView(this);
        image.setBackgroundColor(Color.WHITE);

        input = new EditText(this);
        input.setHint(R.string.hint);
        input.setMaxLines(4);

        Button save = new Button(this);
        save.setText(R.string.save);
        save.setOnClickListener(v -> {
            if (qr != null) startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("image/png").putExtra(Intent.EXTRA_TITLE, "qr.png"), REQ_SAVE);
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(input, -1, -2);
        root.addView(save, -1, -2);
        setContentView(root);

        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable s) {
                update(s.toString());
            }
        });

        Intent intent = getIntent();
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text == null) text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if (text != null) input.setText(text);
    }

    private void update(String text) {
        qr = null;
        input.setError(null);
        if (!text.isEmpty()) {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            // ASCII 以外を含むときだけ UTF-8 (ECI 付き) にする
            if (!StandardCharsets.US_ASCII.newEncoder().canEncode(text)) hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            try {
                ByteMatrix m = Encoder.encode(text, ErrorCorrectionLevel.M, hints).getMatrix();
                int n = m.getWidth(), size = n + QUIET * 2;
                int[] px = new int[size * size];
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        int mx = x - QUIET, my = y - QUIET;
                        boolean dark = mx >= 0 && my >= 0 && mx < n && my < n && m.get(mx, my) == 1;
                        px[y * size + x] = dark ? Color.BLACK : Color.WHITE;
                    }
                }
                qr = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
            } catch (Exception e) {
                input.setError(getString(R.string.too_long));
            }
        }
        BitmapDrawable d = qr == null ? null : new BitmapDrawable(getResources(), qr);
        if (d != null) d.setFilterBitmap(false); // 拡大時にぼかさない
        image.setImageDrawable(d);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_SAVE || resultCode != RESULT_OK || data == null || data.getData() == null || qr == null) return;
        int scale = Math.max(1, 1024 / qr.getWidth());
        Bitmap big = Bitmap.createScaledBitmap(qr, qr.getWidth() * scale, qr.getHeight() * scale, false);
        boolean ok;
        try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            ok = big.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            ok = false;
        }
        Toast.makeText(this, ok ? R.string.done : R.string.failed, Toast.LENGTH_SHORT).show();
    }
}
