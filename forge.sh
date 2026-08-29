#!/bin/bash
set -e

echo "🚀 [Omni Hub Forge] Starting Dynamic DEX Compilation Pipeline..."

# Arguments or Environment Variables from Conduit IDE / CI
APP_LABEL="${1:-${PLUGIN_NAME:-${APP_LABEL:-sample_utility}}}"
OUTPUT_FILE="${2:-${OUTPUT_FILENAME:-${FILE_NAME:-bundle.zip}}}"
SKIP_RESOURCES="${3:-${SKIP_RESOURCES:-false}}"

# Ensure OUTPUT_FILE always ends with .zip
if [[ "$OUTPUT_FILE" != *.zip ]]; then
    OUTPUT_FILE="${OUTPUT_FILE}.zip"
fi

WORKDIR="dynamic_build"
CLASSES_DIR="$WORKDIR/classes"
DEX_DIR="$WORKDIR/dex"
KOTLIN_DIR="$WORKDIR/kotlinc"
LIBS_DIR="app/build/harvested_libs"

rm -rf "$CLASSES_DIR" "$DEX_DIR" "$WORKDIR/bundle"
mkdir -p "$CLASSES_DIR"
mkdir -p "$DEX_DIR"

# 1. Download Kotlin Compiler 1.9.24 if missing
if [ ! -d "$KOTLIN_DIR" ]; then
    echo "⬇️ Downloading Kotlin Compiler 1.9.24..."
    wget -q https://github.com/JetBrains/kotlin/releases/download/v1.9.24/kotlin-compiler-1.9.24.zip -O "$WORKDIR/kotlin.zip"
    unzip -q "$WORKDIR/kotlin.zip" -d "$WORKDIR/"
fi

# 2. Download Compose Compiler Plugin 1.5.14 if missing
if [ ! -f "$WORKDIR/compose-compiler.jar" ]; then
    echo "⬇️ Downloading Compose Compiler Plugin 1.5.14..."
    wget -q https://dl.google.com/dl/android/maven2/androidx/compose/compiler/compiler-hosted/1.5.14/compiler-hosted-1.5.14.jar -O "$WORKDIR/compose-compiler.jar"
fi

# 3. Assemble Classpath from Harvested Host Dependencies
echo "📦 Assembling Classpath from Host Armory..."
CP=$(find "$LIBS_DIR" -name "*.jar" | tr '\n' ':')
if [ -z "$CP" ]; then
    echo "❌ ERROR: Harvested libs directory is empty. Run 'gradle app:harvestDeps' first."
    exit 1
fi

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}"
CP="$CP:${ANDROID_SDK_ROOT}/platforms/android-34/android.jar"

# 4. Resolve Target Module Source & Resources based on App Label
SLUG=$(echo "$APP_LABEL" | tr '[:upper:]' '[:lower:]' | tr ' ' '_')
echo "🎯 Resolving target module for App Label: '$APP_LABEL' (Slug: '$SLUG')..."

SRC_TARGET=""
RES_TARGET=""
MANIFEST_TARGET=""

if [ -d "plugins/$SLUG/src" ]; then
    SRC_TARGET="plugins/$SLUG/src"
    RES_TARGET="plugins/$SLUG/res"
    MANIFEST_TARGET="plugins/$SLUG/plugin.json"
elif [ -d "plugins/$SLUG" ]; then
    SRC_TARGET="plugins/$SLUG"
    RES_TARGET="plugins/$SLUG/res"
    MANIFEST_TARGET="plugins/$SLUG/plugin.json"
elif [ -f "plugin_src/${SLUG}.kt" ]; then
    SRC_TARGET="plugin_src/${SLUG}.kt"
    RES_TARGET="plugin_res"
    MANIFEST_TARGET="plugin_src/plugin.json"
elif [ -f "plugin_src/${APP_LABEL}.kt" ]; then
    SRC_TARGET="plugin_src/${APP_LABEL}.kt"
    RES_TARGET="plugin_res"
    MANIFEST_TARGET="plugin_src/plugin.json"
elif [ -d "plugin_src" ]; then
    SRC_TARGET="plugin_src"
    RES_TARGET="plugin_res"
    MANIFEST_TARGET="plugin_src/plugin.json"
