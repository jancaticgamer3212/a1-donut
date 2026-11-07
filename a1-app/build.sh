#!/bin/sh
# Builds a1-donut.apk with aapt2 + javac + d8 + apksigner (no Gradle).
# Assets (rootfs + guest) are taken from ../android1-emulator/phone/a1.
set -e

SDK=${SDK:-/c/android-sdk}
BT=$SDK/build-tools/34.0.0
ANDROID_JAR=$SDK/platforms/android-32/android.jar
ROOT=$(cd "$(dirname "$0")" && pwd)
SRC=$ROOT/src
ASSETS=$ROOT/../android1-emulator/phone/a1
OUT=$ROOT/build
KEYSTORE=$ROOT/debug.keystore

w() { cygpath -m "$1"; }

for f in "$BT/aapt2.exe" "$BT/zipalign.exe" "$BT/lib/d8.jar" "$BT/lib/apksigner.jar" "$ANDROID_JAR"; do
    [ -f "$f" ] || { echo "missing $f" >&2; exit 1; }
done
[ -f "$ASSETS/guest/zImage" ] || { echo "missing $ASSETS/guest/zImage" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/assets" "$OUT/classes" "$OUT/dex"

echo "== staging assets"
cp -r "$ASSETS/rootfs" "$OUT/assets/rootfs"
cp -r "$ASSETS/guest" "$OUT/assets/guest"
du -sh "$OUT/assets"

echo "== javac"
find "$SRC" -name '*.java' -print | while read -r f; do w "$f"; done > "$OUT/sources.txt"
javac -source 8 -target 8 -Xlint:-options \
    -bootclasspath "$(w "$ANDROID_JAR")" \
    -d "$(w "$OUT/classes")" \
    @"$(w "$OUT/sources.txt")"

echo "== d8"
(cd "$OUT/classes" && jar cf "$(w "$OUT/classes.jar")" .)
java -cp "$(w "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
    --release --min-api 21 \
    --lib "$(w "$ANDROID_JAR")" \
    --output "$(w "$OUT/dex")" \
    "$(w "$OUT/classes.jar")"

echo "== aapt2 link"
"$BT/aapt2.exe" link \
    -o "$(w "$OUT/base.apk")" \
    -I "$(w "$ANDROID_JAR")" \
    --manifest "$(w "$ROOT/AndroidManifest.xml")" \
    --min-sdk-version 21 --target-sdk-version 28 \
    --version-code 1 --version-name 1.0

# aapt2 -A writes asset entry names with backslashes on Windows, which the
# device's AssetManager cannot resolve, so the tree is added with jar instead.
echo "== add classes.dex + assets"
(cd "$OUT/dex" && jar ufM "$(w "$OUT/base.apk")" classes.dex)
(cd "$OUT" && jar ufM "$(w "$OUT/base.apk")" assets)

echo "== zipalign"
"$BT/zipalign.exe" -f 4 "$(w "$OUT/base.apk")" "$(w "$OUT/base-aligned.apk")"

if [ ! -f "$KEYSTORE" ]; then
    echo "== generating debug keystore"
    keytool -genkeypair -keystore "$(w "$KEYSTORE")" \
        -storepass android -keypass android -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi

echo "== apksigner"
java -jar "$(w "$BT/lib/apksigner.jar")" sign \
    --ks "$(w "$KEYSTORE")" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --min-sdk-version 21 \
    --out "$(w "$OUT/a1-donut.apk")" "$(w "$OUT/base-aligned.apk")"

java -jar "$(w "$BT/lib/apksigner.jar")" verify --min-sdk-version 21 "$(w "$OUT/a1-donut.apk")"
ls -lh "$OUT/a1-donut.apk"
echo "OK: $OUT/a1-donut.apk"
