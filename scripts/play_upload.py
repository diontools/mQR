#!/usr/bin/env python3
"""Google Play へ AAB をアップロードする（標準ライブラリのみ）。

  play_upload.py check <whatsnew_dir>
      リリースノートの検証のみ行う
  play_upload.py notes <whatsnew_dir>
      GitHub リリース用にリリースノートを連結して出力する
  play_upload.py upload <whatsnew_dir> <aab>
      GitHub Actions の OIDC トークンを Workload Identity Federation で
      サービスアカウントの短命トークンに交換し、Play Developer API で公開する

upload で使う環境変数:
  GCP_WIF_PROVIDER     projects/<番号>/locations/global/workloadIdentityPools/<pool>/providers/<provider>
  GCP_SERVICE_ACCOUNT  サービスアカウントのメールアドレス
  PACKAGE_NAME         アプリのパッケージ名
  VERSION_NAME         リリース名に使うバージョン
  PLAY_TRACK           トラック（既定: internal）
  PLAY_RELEASE_STATUS  completed / draft（既定: completed。未公開アプリは draft が必要）
  ACTIONS_ID_TOKEN_REQUEST_URL / ACTIONS_ID_TOKEN_REQUEST_TOKEN  GitHub が設定する
"""
import json
import os
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request

MAX_NOTES = 500  # Play の 1 言語あたりの上限
PLAY = "https://androidpublisher.googleapis.com"


def load_notes(directory):
    notes = []
    for f in sorted(pathlib.Path(directory).glob("*.txt")):
        text = f.read_text(encoding="utf-8").strip()
        if not text:
            sys.exit(f"{f}: 空です")
        if len(text) > MAX_NOTES:
            sys.exit(f"{f}: {len(text)} 文字（上限 {MAX_NOTES}）")
        notes.append({"language": f.stem, "text": text})
    if not notes:
        sys.exit(f"{directory}: リリースノート (*.txt) がありません")
    return notes


def request(method, url, token=None, body=None, content_type="application/json"):
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        headers["Content-Type"] = content_type
        if content_type == "application/json":
            body = json.dumps(body).encode()
    if body is None and method != "GET":
        body = b""  # Content-Length: 0 がないと 411 になる
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=300) as res:
            data = res.read()
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {url.split('?')[0]}: {e.code} {e.read().decode(errors='replace')}")
    return json.loads(data) if data else {}


def mask(value):
    print(f"::add-mask::{value}", flush=True)
    return value


def access_token():
    provider = os.environ["GCP_WIF_PROVIDER"]
    # 1. GitHub の OIDC トークン（audience はプロバイダの既定値に合わせる）
    url = os.environ["ACTIONS_ID_TOKEN_REQUEST_URL"] + "&audience=" + urllib.parse.quote(
        "https://iam.googleapis.com/" + provider, safe="")
    oidc = mask(request("GET", url, os.environ["ACTIONS_ID_TOKEN_REQUEST_TOKEN"])["value"])
    # 2. Security Token Service でフェデレーション トークンに交換
    federated = mask(request("POST", "https://sts.googleapis.com/v1/token", body={
        "grantType": "urn:ietf:params:oauth:grant-type:token-exchange",
        "audience": "//iam.googleapis.com/" + provider,
        "scope": "https://www.googleapis.com/auth/cloud-platform",
        "requestedTokenType": "urn:ietf:params:oauth:token-type:access_token",
        "subjectToken": oidc,
        "subjectTokenType": "urn:ietf:params:oauth:token-type:jwt",
    })["access_token"])
    # 3. サービスアカウントの短命アクセストークンを発行
    sa = os.environ["GCP_SERVICE_ACCOUNT"]
    return mask(request(
        "POST", f"https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/{sa}:generateAccessToken",
        federated, {"scope": ["https://www.googleapis.com/auth/androidpublisher"], "lifetime": "600s"},
    )["accessToken"])


def upload(notes, aab):
    token = access_token()
    app = f"{PLAY}/androidpublisher/v3/applications/{os.environ['PACKAGE_NAME']}"
    track = os.environ.get("PLAY_TRACK") or "internal"
    edit = request("POST", f"{app}/edits", token, {})["id"]
    bundle = request("POST", f"{PLAY}/upload/androidpublisher/v3/applications/{os.environ['PACKAGE_NAME']}"
                     f"/edits/{edit}/bundles?uploadType=media",
                     token, pathlib.Path(aab).read_bytes(), "application/octet-stream")
    request("PUT", f"{app}/edits/{edit}/tracks/{track}", token, {
        "track": track,
        "releases": [{
            "name": os.environ.get("VERSION_NAME", "").removeprefix("v"),
            "versionCodes": [str(bundle["versionCode"])],
            "status": os.environ.get("PLAY_RELEASE_STATUS") or "completed",
            "releaseNotes": notes,
        }],
    })
    request("POST", f"{app}/edits/{edit}:commit", token)
    print(f"versionCode {bundle['versionCode']} を {track} トラックに公開しました")


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    command, notes = sys.argv[1], load_notes(sys.argv[2])
    if command == "check":
        print(", ".join(f"{n['language']}: {len(n['text'])} 文字" for n in notes))
    elif command == "notes":
        print("\n\n".join(n["text"] for n in notes))
    elif command == "upload" and len(sys.argv) == 4:
        upload(notes, sys.argv[3])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
