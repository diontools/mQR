package io.github.diontools.mqr;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
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
    private ErrorCorrectionLevel level = ErrorCorrectionLevel.L;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        image = new ImageView(this);
        image.setBackgroundColor(Color.WHITE);

        input = new EditText(this);
        input.setId(100); // ID がないと再生成時（回転など）に入力が復元されない
        input.setHint(R.string.hint);
        input.setMaxLines(4);

        // 誤り訂正レベル。前回の選択を覚えておく
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        try {
            level = ErrorCorrectionLevel.valueOf(prefs.getString("level", level.name()));
        } catch (IllegalArgumentException ignored) {
        }
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        RadioGroup levels = new RadioGroup(this);
        levels.setOrientation(LinearLayout.HORIZONTAL);
        levels.setGravity(Gravity.CENTER_VERTICAL);
        levels.setPadding(pad, 0, pad, 0);
        TextView label = new TextView(this);
        label.setText(R.string.level);
        label.setPadding(0, 0, pad, 0);
        levels.addView(label);
        for (ErrorCorrectionLevel l : ErrorCorrectionLevel.values()) {
            RadioButton b = new RadioButton(this);
            b.setId(l.ordinal() + 1); // 固定 ID にして再生成時に状態を戻せるようにする
            b.setText(l.name());
            RadioGroup.LayoutParams lp = new RadioGroup.LayoutParams(-2, -2);
            lp.setMarginEnd(pad * 2);
            levels.addView(b, lp);
        }
        levels.check(level.ordinal() + 1);
        levels.setOnCheckedChangeListener((g, id) -> {
            level = ErrorCorrectionLevel.values()[id - 1];
            prefs.edit().putString("level", level.name()).apply();
            update(input.getText().toString());
        });

        Button save = new Button(this);
        save.setText(R.string.save);
        save.setOnClickListener(v -> {
            if (qr == null) return;
            try {
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("image/png").putExtra(Intent.EXTRA_TITLE, "qr.png"), REQ_SAVE);
            } catch (ActivityNotFoundException e) { // ファイル選択画面が無い端末
                Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show();
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(input, -1, -2);
        root.addView(levels, -1, -2);
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
                ByteMatrix m = Encoder.encode(text, level, hints).getMatrix();
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
        if (requestCode != REQ_SAVE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        boolean ok = false; // qr が無いと空のファイルだけ作られるので失敗を知らせる
        if (qr != null) {
            int scale = Math.max(1, 1024 / qr.getWidth());
            Bitmap big = Bitmap.createScaledBitmap(qr, qr.getWidth() * scale, qr.getHeight() * scale, false);
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                ok = big.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(this, ok ? R.string.done : R.string.failed, Toast.LENGTH_SHORT).show();
    }
}
