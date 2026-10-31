#!/usr/bin/env bash
set -e

VERSION_NAME="1.5"
VERSION_CODE=5
OUTPUT_APK_NAME="src-payhook-v${VERSION_NAME}.apk"

SDK_ROOT="$HOME/Library/Android/sdk"
BUILD_TOOLS="$SDK_ROOT/build-tools/34.0.0"
PLATFORM="$SDK_ROOT/platforms/android-34"

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
ANDROID_JAR="$PLATFORM/android.jar"

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
WEB_PUBLIC_DOWNLOAD="$(cd "$PROJECT_DIR/../public/download" 2>/dev/null && pwd || true)"
BUILD_DIR="$PROJECT_DIR/build"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/compiled_res" "$BUILD_DIR/gen" "$BUILD_DIR/classes"

echo "=== 1. Compiling resources with AAPT2 ==="
"$AAPT2" compile --dir "$PROJECT_DIR/src/main/res" -o "$BUILD_DIR/compiled_res/resources.zip"

echo "=== 2. Linking resources and generating R.java (v${VERSION_NAME}) ==="
"$AAPT2" link "$BUILD_DIR/compiled_res/resources.zip" \
  -I "$ANDROID_JAR" \
  --manifest "$PROJECT_DIR/src/main/AndroidManifest.xml" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --java "$BUILD_DIR/gen" \
  -o "$BUILD_DIR/app-res.apk"

echo "=== 3. Compiling Java sources ==="
javac -encoding UTF-8 \
  --release 8 \
  -g:lines,source \
  -cp "$ANDROID_JAR:$BUILD_DIR/gen" \
  -d "$BUILD_DIR/classes" \
  "$BUILD_DIR/gen/in/srcjdcoem/payhook/R.java" \
  "$PROJECT_DIR/src/main/java/in/srcjdcoem/payhook/BootReceiver.java" \
  "$PROJECT_DIR/src/main/java/in/srcjdcoem/payhook/HttpPostWorker.java" \
  "$PROJECT_DIR/src/main/java/in/srcjdcoem/payhook/PaymentNotificationListenerService.java" \
  "$PROJECT_DIR/src/main/java/in/srcjdcoem/payhook/MainActivity.java"

echo "=== 4. Dexing bytecode with D8 ==="
jar cf "$BUILD_DIR/classes.jar" -C "$BUILD_DIR/classes" .
"$D8" --release --min-api 26 --output "$BUILD_DIR" --lib "$ANDROID_JAR" "$BUILD_DIR/classes.jar"

echo "=== 5. Packaging APK ==="
cp "$BUILD_DIR/app-res.apk" "$BUILD_DIR/app-unaligned.apk"
cd "$BUILD_DIR"
zip -u "$BUILD_DIR/app-unaligned.apk" classes.dex
cd "$PROJECT_DIR"

echo "=== 6. Zipalign APK ==="
"$ZIPALIGN" -f 4 "$BUILD_DIR/app-unaligned.apk" "$BUILD_DIR/src-payhook-unsigned.apk"

echo "=== 7. Signing APK with permanent release keystore ==="
KEYSTORE="$PROJECT_DIR/release.keystore"
if [ ! -f "$KEYSTORE" ]; then
  echo "Generating permanent release keystore at: $KEYSTORE"
  keytool -genkeypair -v -keystore "$KEYSTORE" -alias srcpayhook -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass srcjdcoem2026 -keypass srcjdcoem2026 \
    -dname "CN=Student Representative Council, OU=SRC, O=JDCOEM, L=Nagpur, ST=Maharashtra, C=IN"
fi

"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:srcjdcoem2026 --key-pass pass:srcjdcoem2026 \
  --out "$PROJECT_DIR/$OUTPUT_APK_NAME" "$BUILD_DIR/src-payhook-unsigned.apk"

"$APKSIGNER" verify "$PROJECT_DIR/$OUTPUT_APK_NAME"

# Also maintain standard release alias and copy to web public/download
cp -f "$PROJECT_DIR/$OUTPUT_APK_NAME" "$PROJECT_DIR/src-payhook-release.apk"

if [ -d "$WEB_PUBLIC_DOWNLOAD" ]; then
  cp -f "$PROJECT_DIR/$OUTPUT_APK_NAME" "$WEB_PUBLIC_DOWNLOAD/$OUTPUT_APK_NAME"
  cp -f "$PROJECT_DIR/$OUTPUT_APK_NAME" "$WEB_PUBLIC_DOWNLOAD/src-payhook-release.apk"
  echo "Synchronized versioned APK to: $WEB_PUBLIC_DOWNLOAD/$OUTPUT_APK_NAME"
fi

echo "SUCCESS! Production APK generated at: $PROJECT_DIR/$OUTPUT_APK_NAME"
ls -lh "$PROJECT_DIR/$OUTPUT_APK_NAME"
