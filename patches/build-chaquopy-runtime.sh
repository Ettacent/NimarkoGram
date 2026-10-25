#!/usr/bin/env bash
# Modifications Copyright (C) 2026 Ettacent
set -euo pipefail

runtime_repo=$(cd -- "$(dirname -- "$0")/.." && pwd)
runtime_manifest="$runtime_repo/TMessagesProj/libs/chaquopy-runtime/manifest.json"
runtime_sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
runtime_python=${CHAQUOPY_BUILD_PYTHON:-python3.11}
runtime_output=${1:?Usage: build-chaquopy-runtime.sh NEW_OUTPUT_DIRECTORY}
test -n "$runtime_sdk"
test ! -e "$runtime_output"
mkdir -p -- "$runtime_output"
runtime_output=$(cd -- "$runtime_output" && pwd)
runtime_work=$(mktemp -d "$runtime_output/build-XXXXXXXX")

runtime_value() {
    "$runtime_python" -c 'import json,sys; value=json.load(open(sys.argv[1]));
for key in sys.argv[2:]: value=value[key]
print(value)' "$runtime_manifest" "$@"
}

test "$("$runtime_python" -c 'import sys; print("%d.%d" % sys.version_info[:2])')" = "$(runtime_value python)"
runtime_patch="$runtime_repo/$(runtime_value patch)"
test "$(sha256sum "$runtime_patch" | cut -d ' ' -f1)" = "$(runtime_value patchSha256)"
git clone --quiet --depth 1 --branch "$(runtime_value version)" "$(runtime_value upstream)" "$runtime_work/source"
test "$(git -C "$runtime_work/source" rev-parse HEAD)" = "$(runtime_value revision)"
git -C "$runtime_work/source" apply --check "$runtime_patch"
git -C "$runtime_work/source" apply "$runtime_patch"
"$runtime_python" -m venv "$runtime_work/venv"
"$runtime_work/venv/bin/pip" install --disable-pip-version-check "Cython==$(runtime_value cython)"

runtime_source="$runtime_work/source/product/runtime"
runtime_generated="$runtime_work/generated"
mkdir "$runtime_generated"
for runtime_module in chaquopy chaquopy_java; do
    runtime_module_dir="$runtime_source/src/main/python"
    if [ "$runtime_module" = chaquopy ]; then runtime_module_dir="$runtime_module_dir/java"; fi
    (
        cd "$runtime_module_dir"
        "$runtime_work/venv/bin/cython" -Wextra -Werror "$runtime_module.pyx" -I "$runtime_generated" -o "$runtime_generated/$runtime_module.c"
    )
    sed -i -E 's/^__PYX_EXTERN_C ([[:alnum:]_]+)/__PYX_EXTERN_C JNIEXPORT \1 JNICALL/; s/__pyx_insert_code_object\(c_line/\/\/ disabled upstream workaround: __pyx_insert_code_object(c_line/g' "$runtime_generated/$runtime_module.c"
done

runtime_target=$(runtime_value pythonTarget)
runtime_ndk="$runtime_sdk/ndk/$(runtime_value ndk)"
test -f "$runtime_ndk/build/cmake/android.toolchain.cmake"
for runtime_abi in armeabi-v7a arm64-v8a x86 x86_64; do
    runtime_dir="$runtime_work/$runtime_abi"
    mkdir "$runtime_dir"
    runtime_archive="$runtime_dir/target.zip"
    curl -fsSL --retry 3 --max-time 180 "https://repo.maven.apache.org/maven2/com/chaquo/python/target/$runtime_target/target-$runtime_target-$runtime_abi.zip" -o "$runtime_archive"
    test "$(sha256sum "$runtime_archive" | cut -d ' ' -f1)" = "$(runtime_value artifacts "$runtime_abi" targetSha256)"
    unzip -q "$runtime_archive" 'include/*' "jniLibs/$runtime_abi/*" -d "$runtime_dir/target"
    cmake -S "$runtime_source" -B "$runtime_dir/cmake" -GNinja \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE="$runtime_ndk/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$runtime_abi" -DANDROID_PLATFORM="android-$(runtime_value minSdk)" \
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
        -DCMAKE_C_FLAGS="-ffile-prefix-map=$runtime_generated/=generated/" \
        -DCMAKE_SHARED_LINKER_FLAGS="$(runtime_value linkerFlags)" \
        -DCHAQUOPY_PYTHON_VERSIONS="$(runtime_value python)" \
        -DCHAQUOPY_LIB_DIRS="$runtime_dir/target/jniLibs/$runtime_abi" \
        -DCHAQUOPY_INCLUDE_PYTHON="$runtime_dir/target/include" \
        -DCHAQUOPY_SRC_DIR="$runtime_generated" -DCHAQUOPY_PY_SUFFIX=.so
    cmake --build "$runtime_dir/cmake" --target "$(runtime_value cmakeTarget)" --parallel 4
    runtime_binary="$runtime_dir/cmake/$(runtime_value cmakeTarget).so"
    test "$(sha256sum "$runtime_binary" | cut -d ' ' -f1)" = "$(runtime_value artifacts "$runtime_abi" sha256)"
    cp "$runtime_binary" "$runtime_output/chaquopy-$(runtime_value version)-$(runtime_value python)-$runtime_abi.so"
done
