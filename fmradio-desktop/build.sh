#!/bin/bash
# Build FM Radio Desktop (Windows/Linux/Mac)
# Requires: kotlinc (Kotlin compiler), JDK 11+
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
OUT_DIR="$SCRIPT_DIR/build"
JAR_NAME="fmradio-desktop.jar"

KOTLINC="${KOTLINC:-/tmp/android-tools/kotlinc/bin/kotlinc}"
if [ ! -f "$KOTLINC" ]; then
    KOTLINC="$(which kotlinc 2>/dev/null || echo kotlinc)"
fi

echo "=== Building FM Radio Desktop ==="
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/classes"

echo "=== Compiling Kotlin sources ==="
"$KOTLINC" \
    -jvm-target 11 \
    -nowarn \
    $(find "$SRC_DIR" -name "*.kt" -type f) \
    -d "$OUT_DIR/classes" 2>&1

echo "=== Creating JAR ==="
cd "$OUT_DIR/classes"

# Create manifest
mkdir -p META-INF
cat > META-INF/MANIFEST.MF << 'MEOF'
Manifest-Version: 1.0
Main-Class: com.fmradio.ui.MainWindowKt
MEOF

jar cfm "$OUT_DIR/$JAR_NAME" META-INF/MANIFEST.MF .

echo "=== BUILD COMPLETE ==="
echo "JAR: $OUT_DIR/$JAR_NAME"
ls -lh "$OUT_DIR/$JAR_NAME"
echo ""
echo "To run:"
echo "  java -jar $OUT_DIR/$JAR_NAME"
echo ""
echo "Or on Windows:"
echo "  java -jar fmradio-desktop.jar"
echo ""
echo "Prerequisites:"
echo "  1. Install RTL-SDR drivers (Zadig + librtlsdr)"
echo "  2. Run: rtl_tcp -a 127.0.0.1"
echo "  3. Then launch this app and click Connect"
