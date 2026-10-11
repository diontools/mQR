# リリース

Actions → build → Run workflow で `main` を選び（main 以外ではエラー）、バージョン（例: `1.0.0`）を入力すると、GitHub Actions がビルドし、タグ `v1.0.0` を作成して APK をリリースに添付します。リリース本文は `whatsnew/*.txt`（リリース前の PR で更新する）。タグを手動で push してもリリースは作成されません。

「タグと GitHub リリースを作る」のチェックを外すと、タグとリリースを作らずに Google Play にだけアップロードします。versionCode は実行ごとに増える（`GITHUB_RUN_NUMBER`）ので、同じバージョンのまま何度でも内部テストに出せます。本番に出す版だけチェックを入れて実行し、Play Console で昇格します。

```sh
gh workflow run build.yml -R diontools/mQR --ref main -f version=1.0.0
```

## 署名鍵

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

## Google Play

Run workflow でのリリース時に AAB を Google Play にアップロードできます（鍵を保存しない Workload Identity Federation 方式）。設定手順は [google-play.md](google-play.md)。
