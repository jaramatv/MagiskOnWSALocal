#!/usr/bin/env bash
# Se ejecuta dentro del emulador de CI.
set -u
OUT=release/test-results; rm -rf $OUT; mkdir -p $OUT
adb wait-for-device; adb shell settings put global window_animation_scale 0
echo "## Tests instrumentados (Suno real)"
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w -r com.sunodl.app.test/androidx.test.runner.AndroidJUnitRunner | tee $OUT/instrumented.txt
adb logcat -d -s SunoLiveTest:I | tee $OUT/instrumented-log.txt
adb uninstall com.sunodl.app >/dev/null; adb uninstall com.sunodl.app.test >/dev/null
echo "## E2E APK release"
bash tools/e2e_release.sh app/build/outputs/apk/release/app-release.apk $OUT 2>&1 | tee $OUT/e2e.txt
grep -q "OK (2 tests)" $OUT/instrumented.txt
