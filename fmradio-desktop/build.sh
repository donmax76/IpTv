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

LIB_DIR="$SCRIPT_DIR/lib"
JNA_VERSION="5.14.0"
JNA_JAR="$LIB_DIR/jna-${JNA_VERSION}.jar"
JSON_VERSION="20231013"
JSON_JAR="$LIB_DIR/json-${JSON_VERSION}.jar"

echo "=== Building FM Radio Desktop ==="
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/classes"
mkdir -p "$LIB_DIR"

# Download JNA if not present
if [ ! -f "$JNA_JAR" ]; then
    echo "=== Downloading JNA ${JNA_VERSION} ==="
    curl -fSL -o "$JNA_JAR" \
        "https://repo1.maven.org/maven2/net/java/dev/jna/jna/${JNA_VERSION}/jna-${JNA_VERSION}.jar"
fi

# Download org.json if not present
if [ ! -f "$JSON_JAR" ]; then
    echo "=== Downloading org.json ${JSON_VERSION} ==="
    curl -fSL -o "$JSON_JAR" \
        "https://repo1.maven.org/maven2/org/json/json/${JSON_VERSION}/json-${JSON_VERSION}.jar"
fi

echo "=== Compiling Kotlin sources ==="
"$KOTLINC" \
    -jvm-target 11 \
    -nowarn \
    -classpath "$JNA_JAR:$JSON_JAR" \
    $(find "$SRC_DIR" -name "*.kt" -type f) \
    -d "$OUT_DIR/classes" 2>&1

echo "=== Creating JAR ==="
cd "$OUT_DIR/classes"

# Extract dependencies into output for fat jar
jar xf "$JNA_JAR"
jar xf "$JSON_JAR"

# Include Kotlin stdlib
KOTLINC_DIR="$(dirname "$(dirname "$KOTLINC")")"
KOTLIN_STDLIB="$KOTLINC_DIR/lib/kotlin-stdlib.jar"
KOTLIN_STDLIB_JDK8="$KOTLINC_DIR/lib/kotlin-stdlib-jdk8.jar"
KOTLIN_STDLIB_JDK7="$KOTLINC_DIR/lib/kotlin-stdlib-jdk7.jar"

if [ -f "$KOTLIN_STDLIB" ]; then
    jar xf "$KOTLIN_STDLIB"
    echo "  Included: kotlin-stdlib.jar"
fi
if [ -f "$KOTLIN_STDLIB_JDK8" ]; then
    jar xf "$KOTLIN_STDLIB_JDK8"
    echo "  Included: kotlin-stdlib-jdk8.jar"
fi
if [ -f "$KOTLIN_STDLIB_JDK7" ]; then
    jar xf "$KOTLIN_STDLIB_JDK7"
    echo "  Included: kotlin-stdlib-jdk7.jar"
fi

rm -rf META-INF

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

# Copy to project root (next to FmRadio.exe)
cp "$OUT_DIR/$JAR_NAME" "$SCRIPT_DIR/../$JAR_NAME"
echo "Copied to: $SCRIPT_DIR/../$JAR_NAME"

echo ""
echo "To run:"
echo "  java -jar $OUT_DIR/$JAR_NAME"
echo ""
echo "Or on Windows:"
echo "  Double-click FmRadio.exe (with fmradio-desktop.jar in same folder)"
echo ""
echo "Prerequisites:"
echo "  1. Install RTL-SDR drivers (Zadig + librtlsdr)"
echo "  2. Run: rtl_tcp -a 127.0.0.1"
echo "  3. Then launch this app and click Connect"
