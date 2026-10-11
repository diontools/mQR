# mQR

最小限の権限・コード・サイズを目指した Android 用 QR コードリーダー/ジェネレーター。

- 権限: `CAMERA` のみ（`INTERNET` なし）
- 依存: ZXing core のみ（AndroidX / Kotlin なし）
- minSdk 26

## 機能

- カメラでスキャン（画面中央の枠内のみ認識、読み取り後はカメラを停止。結果はコピー / 共有 / http(s) のみ開く）
- 画像ファイルからスキャン（ストレージ権限不要）
- テキストから生成し PNG で保存（誤り訂正レベル L/M/Q/H を選択、既定は L。SAF 経由、権限不要）
- 他アプリの「共有」や文字選択メニューからテキストを受け取って生成

## 開発者向け

- リリース手順と署名鍵: [docs/release.md](docs/release.md)
- Google Play へのアップロード設定: [docs/google-play.md](docs/google-play.md)

## 更新履歴

[CHANGELOG.md](CHANGELOG.md)

## プライバシー

個人情報を収集・送信しません。詳細は [PRIVACY.md](PRIVACY.md)（Google Play のプライバシーポリシー URL にも使用）。

## ライセンス

[MIT](LICENSE)。同梱しているサードパーティ ソフトウェアのライセンスは [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
