# Building NimarkoGram

This guide builds the standalone NimarkoGram APK from a clean checkout.

## Toolchain

- JDK 17
- Android SDK 36
- Android Build Tools 36.0.0
- Android NDK 26.3.11579264
- CMake 3.22.1
- Python 3.11 for Chaquopy build tasks

Android Studio may install the Android SDK, NDK and CMake components. The repository includes the Gradle wrapper, so a separate Gradle installation is not required.

## Checkout

Clone with submodules:

```bash
git clone --recursive https://github.com/Ettacent/NimarkoGram.git
cd NimarkoGram
```

If the repository was cloned without `--recursive`, initialize the submodules separately:

```bash
git submodule update --init --recursive
```

## Local configuration

Copy the public template:

```bash
cp private.properties.example private.properties
```

At minimum, set these values:

```properties
TELEGRAM_API_ID=123456
TELEGRAM_API_HASH=your_api_hash
```

Obtain credentials from [my.telegram.org](https://my.telegram.org). Do not commit `private.properties`, service configuration files or signing material.

Optional blank values are supported for integrations which are not required by a local development build. Release signing can be configured through `private.properties` or equivalent environment variables.

## Build variants

Build the standalone APK containing both `arm64-v8a` and `armeabi-v7a`:

```bash
./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone
```

Build ARM64 only for faster local iteration:

```bash
./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone -PngArm64Only
```

The default output is:

```text
TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk
```

## Native hook runtime

The repository pins Pine as a Git submodule. NimarkoGram-specific changes are stored in `patches/pine-nimarkogram.patch`, while the matching ARM binaries used by the application are tracked in `TMessagesProj/jni`.

To inspect the patch against a clean Pine checkout:

```bash
git -C third_party/pine apply --check ../../patches/pine-nimarkogram.patch
```

## Troubleshooting

- Confirm that all submodules are initialized before diagnosing native-linker failures.
- Confirm that Gradle runs on JDK 17 rather than a system-default JDK.
- Install the exact NDK and CMake versions declared above when CMake configuration fails.
- Remove only generated module build directories when a stale local build cache causes inconsistent output; do not delete source or local signing files.
