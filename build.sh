#!/usr/bin/env bash
# 纯命令行构建（aapt2 + javac + d8 + apksigner），不依赖 Gradle
set -e

SDK=/root/android-sdk
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
OUT=build

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> aapt2 compile"
$BT/aapt2 compile --dir res -o "$OUT/res/resources.zip"

echo "==> aapt2 link"
$BT/aapt2 link -o "$OUT/base.apk" -I "$JAR" \
    --manifest AndroidManifest.xml \
    --java "$OUT/gen" \
    --min-sdk-version 26 --target-sdk-version 34 \
    --version-code 24 --version-name 3.8 \
    --auto-add-overlay \
    "$OUT/res/resources.zip"

echo "==> javac"
javac -source 8 -target 8 -bootclasspath "$JAR" -cp "$JAR" \
    -encoding UTF-8 -nowarn -d "$OUT/classes" \
    $(find src "$OUT/gen" -name '*.java')

echo "==> d8"
$BT/d8 --release --min-api 26 --lib "$JAR" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q -j unsigned.apk dex/classes.dex)

echo "==> zipalign"
$BT/zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f keystore.jks ]; then
    keytool -genkeypair -keystore keystore.jks -storepass suiji1234 -keypass suiji1234 \
        -alias suiji -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Suiji, OU=App, O=Suiji, L=Beijing, ST=Beijing, C=CN" > /dev/null 2>&1
fi

echo "==> sign"
$BT/apksigner sign --ks keystore.jks --ks-pass pass:suiji1234 --key-pass pass:suiji1234 \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$OUT/suiji.apk" "$OUT/aligned.apk"

$BT/apksigner verify "$OUT/suiji.apk"
$BT/aapt2 dump badging "$OUT/suiji.apk" | head -3
echo "==> OK: $OUT/suiji.apk ($(du -h "$OUT/suiji.apk" | cut -f1))"
