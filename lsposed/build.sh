#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
tools=${ANDROID_BUILD_TOOLS:-$sdk/build-tools/37.0.0}
platform=${ANDROID_PLATFORM_JAR:-$sdk/platforms/android-37.0/android.jar}
api_cache=${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/io.github.libxposed/api/102.0.0
libxposed_cache=$(dirname "$(dirname "$api_cache")")
if [ -z "${LIBXPOSED_AAR:-}" ] && [ -d "$api_cache" ]; then
  LIBXPOSED_AAR=$(find "$api_cache" -name api-102.0.0.aar -print -quit)
fi

[ -f "${LIBXPOSED_AAR:-}" ] || {
  printf '%s\n' 'Set LIBXPOSED_AAR to io.github.libxposed:api:102.0.0 (AAR).' >&2
  exit 1
}
[ -f "$platform" ] || {
  printf '%s\n' 'Android 37 platform not found; set ANDROID_PLATFORM_JAR.' >&2
  exit 1
}
if [ -z "${LIBXPOSED_SERVICE_AAR:-}" ] && [ -d "$libxposed_cache/service/102.0.0" ]; then
  LIBXPOSED_SERVICE_AAR=$(find "$libxposed_cache/service/102.0.0" -name '*.aar' -print -quit)
fi
if [ -z "${LIBXPOSED_INTERFACE_AAR:-}" ] && [ -d "$libxposed_cache/interface/102.0.0" ]; then
  LIBXPOSED_INTERFACE_AAR=$(find "$libxposed_cache/interface/102.0.0" -name '*.aar' -print -quit)
fi
[ -f "${LIBXPOSED_SERVICE_AAR:-}" ] && [ -f "${LIBXPOSED_INTERFACE_AAR:-}" ] || {
  printf '%s\n' 'Set LIBXPOSED_SERVICE_AAR and LIBXPOSED_INTERFACE_AAR to libxposed 102.0.0 AARs.' >&2
  exit 1
}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
trap 'exit 1' HUP INT TERM
mkdir -p "$work/classes" "$work/dex" ../dist

unzip -p "$LIBXPOSED_AAR" classes.jar > "$work/api.jar"
unzip -p "$LIBXPOSED_SERVICE_AAR" classes.jar > "$work/service.jar"
unzip -p "$LIBXPOSED_INTERFACE_AAR" classes.jar > "$work/interface.jar"
"$tools/aapt2" compile --dir res -o "$work/res.zip"
"$tools/aapt2" link -I "$platform" --manifest AndroidManifest.xml --java "$work/generated" \
  -o "$work/unsigned.apk" "$work/res.zip"
javac --release 17 -encoding UTF-8 -cp "$platform:$work/api.jar:$work/service.jar:$work/interface.jar" \
  -d "$work/classes" "$work"/generated/io/github/justdreemurr/encore/R.java \
  src/io/github/justdreemurr/encore/*.java
jar cf "$work/module.jar" -C "$work/classes" .
"$tools/d8" --min-api 31 --lib "$platform" --classpath "$work/api.jar" \
  --output "$work/dex" "$work/module.jar" "$work/service.jar" "$work/interface.jar"
(cd "$work/dex" && zip -q -0 "$work/unsigned.apk" classes.dex)
(cd metadata && zip -q -0 -r "$work/unsigned.apk" META-INF/xposed)
"$tools/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"

keystore=../dist/x4-le-route-debug.p12
if [ ! -f "$keystore" ]; then
  keytool -genkeypair -keystore "$keystore" -storetype PKCS12 -alias x4route \
    -storepass android -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Encore'
fi
output=../dist/encore.apk
"$tools/apksigner" sign --ks "$keystore" --ks-key-alias x4route --ks-pass pass:android \
  --out "$output" "$work/aligned.apk"
"$tools/apksigner" verify "$output"
printf '%s\n' 'dist/encore.apk'
