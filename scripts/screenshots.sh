#!/usr/bin/env bash
# ストア掲載用スクリーンショットを撮る。起動中のエミュレータ（API 33 以上）を adb で操作する。
#
#   screenshots.sh <出力先>   <出力先>/<言語>/{1-scan,2-generate}.png を作る
#
# 事前に ./gradlew assembleDebug assembleDebugAndroidTest でビルドしておく。
# 撮影内容は app/src/androidTest/.../Screenshots.java。
set -euo pipefail
shopt -s inherit_errexit

[ $# -eq 1 ] || { sed -n '2,7p' "$0" >&2; exit 2; }
OUT=$1
PKG=io.github.diontools.mqr
LOCALES=(en-US ja-JP) # whatsnew/ と同じ
APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

cleanup() {
  adb shell wm size reset || true
  adb shell wm density reset || true
}
trap cleanup EXIT

# 9:16 にする（Play は長辺が短辺の 2 倍以下）
adb shell wm size 1080x1920
adb shell wm density 420

# カメラ権限は付与しない（Screenshots.java がカメラを開かせずに合成画像を描く）
adb uninstall "$PKG" > /dev/null 2>&1 || true
adb uninstall "$PKG.test" > /dev/null 2>&1 || true
adb install "$APK"
adb install "$TEST_APK"

for locale in "${LOCALES[@]}"; do
  adb shell cmd locale set-app-locales "$PKG" --locales "$locale"
  result=$(adb shell am instrument -w "$PKG.test/$PKG.Screenshots")
  echo "$result"
  grep -q 'INSTRUMENTATION_CODE: -1' <<< "$result" || {
    echo "$locale: 撮影に失敗しました" >&2
    adb logcat -d -b crash >&2 || true
    exit 1
  }
  mkdir -p "$OUT/$locale"
  for name in 1-scan:scan 2-generate:generate; do
    adb exec-out run-as "$PKG" cat "files/screenshots/${name#*:}.png" > "$OUT/$locale/${name%%:*}.png"
  done
done
ls -l "$OUT"/*
