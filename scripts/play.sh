#!/usr/bin/env bash
# Google Play へ AAB をアップロードする（bash + curl + jq のみ）。
#
#   play.sh check  <whatsnew_dir>        リリースノートを検証する
#   play.sh notes  <whatsnew_dir>        GitHub リリース用にリリースノートを連結して出力する
#   play.sh upload <whatsnew_dir> <aab>  GitHub Actions の OIDC トークンを Workload Identity
#                                        Federation でサービスアカウントの短命トークンに交換し、
#                                        Play Developer API で公開する
#
# upload で使う環境変数:
#   GCP_WIF_PROVIDER     projects/<番号>/locations/global/workloadIdentityPools/<pool>/providers/<provider>
#   GCP_SERVICE_ACCOUNT  サービスアカウントのメールアドレス
#   PACKAGE_NAME         アプリのパッケージ名
#   VERSION_NAME         リリース名に使うバージョン
#   PLAY_TRACK           トラック（既定: internal）
#   PLAY_RELEASE_STATUS  completed / draft（既定: completed。未公開アプリは draft が必要）
#   ACTIONS_ID_TOKEN_REQUEST_URL / ACTIONS_ID_TOKEN_REQUEST_TOKEN  GitHub が設定する
set -euo pipefail
shopt -s inherit_errexit # コマンド置換の中でも失敗で止める

MAX_NOTES=500 # Play の 1 言語あたりの上限
PLAY=https://androidpublisher.googleapis.com

usage() { sed -n '2,9p' "$0" >&2; exit 2; }

# <dir>/*.txt を [{"language": ファイル名, "text": 本文}] にする
notes_json() {
  local f files=("$1"/*.txt)
  [ -e "${files[0]}" ] || { echo "$1: リリースノート (*.txt) がありません" >&2; exit 1; }
  for f in "${files[@]}"; do
    jq -Rs --arg lang "$(basename "$f" .txt)" --arg file "$f" --argjson max "$MAX_NOTES" '
      sub("^\\s+"; "") | sub("\\s+$"; "") as $t
      | if $t == "" then error("\($file): 空です")
        elif ($t | length) > $max then error("\($file): \($t | length) 文字（上限 \($max)）")
        else {language: $lang, text: $t} end' "$f"
  done | jq -s .
}

# api <メソッド> <URL> <トークン> [curl の引数...]  失敗時は応答本文を表示して終了する
api() {
  local method=$1 url=$2 token=$3 res
  shift 3
  if ! res=$(curl -sS --fail-with-body -X "$method" ${token:+-H "Authorization: Bearer $token"} "$@" "$url"); then
    echo "$method ${url%%\?*}: $res" >&2
    exit 1
  fi
  echo "$res"
}

# コマンド置換の中からもログに出せるよう元の標準出力を fd 3 に残す
exec 3>&1
mask() { echo "::add-mask::$1" >&3; }

access_token() {
  local provider=$GCP_WIF_PROVIDER audience oidc federated
  # 1. GitHub の OIDC トークン（audience はプロバイダの既定値に合わせる）
  audience=$(jq -rn --arg a "https://iam.googleapis.com/$provider" '$a | @uri')
  oidc=$(api GET "$ACTIONS_ID_TOKEN_REQUEST_URL&audience=$audience" "$ACTIONS_ID_TOKEN_REQUEST_TOKEN" | jq -r .value)
  mask "$oidc"
  # 2. Security Token Service でフェデレーション トークンに交換する
  federated=$(jq -n --arg aud "//iam.googleapis.com/$provider" --arg jwt "$oidc" '{
      grantType: "urn:ietf:params:oauth:grant-type:token-exchange",
      audience: $aud,
      scope: "https://www.googleapis.com/auth/cloud-platform",
      requestedTokenType: "urn:ietf:params:oauth:token-type:access_token",
      subjectToken: $jwt,
      subjectTokenType: "urn:ietf:params:oauth:token-type:jwt"
    }' | api POST https://sts.googleapis.com/v1/token "" --json @- | jq -r .access_token)
  mask "$federated"
  # 3. サービスアカウントの短命アクセストークンを発行する
  api POST "https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/$GCP_SERVICE_ACCOUNT:generateAccessToken" \
    "$federated" --json '{"scope": ["https://www.googleapis.com/auth/androidpublisher"], "lifetime": "600s"}' \
    | jq -r .accessToken
}

upload() {
  local notes=$1 aab=$2 token app track edit code
  token=$(access_token)
  mask "$token"
  app="$PLAY/androidpublisher/v3/applications/$PACKAGE_NAME"
  track=${PLAY_TRACK:-internal}
  edit=$(api POST "$app/edits" "$token" --json '{}' | jq -r .id)
  code=$(api POST "$PLAY/upload/androidpublisher/v3/applications/$PACKAGE_NAME/edits/$edit/bundles?uploadType=media" \
    "$token" -H 'Content-Type: application/octet-stream' --data-binary "@$aab" | jq -r .versionCode)
  jq -n --arg track "$track" --arg name "${VERSION_NAME#v}" --arg code "$code" \
    --arg status "${PLAY_RELEASE_STATUS:-completed}" --argjson notes "$notes" '{
      track: $track,
      releases: [{name: $name, versionCodes: [$code], status: $status, releaseNotes: $notes}]
    }' | api PUT "$app/edits/$edit/tracks/$track" "$token" --json @- >/dev/null
  api POST "$app/edits/$edit:commit" "$token" -d '' >/dev/null
  echo "versionCode $code を $track トラックに公開しました"
}

[ $# -ge 2 ] || usage
notes=$(notes_json "$2")
case $1 in
  check) jq -r 'map("\(.language): \(.text | length) 文字") | join(", ")' <<<"$notes" ;;
  notes) jq -r 'map(.text) | join("\n\n")' <<<"$notes" ;;
  upload) [ $# -eq 3 ] || usage; upload "$notes" "$3" ;;
  *) usage ;;
esac
