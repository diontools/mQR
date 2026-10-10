# mQR

最小限の権限・コード・サイズを目指した Android 用 QR コードリーダー/ジェネレーター。

- 権限: `CAMERA` のみ（`INTERNET` なし）
- 依存: ZXing core のみ（AndroidX / Kotlin なし）
- minSdk 26

## 機能

- カメラでスキャン（結果はコピー / 共有 / http(s) のみ開く）
- 画像ファイルからスキャン（ストレージ権限不要）
- テキストから生成し PNG で保存（SAF 経由、権限不要）
- 他アプリの「共有」や文字選択メニューからテキストを受け取って生成

## リリース

`v*` タグを push すると GitHub Actions がビルドし、APK をリリースに添付します。

```sh
git tag v1.0.0 && git push origin v1.0.0
```

### 署名鍵

リポジトリの Secrets に以下を設定します。未設定のときはデバッグ鍵で署名されるため、上書きアップデートができません。

| Secret | 内容 |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | `base64 -w0 release.jks` の出力 |
| `SIGNING_STORE_PASSWORD` | キーストアのパスワード |
| `SIGNING_KEY_ALIAS` | 鍵のエイリアス |
| `SIGNING_KEY_PASSWORD` | 鍵のパスワード |

```sh
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias mqr
```
