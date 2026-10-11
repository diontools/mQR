# Google Play への自動アップロード

Actions → build → Run workflow で `main` とバージョンを指定すると、`play` ジョブが AAB を Google Play にアップロードします。

- 認証は Workload Identity Federation（WIF）。JSON 鍵などの長期の鍵は作らず、Secrets にも何も保存しない
- 実行のたびに GitHub の OIDC トークンを Google の短命トークン（10 分）に交換する。期限切れの更新作業はない
- サードパーティのアクションは使わない。処理は `scripts/play_upload.py`（Python 標準ライブラリのみ）
- 下の Variables が未設定なら `play` ジョブはスキップされ、GitHub リリースだけが作られる

## 1. Google Cloud

[Cloud Shell](https://shell.cloud.google.com/) などで実行します。`PROJECT_ID` は新規または既存のプロジェクトです。

```sh
PROJECT_ID=your-project-id
REPO_ID=1413411916   # diontools/mQR のリポジトリ ID（名前変更の影響を受けない）
gcloud config set project "$PROJECT_ID"
PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')

# 必要な API を有効化
gcloud services enable \
  iam.googleapis.com iamcredentials.googleapis.com sts.googleapis.com \
  androidpublisher.googleapis.com

# Workload Identity プールと GitHub 用プロバイダ
gcloud iam workload-identity-pools create github --location=global
gcloud iam workload-identity-pools providers create-oidc mqr \
  --location=global --workload-identity-pool=github \
  --issuer-uri=https://token.actions.githubusercontent.com \
  --attribute-mapping='google.subject=assertion.sub,attribute.repository_id=assertion.repository_id,attribute.environment=assertion.environment,attribute.ref=assertion.ref' \
  --attribute-condition="assertion.repository_id=='$REPO_ID' && assertion.environment=='play' && assertion.ref=='refs/heads/main'"

# アップロード専用のサービスアカウント（Google Cloud 上のロールは付与しない）
gcloud iam service-accounts create play-publisher --display-name='mQR Play publisher'
SA="play-publisher@$PROJECT_ID.iam.gserviceaccount.com"

# このリポジトリからだけサービスアカウントを借用できるようにする
gcloud iam service-accounts add-iam-policy-binding "$SA" \
  --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github/attribute.repository_id/$REPO_ID"

echo "GCP_WIF_PROVIDER=projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github/providers/mqr"
echo "GCP_SERVICE_ACCOUNT=$SA"
```

`attribute-condition` により、トークンを発行できるのは「このリポジトリ」の「`main` ブランチ」の「`play` Environment」で動くジョブだけです。フォークや他のブランチ、PR からは使えません。

## 2. Google Play Console

1. アプリを作成し、**最初の AAB を手動でアップロード**する（API は既存アプリにしか使えない）。AAB は任意の CI 実行の Artifacts（`aab`）から取得できる。このとき Play App Signing が有効になり、リポジトリの署名鍵は「アップロード鍵」になる
2. **ユーザーと権限** → **新しいユーザーを招待** で上の `GCP_SERVICE_ACCOUNT` のメールアドレスを追加
3. **アプリの権限** でこのアプリだけを追加し、次の権限だけを付与する
   - アプリ情報の表示（読み取り専用）
   - テスト トラックへのアプリのリリース
4. 本番への昇格は Play Console で手動で行う

## 3. GitHub

**Settings → Secrets and variables → Actions → Variables**

| Variable | 値 |
|---|---|
| `GCP_WIF_PROVIDER` | 手順 1 の出力 |
| `GCP_SERVICE_ACCOUNT` | 手順 1 の出力 |
| `PLAY_TRACK` | 省略時 `internal` |
| `PLAY_RELEASE_STATUS` | 省略時 `completed`。一度も公開していないアプリは `draft` にする |

**Settings → Environments → `play`**（初回実行で自動作成される。事前に作ってもよい）

- Deployment branches and tags: `main` のみ
- Required reviewers: 自分を追加すると、アップロード前に毎回承認が必要になる（任意）

## リリースノート

`whatsnew/<言語>.txt`（例: `ja-JP.txt`, `en-US.txt`）が Play のリリースノートと GitHub リリース本文になります。

- 1 言語あたり 500 文字まで。CI の「Check release notes」で検証する
- リリース前の PR で内容を更新する

## 注意

- GitHub リリースの APK（リポジトリの鍵で署名）と Play 版（Google の鍵で再署名）は署名が異なるため、相互に上書きインストールできない
- Play へのアップロードは Run workflow でのリリース時だけ。`v*` タグの push では行わない
- `play` ジョブだけが失敗した場合は **Re-run failed jobs** で再実行できる（同じ AAB・同じ `versionCode` を使う）。ワークフロー全体の再実行は GitHub リリースが既に存在するため失敗する
