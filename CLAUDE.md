# CLAUDE.md

## Git

- コミットメッセージに `Co-Authored-By:` や `Claude-Session:` などの帰属トレーラーを付けない。
- PR 本文にも「Generated with Claude Code」やセッション URL などの帰属行を付けない。

## ブランチ運用

- main へ直接 push しない。作業ブランチから PR を作り、CI 成功後に squash マージする。
- リリースは Actions の Run workflow（main を選び version を指定）で行う。タグは自動で作成される。
- 利用者に見える変更をする PR では `whatsnew/*.txt`（Play と GitHub リリースのリリースノート、各 500 文字まで）も更新する。
