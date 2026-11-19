#!/usr/bin/env bash
# 纯命令行构建：aapt2 → javac → d8 → zipalign → apksigner，不依赖 Gradle。
#
# 依赖：JDK 8+、Android SDK（build-tools + platforms）
# 环境变量：ANDROID_HOME 或 ANDROID_SDK_ROOT 指向 SDK 根目录
# 可选：KS_PATH / KS_PASS / KS_ALIAS 指定自己的签名密钥，默认在仓库根生成一个调试密钥
set -e

cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
if [ ! -d "$SDK" ]; then
    echo "错误：找不到 Android SDK，请设置 ANDROID_HOME 或 ANDROID_SDK_ROOT" >&2
    exit 1
fi

BT="$(find "$SDK/build-tools" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -1)"
PLATFORM="$(find "$SDK/platforms" -maxdepth 1 -mindepth 1 -type d -name 'android-*' | sort -V | tail -1)"
JAR="$PLATFORM/android.jar"
if [ -z "$BT" ] || [ ! -f "$JAR" ]; then
    echo "错误：SDK 里缺少 build-tools 或 platforms/android-*" >&2
    exit 1
fi

MIN_SDK=26
OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> aapt2 compile"
"$BT/aapt2" compile --dir res -o "$OUT/res/resources.zip"

echo "==> aapt2 link"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" \
    --manifest AndroidManifest.xml \
    --java "$OUT/gen" \
    --min-sdk-version "$MIN_SDK" \
    --auto-add-overlay \
    "$OUT/res/resources.zip"

echo "==> javac"
javac -source 8 -target 8 -bootclasspath "$JAR" -cp "$JAR" \
    -encoding UTF-8 -nowarn -d "$OUT/classes" \
    $(find src "$OUT/gen" -name '*.java')

echo "==> d8"
"$BT/d8" --release --min-api "$MIN_SDK" --lib "$JAR" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q -j unsigned.apk dex/classes.dex)

echo "==> zipalign"
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KS_PATH="${KS_PATH:-keystore.jks}"
KS_PASS="${KS_PASS:-suiji1234}"
KS_ALIAS="${KS_ALIAS:-suiji}"
if [ ! -f "$KS_PATH" ]; then
    echo "==> 生成本地调试签名密钥 $KS_PATH"
    keytool -genkeypair -keystore "$KS_PATH" -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=SuiJi, OU=App, O=SuiJi, L=Beijing, ST=Beijing, C=CN" > /dev/null 2>&1
fi

echo "==> sign"
"$BT/apksigner" sign --ks "$KS_PATH" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$OUT/suiji.apk" "$OUT/aligned.apk"

"$BT/apksigner" verify "$OUT/suiji.apk"
"$BT/aapt2" dump badging "$OUT/suiji.apk" | head -3
echo "==> OK: $OUT/suiji.apk ($(du -h "$OUT/suiji.apk" | cut -f1))"
