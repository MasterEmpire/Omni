#!/bin/bash
set -e

echo "🚀 [Omni Hub Forge] Starting Dynamic DEX Compilation Pipeline..."

WORKDIR="dynamic_build"
CLASSES_DIR="$WORKDIR/classes"
DEX_DIR="$WORKDIR/dex"
KOTLIN_DIR="$WORKDIR/kotlinc"
LIBS_DIR="app/build/harvested_libs"

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

# 4. Compile Dynamic Source against Contract & Compose Plugin
echo "⚙️ Compiling Kotlin & Compose UI..."
$WORKDIR/kotlinc/bin/kotlinc \
    plugin_src/ \
    app/src/main/java/com/omni/hub/api/PluginEntry.kt \
    -cp "$CP" \
    -Xplugin="$WORKDIR/compose-compiler.jar" \
    -jvm-target 17 \
    -d "$CLASSES_DIR"

# 5. Strip Contract Interface Class to avoid ClassCastException
echo "🧹 Stripping duplicate contract classes from bundle..."
find "$CLASSES_DIR" -name "PluginEntry*" -delete

# 6. Convert Bytecode to Android DEX via d8
echo "🔩 Converting Bytecode to classes.dex..."
BUILD_TOOLS_DIR=$(ls -d ${ANDROID_SDK_ROOT}/build-tools/34.* | head -1)

$BUILD_TOOLS_DIR/d8 \
    --output "$DEX_DIR/" \
    --lib "${ANDROID_SDK_ROOT}/platforms/android-34/android.jar" \
    $(find "$CLASSES_DIR" -name "*.class")

# 7. Package Bundle ZIP
echo "📦 Packaging final bundle.zip..."
BUNDLE_DIR="$WORKDIR/bundle"
mkdir -p "$BUNDLE_DIR/res"
cp "$DEX_DIR/classes.dex" "$BUNDLE_DIR/"

if [ "$SKIP_RESOURCES" != "true" ] && [ -d "plugin_res" ]; then
    echo "🖼️ Injecting resources from plugin_res..."
    cp -r plugin_res/* "$BUNDLE_DIR/res/"
fi

cd "$BUNDLE_DIR" && zip -r ../../bundle.zip . && cd ../..

echo "✅ [Omni Hub Forge] Build Complete: bundle.zip"