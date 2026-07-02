#!/bin/bash
# Bump FM Radio version in all three places at once:
#   1. fmradio/build.gradle.kts            (versionCode, versionName)
#   2. fmradio-desktop/.../MainWindow.kt   (VERSION, VERSION_CODE, BUILD)
#   3. fmradio-version.json                (versionCode, versionName, releaseNotes)
#
# Usage:
#   ./bump-fmradio-version.sh <versionCode> <versionName> [release notes]
# Example:
#   ./bump-fmradio-version.sh 8 1.8 "Исправлен поиск станций"
set -e

CODE="$1"
NAME="$2"
NOTES="$3"

if [ -z "$CODE" ] || [ -z "$NAME" ]; then
    echo "Usage: $0 <versionCode> <versionName> [release notes]"
    echo "Example: $0 8 1.8 \"Исправлен поиск станций\""
    exit 1
fi
if ! [[ "$CODE" =~ ^[0-9]+$ ]]; then
    echo "ERROR: versionCode must be an integer, got: $CODE"
    exit 1
fi

DIR="$(cd "$(dirname "$0")" && pwd)"
GRADLE="$DIR/fmradio/build.gradle.kts"
MAINWINDOW="$DIR/fmradio-desktop/src/com/fmradio/ui/MainWindow.kt"
MANIFEST="$DIR/fmradio-version.json"
BUILD_TAG="$(date +%Y%m%d)-1"

# 1. Android gradle
sed -i -E "s/versionCode = [0-9]+/versionCode = $CODE/" "$GRADLE"
sed -i -E "s/versionName = \"[^\"]*\"/versionName = \"$NAME\"/" "$GRADLE"

# 2. Desktop MainWindow
sed -i -E "s/const val VERSION = \"[^\"]*\"/const val VERSION = \"$NAME\"/" "$MAINWINDOW"
sed -i -E "s/const val BUILD = \"[^\"]*\"/const val BUILD = \"$BUILD_TAG\"/" "$MAINWINDOW"
sed -i -E "s/const val VERSION_CODE = [0-9]+/const val VERSION_CODE = $CODE/" "$MAINWINDOW"

# 3. Update manifest (python for safe JSON quoting of release notes)
python3 - "$MANIFEST" "$CODE" "$NAME" "$NOTES" << 'PYEOF'
import json, sys
path, code, name, notes = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
with open(path) as f:
    m = json.load(f)
m["versionCode"] = code
m["versionName"] = name
if notes:
    m["releaseNotes"] = notes
with open(path, "w") as f:
    json.dump(m, f, ensure_ascii=False, indent=2)
    f.write("\n")
PYEOF

echo "=== Version bumped to $NAME (code $CODE, build $BUILD_TAG) ==="
echo "--- $GRADLE"
grep -E "versionCode|versionName" "$GRADLE"
echo "--- $MAINWINDOW"
grep -E "const val VERSION|const val BUILD|VERSION_CODE" "$MAINWINDOW" | head -3
echo "--- $MANIFEST"
cat "$MANIFEST"
echo ""
echo "Не забудьте: пересобрать APK/JAR и смержить fmradio-version.json в main,"
echo "иначе старые версии не увидят обновление."