else
    echo "❌ ERROR: Could not locate source directory for '$APP_LABEL'."
    exit 1
fi

echo "📂 Target Source: $SRC_TARGET"

# 5. Compile Dynamic Source against Contract & Compose Plugin
echo "⚙️ Compiling Kotlin & Compose UI..."
API_SOURCES=$(find app/src/main/java/com/omni/hub/api -name "*.kt" 2>/dev/null)

$WORKDIR/kotlinc/bin/kotlinc \
    $SRC_TARGET \
    $API_SOURCES \
    -cp "$CP" \
    -Xplugin="$WORKDIR/compose-compiler.jar" \
    -jvm-target 17 \
    -d "$CLASSES_DIR"

# 6. Strip Contract Interface Class to avoid ClassCastException
echo "🧹 Stripping duplicate contract classes from bundle..."
find "$CLASSES_DIR" -path "*/com/omni/hub/api/*" -delete

# 7. Convert Bytecode to Android DEX via d8
echo "🔩 Converting Bytecode to classes.dex..."
BUILD_TOOLS_DIR=$(ls -d ${ANDROID_SDK_ROOT}/build-tools/34.* | head -1)

D8_CLASSPATH=""
for jar in $(find "$LIBS_DIR" -name "*.jar"); do
    D8_CLASSPATH="$D8_CLASSPATH --classpath $jar"
done

$BUILD_TOOLS_DIR/d8 \
    --output "$DEX_DIR/" \
    --lib "${ANDROID_SDK_ROOT}/platforms/android-34/android.jar" \
    $D8_CLASSPATH \
    --min-api 26 \
    $(find "$CLASSES_DIR" -name "*.class")

# 8. Package Bundle ZIP with Manifest
echo "📦 Packaging final $OUTPUT_FILE..."
BUNDLE_DIR="$WORKDIR/bundle"
mkdir -p "$BUNDLE_DIR/res"
cp "$DEX_DIR/classes.dex" "$BUNDLE_DIR/"

# Auto-Discovery vs Auto-Generation of plugin.json
if [ -n "$MANIFEST_TARGET" ] && [ -f "$MANIFEST_TARGET" ]; then
    echo "📄 Using existing manifest: $MANIFEST_TARGET"
    cp "$MANIFEST_TARGET" "$BUNDLE_DIR/plugin.json"
else
    echo "🔍 Auto-discovering PluginEntry class from source..."
    DISCOVERED_PKG=$(grep -hr "^package " $SRC_TARGET 2>/dev/null | head -1 | awk '{print $2}' | tr -d '\r')
    DISCOVERED_CLASS=$(grep -hrE "(class|object) [A-Za-z0-9_]+.*PluginEntry" $SRC_TARGET 2>/dev/null | head -1 | sed -E 's/.*(class|object) ([A-Za-z0-9_]+).*/\2/' | tr -d '\r')

    if [ -n "$DISCOVERED_PKG" ] && [ -n "$DISCOVERED_CLASS" ]; then
        ENTRY_FQCN="${DISCOVERED_PKG}.${DISCOVERED_CLASS}"
    else
        ENTRY_FQCN="com.omni.plugin.SampleUtility"
    fi

    echo "✨ Auto-generated manifest for: $ENTRY_FQCN"
    cat <<EOF > "$BUNDLE_DIR/plugin.json"
{
  "id": "$SLUG",
  "name": "$APP_LABEL",
  "version": "1.0.0",
  "entryClass": "$ENTRY_FQCN",
  "description": "Dynamic Omni Hub Module"
}
EOF
fi

if [ "$SKIP_RESOURCES" != "true" ] && [ -n "$RES_TARGET" ] && [ -d "$RES_TARGET" ]; then
    echo "🖼️ Injecting resources from $RES_TARGET..."
    cp -r $RES_TARGET/* "$BUNDLE_DIR/res/" 2>/dev/null || true
fi

cd "$BUNDLE_DIR" && zip -r "../../$OUTPUT_FILE" . && cd ../..
cp -f "$OUTPUT_FILE" bundle.zip

echo "✅ [Omni Hub Forge] Build Complete: $OUTPUT_FILE"