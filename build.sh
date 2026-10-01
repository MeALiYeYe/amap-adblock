#!/usr/bin/env bash
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$ROOT/tools"
SDK="$TOOLS/android-sdk"
BT="$SDK/build-tools/34.0.0"
JDK="$TOOLS/jdk/jdk-17.0.13+11"
JAVA="$JDK/bin/java.exe"
JAVAC="$JDK/bin/javac.exe"
AAPT2="$BT/aapt2.exe"
ZIPALIGN="$BT/zipalign.exe"
D8="$BT/lib/d8.jar"
APKSIGNER="$BT/lib/apksigner.jar"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
API="$TOOLS/api-82.jar"
MOD="$ROOT/module"
OUT="$ROOT/out"
KS="$ROOT/debug.keystore"

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/obj" "$OUT/gen"

echo "[1/6] aapt2 compile"
"$AAPT2" compile --dir "$MOD/res" -o "$OUT/res.zip"

echo "[2/6] aapt2 link"
"$AAPT2" link -o "$OUT/unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$MOD/AndroidManifest.xml" \
  --auto-add-overlay \
  --min-sdk-version 24 --target-sdk-version 33 \
  -A "$MOD/assets" \
  "$OUT/res.zip"

echo "[3/6] javac"
find "$MOD/src" -name "*.java" | sed 's|^/c/|C:/|' > "$OUT/sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR:$API" \
  -d "$OUT/obj" @"$OUT/sources.txt"

echo "[4/6] d8"
"$JAVA" -cp "$D8" com.android.tools.r8.D8 --lib "$ANDROID_JAR" --output "$OUT" \
  $(find "$OUT/obj" -name "*.class")

ls -la "$OUT/classes.dex"

echo "[5/6] add dex"
cp "$OUT/unsigned.apk" "$OUT/packed.apk"
cd "$OUT" && "$JDK/bin/jar.exe" uf "$OUT/packed.apk" classes.dex && cd "$ROOT"

echo "[6/6] zipalign + sign"
if [ ! -f "$KS" ]; then
  "$JDK/bin/keytool.exe" -genkeypair -v -keystore "$KS" -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Android Debug, OU=AmapAdBlock, O=AmapAdBlock, L=, S=, C=CN" >/dev/null
fi
"$ZIPALIGN" -f 4 "$OUT/packed.apk" "$OUT/aligned.apk"
"$JAVA" -jar "$APKSIGNER" sign --ks "$KS" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android --out "$OUT/AmapAdBlock.apk" "$OUT/aligned.apk"

echo "OK -> $OUT/AmapAdBlock.apk"
ls -la "$OUT/AmapAdBlock.apk"
