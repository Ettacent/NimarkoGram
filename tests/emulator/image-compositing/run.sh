#!/usr/bin/env bash
set -euo pipefail
# Standalone graphics reference only. No client or production source is compiled.
harness_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
sdk_dir=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${HOME}/android-sdk}}
serial=${1:?Usage: bash tests/emulator/image-compositing/run.sh emulator-5582}
adb="$sdk_dir/platform-tools/adb"
tool_dir="$sdk_dir/build-tools/${BUILD_TOOLS_VERSION:-35.0.0}"
android_jar="$sdk_dir/platforms/android-${COMPILE_API:-36}/android.jar"
package=dev.nimarko.imagecompositing
test "$("$adb" -s "$serial" get-state)" = device
test "$("$adb" -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" = 1
device_api=$("$adb" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')
if (( device_api < 31 )); then echo 'Requires API 31+' >&2; exit 2; fi
build_dir=$(mktemp -d "${TMPDIR:-/tmp}/ng-image-compositing.XXXXXX")
printf 'Artifacts: %s\n' "$build_dir"
mkdir -p "$build_dir/classes" "$build_dir/dex"
mapfile -t test_sources < <(find "$harness_dir/src" -name '*.java' -print | sort)
sha256sum "${test_sources[@]}" "$harness_dir/run.sh" "$harness_dir/AndroidManifest.xml" > "$build_dir/source.sha256"
"$adb" -s "$serial" shell getprop > "$build_dir/device-properties.txt"
"$adb" -s "$serial" shell dumpsys SurfaceFlinger > "$build_dir/surfaceflinger.txt"
javac -source 11 -target 11 -cp "$android_jar" -d "$build_dir/classes" "${test_sources[@]}"
sha256sum --check "$build_dir/source.sha256"
jar cf "$build_dir/classes.jar" -C "$build_dir/classes" .
"$tool_dir/d8" --min-api 31 --lib "$android_jar" --output "$build_dir/dex" "$build_dir/classes.jar"
"$tool_dir/aapt" package -f -M "$harness_dir/AndroidManifest.xml" -I "$android_jar" -F "$build_dir/unsigned.apk"
(cd "$build_dir/dex" && zip -q "$build_dir/unsigned.apk" classes.dex)
"$tool_dir/zipalign" -f 4 "$build_dir/unsigned.apk" "$build_dir/aligned.apk"
key_dir=${IMAGE_COMPOSITING_KEY_DIR:-${XDG_CACHE_HOME:-/tmp}/ng-image-compositing-key}
mkdir -p "$key_dir"
if [[ ! -f "$key_dir/debug.jks" ]]; then
  keytool -genkeypair -keystore "$key_dir/debug.jks" -storepass android -keypass android \
    -alias debug -keyalg RSA -validity 3650 -dname CN=ImageCompositingReference
fi
"$tool_dir/apksigner" sign --ks "$key_dir/debug.jks" --ks-pass pass:android --out "$build_dir/check.apk" "$build_dir/aligned.apk"
"$adb" -s "$serial" install -r "$build_dir/check.apk"
"$adb" -s "$serial" shell am force-stop "$package"
token="run_$(date +%s)_${RANDOM}"
"$adb" -s "$serial" shell am start -n "$package/.CheckActivity" --es run "$token"
deadline=$((SECONDS + ${TEST_TIMEOUT_SECONDS:-600}))
while (( SECONDS < deadline )); do
  if "$adb" -s "$serial" exec-out run-as "$package" cat "files/$token/report.txt" > "$build_dir/report.txt" 2>/dev/null; then
    if grep -q '^RESULT ' "$build_dir/report.txt"; then break; fi
  fi
  sleep 2
done
"$adb" -s "$serial" exec-out run-as "$package" tar -C files -cf - "$token" > "$build_dir/device-artifacts.tar"
tar -xf "$build_dir/device-artifacts.tar" -C "$build_dir"
printf 'Report: %s\n' "$build_dir/report.txt"
grep -E '^(BACKEND|DIRECT_ADD|CONTROL|SUMMARY|RESULT)' "$build_dir/report.txt" || true
if ! grep -qx 'RESULT PASS' "$build_dir/report.txt"; then
  echo 'Failed or timed out; retained artifacts include report/PNGs.' >&2
  exit 1
fi
