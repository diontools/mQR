# Google Play 掲載情報の画像

| ファイル | 用途 | サイズ |
|---|---|---|
| `icon-512.svg` → `icon-512.png` | アプリのアイコン | 512×512 PNG |
| `feature-graphic-1024x500.svg` → `feature-graphic-1024x500.png` | フィーチャー グラフィック | 1024×500 PNG |

SVG が元データ。編集したら PNG に書き出して Play Console にアップロードする（例: `rsvg-convert icon-512.svg -o icon-512.png`、Inkscape、ブラウザで開いてスクリーンショットなど）。

- 図形はランチャーアイコン（`app/src/main/res/drawable/ic_fg.xml`）と同じ。アイコンの角丸は Play 側で適用される
- フィーチャー グラフィックの文字は英語のみ（DejaVu Sans）
- スクリーンショットは実機で撮影する（9:16、短辺 320px 以上・長辺 3840px 以下、2〜8 枚）
