# mQR

最小限の権限・コード・サイズを目指した Android 用 QR コードリーダー/ジェネレーター。

- 権限: `CAMERA` のみ（`INTERNET` なし）
- 依存: ZXing core のみ（AndroidX / Kotlin なし）
- minSdk 26

## 機能

- カメラでスキャン（画面中央の枠内のみ認識、読み取り後はカメラを停止。結果はコピー / 共有 / http(s) のみ開く）
- 画像ファイルからスキャン（ストレージ権限不要）
- テキストから生成し PNG で保存（SAF 経由、権限不要）
- 他アプリの「共有」や文字選択メニューからテキストを受け取って生成

## リリース

Actions → build → Run workflow で `main` を選び（main 以外ではエラー）、バージョン（例: `1.0.0`）を入力すると、GitHub Actions がビルドし、タグ `v1.0.0` を作成して APK をリリースに添付します。リリース本文は `whatsnew/*.txt`（リリース前の PR で更新する）。タグを手動で push してもリリースは作成されません。

```sh
gh workflow run build.yml -R diontools/mQR --ref main -f version=1.0.0
```

### 署名鍵

リポジトリの Actions の Secrets と Variables に以下を設定します。未設定のときはデバッグ鍵で署名されるため、上書きアップデートができません。

| Secret | 内容 |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | `base64 -w0 release.jks` の出力 |
| `SIGNING_STORE_PASSWORD` | キーストアのパスワード |
| `SIGNING_KEY_PASSWORD` | 鍵のパスワード |

| Variable | 内容 |
|---|---|
| `SIGNING_KEY_ALIAS` | 鍵のエイリアス（秘密ではないため Variable にする。Secret にするとログ中の同じ文字列が伏せ字になる） |

```sh
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias mqr
```

### Google Play

Run workflow でのリリース時に AAB を Google Play にアップロードできます（鍵を保存しない Workload Identity Federation 方式）。設定手順は [docs/google-play.md](docs/google-play.md)。

## ライセンス

[MIT](LICENSE)。同梱しているサードパーティ ソフトウェアのライセンスは [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
