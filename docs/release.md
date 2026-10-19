# リリース

Actions → build → Run workflow でブランチとバージョン（例: `1.0.0`）を指定すると、GitHub Actions がビルドして Google Play にアップロードします。ブランチで動作が変わります（`main` と `dev` 以外ではエラー）。

| ブランチ | 動作 |
|---|---|
| `dev` | Google Play にだけ送る。タグと GitHub リリースは作らない |
| `main` | タグ `v1.0.0` を作成し、APK を添付した GitHub リリースを作る。Google Play にも送る |

versionCode は実行ごとに増える（`GITHUB_RUN_NUMBER`）ので、`dev` からは同じバージョンのまま何度でも内部テストに出せます。リリース本文は `whatsnew/*.txt`。タグを手動で push してもリリースは作成されません。

1. `dev` から次のバージョンで実行し、Play の内部テストで実機確認する。修正したら `dev` に積んで同じバージョンで再実行する
2. 問題が無ければ `CHANGELOG.md` の版を確定し、`dev` → `main` の PR を squash マージする
3. `main` から同じバージョンで実行し、Play Console でその版を本番に昇格する

```sh
gh workflow run build.yml -R diontools/mQR --ref dev -f version=1.0.0   # Play のテスト
gh workflow run build.yml -R diontools/mQR --ref main -f version=1.0.0  # 正式リリース
```

## 署名鍵

リポジトリの Actions の Secrets と Variables に以下を設定します。未設定のときはデバッグ鍵で署名されるため、上書きアップデートができません。鍵を使うのはバージョンを指定した Run workflow だけで、PR と push のビルドは常にデバッグ鍵で署名します。

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
