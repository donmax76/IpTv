#!/bin/bash
# Honest compile check for the Android fmradio module without the Android SDK
# (dl.google.com is unreachable from this environment, so Gradle/AGP can't run).
#
# CRITICAL: always check the EXIT CODE, never just grep for "error:".
# kotlinc gets OOM-killed (exit 137) on this module with its default heap and
# then prints NO errors — which reads exactly like success and silently hid a
# real compile failure (a missing android.os.Build import) that broke CI.
set -u
KOTLINC="${KOTLINC:-/tmp/kotlinc/bin/kotlinc}"
LIBS=/tmp/verify-libs
SRC=$(mktemp); find "$(dirname "$0")/fmradio/src/main/java" -name '*.kt' > "$SRC"

rm -rf "$LIBS/out"; mkdir -p "$LIBS/out"
"$KOTLINC" -J-Xmx6g -J-XX:MaxMetaspaceSize=1g \
  -cp "/tmp/android-all.jar:$LIBS/coroutines-core.jar:$LIBS/coroutines-android.jar" \
  -d "$LIBS/out" "@$SRC" \
  "$LIBS/stub/com/fmradio/R.kt" \
  "$LIBS/stub/com/fmradio/BuildConfig.kt" \
  "$LIBS/stub/androidx/core/content/FileProvider.kt" 2>&1 | tee /tmp/verify.log
rc=${PIPESTATUS[0]}
rm -f "$SRC"

echo "exit=$rc  errors=$(grep -c 'error:' /tmp/verify.log)  classes=$(find "$LIBS/out" -name '*.class' | wc -l)"
if [ "$rc" -eq 137 ]; then echo "FAIL: compiler OOM-killed — result is meaningless"; exit 1; fi
[ "$rc" -eq 0 ] && echo "OK" || { echo "FAIL"; exit 1; }
