# Google Play 掲載情報の画像

| ファイル | 用途 | サイズ |
|---|---|---|
| `icon-512.svg` → `icon-512.png` | アプリのアイコン | 512×512 PNG |
| `feature-graphic-1024x500.svg` → `feature-graphic-1024x500.png` | フィーチャー グラフィック | 1024×500 PNG |

SVG が元データ。編集したら PNG に書き出して Play Console にアップロードする（例: `rsvg-convert icon-512.svg -o icon-512.png`、Inkscape、ブラウザで開いてスクリーンショットなど）。

- 図形はランチャーアイコン（`app/src/main/res/drawable/ic_fg.xml`）と同じ。アイコンの角丸は Play 側で適用される
- フィーチャー グラフィックの文字は英語のみ（DejaVu Sans）
- スクリーンショットは Actions → screenshots → Run workflow で撮影し、Artifact `screenshots` の `<言語>/*.png` をアップロードする（1080×1920、en-US / ja-JP）

## スクリーンショットの自動撮影

`scripts/screenshots.sh` がエミュレータ（API 33 以上）に debug APK とテスト APK を入れ、`app/src/androidTest/.../Screenshots.java`（`am instrument` で実行）が各画面を撮る。

- カメラ画面: カメラ権限を与えずに起動し、プレビューに QR コードのカードを描いた合成画像を表示する（カメラ映像に依存しない）
- QR 生成画面: 共有インテントで URL を渡し、誤り訂正レベル L で表示する
- 画面サイズは `wm size` で 1080×1920 に、ステータスバーは時計や通知が写らないよう背景色で塗りつぶす

ローカルで撮る場合は、エミュレータを起動してから:

```sh
./gradlew assembleDebug assembleDebugAndroidTest
scripts/screenshots.sh screenshots
```
