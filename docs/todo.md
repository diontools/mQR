# 作業予定

2026-10-19 のコード調査で見つかった指摘と対応予定。全ファイルの通読と公式ドキュメントの確認によるもので、実機では未確認。対応したらチェックを入れ、PR 番号を書き添える。

## 優先度高: 実害のある不具合

利用者に見える変更なので `whatsnew/*.txt` も更新する。

- [x] **1. 大文字スキームの URL が「開く」で開けない**（`ScanActivity.java` の `showResult`） (#24)
    - 現象: `HTTPS://EXAMPLE.COM/` でも「開く」ボタンは出るが、押すと「失敗しました」になる
    - 原因: スキーム判定は `equalsIgnoreCase` だが、インテントフィルタの照合は大文字小文字を区別する。英数字モードで小さくするため大文字にした URL の QR は珍しくない
    - 対応: `Uri uri = Uri.parse(text.trim()).normalizeScheme();`
    - 確認: `HTTPS://GITHUB.COM/DIONTOOLS/MQR` の QR を読み、「開く」でブラウザが開くこと
- [x] **2. `camera.autofocus` の uses-feature が無く、Play で配信対象が狭まる**（`AndroidManifest.xml`） (#24)
    - 現象: オートフォーカスの無い端末（固定焦点カメラ、一部のタブレットや Chromebook）が配信対象外になる
    - 原因: `CAMERA` 権限は `android.hardware.camera` と `android.hardware.camera.autofocus` を暗黙に必須にする（[uses-feature の Table 2](https://developer.android.com/guide/topics/manifest/uses-feature-element)）。宣言済みなのは前者だけ
    - 対応: `<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />` を追加する。コードは対応モードがあるときだけ AF を使うので変更不要
    - 確認: `aapt2 dump badging` の出力に autofocus の `uses-implied-feature` が出ないこと

## 優先度中・低: 軽微な不具合

どれも起こりにくいか見た目の問題。3 と 4 は修正が小さい。

- [x] **3. （中）ファイル選択画面が無い端末でクラッシュする**（`ScanActivity.onCreate`、`GenerateActivity.onCreate`） (#24)
    - `ACTION_GET_CONTENT` と `ACTION_CREATE_DOCUMENT` の `ActivityNotFoundException` を捕捉し、「失敗しました」を出す
- [ ] **4. （中）HandlerThread を `onResume` ごとに作り直している**（`ScanActivity.onResume` / `onPause`）
    - 一時停止→再開が素早いと、前のスレッドの `release()` より先に `Camera.open()` が走り「カメラを使用できません」になり得る
    - `onRequestPermissionsResult` や停止中の `resumeScan()` が終了済みスレッドに post して警告ログが出る（動作は `onResume` で回復する）
    - 対応: スレッドを `onCreate`〜`onDestroy` の 1 本にする
- [ ] **5. （低）大画面で回転すると結果ダイアログが消える**
    - Android 16 以降は大画面で縦固定が効かず再生成され、AlertDialog が WindowLeaked になって結果が失われる
    - `asked` も保存していないので、権限を拒否した後は回転のたびに再要求とトーストが出る
    - 対応: 結果の文字列と `asked` を `onSaveInstanceState` で保存し、`onDestroy` でダイアログを閉じる
- [ ] **6. （低）カメラを他アプリに奪われると止まったまま**
    - `Camera.ErrorCallback` が無い。マルチウィンドウで奪われると次の再開までプレビューが止まる
- [ ] **7. （低）高さの小さい画面で案内文が下の帯と重なる**（`ScanActivity.Overlay.onDraw`）
    - 分割画面などで、読み取り枠の下端と案内文がボタン帯にかかる。枠の配置を帯を除いた領域で計算する
- [ ] **8. （低）毎キー入力で同期的にエンコードしている**（`GenerateActivity.update`）
    - バージョン 40 付近の長文では、古い端末で入力がもたつき得る。実機で問題が出たら、入力が止まってから生成するなどで対応する

## CI・ライセンスの強化策

- [ ] **9. PR のビルドにも署名鍵と書き込み権限が渡る**（`.github/workflows/build.yml`）
    - `pull_request` でも署名鍵の Secrets をデコードし、ジョブ全体に `contents: write` が付いている。フォークの PR には Secrets が渡らないので現状の危険は小さいが、GitHub リリースの APK はこの鍵で署名している
    - 対応: 鍵のデコードと書き込み権限をリリース（`workflow_dispatch` で version 指定）に限る。PR と push はデバッグ鍵で署名する
- [ ] **10. APK に Apache License の本文が入っていない**（要検討）
    - `META-INF/**` を除外しており、アプリ内にもライセンス表示が無い。Apache-2.0 §4(a) を厳密に読むと配布物に本文を添える必要がある
    - 現状は GitHub リリースの `THIRD_PARTY_NOTICES.md` とストア説明のリンクで示している。サイズとの兼ね合いで、対応するかを決める

## 進め方

1. 1〜3 を 1 つの PR にまとめ、`whatsnew/*.txt` と `CHANGELOG.md` を更新する（次のリリース 1.0.2 向け）
2. 4〜7 は `ScanActivity` のライフサイクル整理として別の PR にする。実機で回転・分割画面・権限拒否を確認する
3. 9 は CI だけの PR にする（利用者に見えないので whatsnew は不要）
4. 8 と 10 は必要になったら対応する

## 確認済みで問題なし

- プレビューの center-crop 行列と、読み取り枠からフレーム座標への換算（`crop`）。前面カメラの鏡像は表示だけで解析データには影響しない
- 画像読み取りは `inSampleSize` で一辺 2048 以下に縮小しておりメモリは十分。読み取り中に閉じた場合は `isDestroyed` で防げている
- `onActivityResult` は状態復元の後に届くので、保存時に `qr` は再生成済み。ラジオボタンの固定 ID による復元も正しい
- ASCII 以外を含むときだけ UTF-8（ECI 付き）にする判定と、ZXing の既定動作の組み合わせ
- `play.sh`: エラー時に URL のクエリを出さない、`::add-mask::`、`inherit_errexit`、jq のパイプの優先順位
- `build.yml`: `INPUT_VERSION` を環境変数で受けているのでスクリプト注入はできない。リリースを main だけに限るチェックも機能している
