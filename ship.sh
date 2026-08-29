#!/bin/bash
set -e

echo "🚀 [Omni Hub Ship] Initializing Forge & Ship Pipeline..."

# === Robust Normalization Engine ===
normalize_slug() {
    local input="$1"
    local base="${input%.[zZ][iI][pP]}"
    base=$(echo "$base" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9_]+/_/g' | sed -E 's/_+/_/g' | sed -E 's/^[_]+|[_]+$//g')
    [ -z "$base" ] && base="module_$(date +%s)"
    echo "$base"
}

normalize_filename() {
    local input="$1"
    local base="${input%.[zZ][iI][pP]}"
    base=$(echo "$base" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9_-]+/_/g' | sed -E 's/_+/_/g' | sed -E 's/^[_ -]+|[_ -]+$//g')
    [ -z "$base" ] && base="module_$(date +%s)"
    echo "${base}.zip"
}

RAW_LABEL="${1:-${APP_LABEL:-sample_utility}}"
RAW_TARGET="${2:-${TARGET_FILENAME:-${FILE_NAME:-sample_utility.zip}}}"
SKIP_RESOURCES="${3:-${SKIP_RESOURCES:-false}}"

APP_LABEL="$RAW_LABEL"
MODULE_ID=$(normalize_slug "$RAW_LABEL")
TARGET_FILENAME=$(normalize_filename "$RAW_TARGET")

SUPABASE_URL="${SUPABASE_URL:-https://vlzgfaqrnyiqfxxxvtas.supabase.co}"
ANON_KEY="${ANON_KEY:-eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZsemdmYXFybnlpcWZ4eHh2dGFzIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjU1NTk5NDAsImV4cCI6MjA4MTEzNTk0MH0.y93d68JWyGL7NKXZEHLunAuayMEWw1K6yATFGLxkUxY}"

echo "🏷️  App Display Name: '$APP_LABEL'"
echo "🆔 Canonical Module ID: '$MODULE_ID'"
echo "📦 Normalized Storage Filename: '$TARGET_FILENAME'"

# 1. Forge Dynamic Module DEX & Bundle Archive
chmod +x forge.sh
./forge.sh "$APP_LABEL" "$TARGET_FILENAME" "$SKIP_RESOURCES"

BUNDLE_PATH="bundle.zip"
if [ ! -f "$BUNDLE_PATH" ] && [ -f "$TARGET_FILENAME" ]; then
    BUNDLE_PATH="$TARGET_FILENAME"
    cp -f "$BUNDLE_PATH" bundle.zip
fi

if [ ! -f "$BUNDLE_PATH" ]; then
    FOUND_ZIP=$(find . -maxdepth 1 -name "*.zip" | head -1 | sed 's|^\./||')
    if [ -n "$FOUND_ZIP" ]; then
        BUNDLE_PATH="$FOUND_ZIP"
        cp -f "$BUNDLE_PATH" bundle.zip
    else
        echo "❌ ERROR: Bundle file '$BUNDLE_PATH' was not found after compilation."
        exit 1
    fi
fi

# 2. Upload Bundle to Supabase Storage
echo "📦 Uploading $BUNDLE_PATH to Supabase Storage bucket 'omni-modules' as '$TARGET_FILENAME'..."
STORAGE_STATUS=$(curl -s -o response_storage.txt -w "%{http_code}" -X POST "$SUPABASE_URL/storage/v1/object/omni-modules/$TARGET_FILENAME" \
  -H "Authorization: Bearer $ANON_KEY" \
  -H "apikey: $ANON_KEY" \
  -H "Content-Type: application/zip" \
  -H "x-upsert: true" \
  --data-binary "@$BUNDLE_PATH")

if [ "$STORAGE_STATUS" -ge 200 ] && [ "$STORAGE_STATUS" -lt 300 ]; then
  echo "✅ Storage upload complete (HTTP $STORAGE_STATUS)."
else
  echo "❌ Storage upload FAILED (HTTP $STORAGE_STATUS)."
  cat response_storage.txt 2>/dev/null || true
  exit 1
fi

DOWNLOAD_URL="$SUPABASE_URL/storage/v1/object/public/omni-modules/$TARGET_FILENAME"
echo "🌐 Public CDN URL: $DOWNLOAD_URL"

# 3. Extract Manifest and Upsert to Cloud Database Catalog
echo "📝 Extracting manifest metadata from $BUNDLE_PATH and updating cloud catalog..."
python3 - << EOF
import zipfile, json, os, urllib.request, sys

bundle_file = '$BUNDLE_PATH'
app_label = '$APP_LABEL'
module_id = '$MODULE_ID'
target_filename = '$TARGET_FILENAME'
download_url = '$DOWNLOAD_URL'
supabase_url = '$SUPABASE_URL'
anon_key = '$ANON_KEY'

try:
    with zipfile.ZipFile(bundle_file, 'r') as z:
        manifest = json.loads(z.read('plugin.json').decode('utf-8'))
except Exception as e:
    print(f'⚠️ Warning: Could not read plugin.json from {bundle_file}: {e}')
    manifest = {}

payload = {
    'id': manifest.get('id', module_id),
    'name': manifest.get('name', app_label),
    'description': manifest.get('description', 'Dynamic Omni Hub Module'),
    'version': manifest.get('version', '1.0.0'),
    'entry_class': manifest.get('entryClass', 'com.omni.plugin.browser.OmniBrowser'),
    'file_name': target_filename,
    'download_url': download_url
}

req = urllib.request.Request(
    f"{supabase_url}/rest/v1/omni_modules?on_conflict=id",
    data=json.dumps(payload).encode('utf-8'),
    headers={
        'Authorization': f"Bearer {anon_key}",
        'apikey': anon_key,
        'Content-Type': 'application/json',
        'Prefer': 'resolution=merge-duplicates'
    },
    method='POST'
)

try:
    with urllib.request.urlopen(req) as resp:
        print(f"✅ Omni Hub Cloud Catalog Updated successfully (HTTP {resp.status}).")
except Exception as e:
    print(f"❌ Database catalog update FAILED: {e}", file=sys.stderr)
    sys.exit(1)
EOF

echo "🎉 [Omni Hub Ship] Successfully forged, uploaded, and cataloged '$APP_LABEL'!"