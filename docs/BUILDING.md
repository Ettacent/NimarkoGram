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

Optional blank values are supported for integrations which are not required by a local development build. Values are read from Gradle properties (`-P`), environment variables, then `private.properties`, in that order.

For a signed standalone APK, configure all four signing properties together:

```properties
RELEASE_STORE_FILE=/absolute/path/to/your-release.jks
RELEASE_STORE_PASSWORD=your_store_password
RELEASE_KEY_ALIAS=your_key_alias
RELEASE_KEY_PASSWORD=your_key_password
```

With no signing configuration, the standalone APK is unsigned and must be signed before installation. Debug variants use the standard Android debug key. A self-signed build cannot replace an official NimarkoGram installation without the same signing certificate.

Firebase is optional for local builds. To enable it, provide your own matching `TMessagesProj_AppStandalone/google-services.json` and set `NIMARKO_FIREBASE_ENABLED=true`. Without Firebase configuration, Firebase-dependent features such as FCM push delivery and Crashlytics reporting are unavailable. Uploading Crashlytics symbols is separately opt-in with `NIMARKO_CRASHLYTICS_UPLOAD=true` and requires access to your Firebase project. Do not publish service configuration or signing material.

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

The repository pins Pine at `216d910f18b18430a5d21c510affb221a9833a55` as the `third_party/pine` submodule. The normal app build uses `TMessagesProj/libs/pine-core-16kb.jar` and the matching ARM binaries in `TMessagesProj/jni`; it does not rebuild Pine automatically.

To prepare the source for rebuilding Pine, apply both patches in order to a clean submodule checkout:

```bash
git -C third_party/pine apply --check ../../patches/pine-nimarkogram.patch
git -C third_party/pine apply ../../patches/pine-nimarkogram.patch
git -C third_party/pine apply --check ../../patches/pine-callback-snapshot.patch
git -C third_party/pine apply ../../patches/pine-callback-snapshot.patch
```

## Troubleshooting

- Confirm that all submodules are initialized before diagnosing native-linker failures.
- Confirm that Gradle runs on JDK 17 rather than a system-default JDK.
- Install the exact NDK and CMake versions declared above when CMake configuration fails.
- Remove only generated module build directories when a stale local build cache causes inconsistent output; do not delete source or local signing files.
