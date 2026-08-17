<p align="center">
  <img src="docs/assets/nimarkogram-icon.png" width="128" height="128" alt="NimarkoGram icon">
</p>

<h1 align="center">NimarkoGram</h1>

<p align="center">
  <strong>English</strong> · <a href="README.ru.md">Русский</a> · <a href="README.zh-CN.md">简体中文</a>
</p>

<p align="center">
  An open-source, unofficial Telegram client for Android with advanced customization, media tools and plugin support.
</p>

<p align="center">
  <img alt="NimarkoGram 12.9.2" src="https://img.shields.io/badge/NimarkoGram-12.9.2-ff4fa3">
  <a href="LICENSE"><img alt="GPL-2.0 license" src="https://img.shields.io/badge/license-GPL--2.0-6f42c1"></a>
  <img alt="Android 7.0 or newer" src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Telegram 12.9.2" src="https://img.shields.io/badge/Telegram-12.9.2-26A5E4?logo=telegram&logoColor=white">
</p>

<p align="center">
  <a href="#features">Features</a> ·
  <a href="#build-from-source">Build</a> ·
  <a href="#plugin-platform">Plugins</a> ·
  <a href="CONTRIBUTING.md">Contributing</a> ·
  <a href="https://github.com/Ettacent/NimarkoGram/issues">Issues</a>
</p>

> [!IMPORTANT]
> NimarkoGram is an independent project based on the official Telegram for Android source code. It is not affiliated with or endorsed by Telegram Messenger Inc.

## About

NimarkoGram extends [Telegram for Android](https://github.com/DrKLO/Telegram) while preserving the familiar Telegram experience. The project focuses on a flexible interface, improved camera and media workflows, privacy controls, network tools and a complete extensibility platform.

The plugin runtime is part of this repository. Python plugins, method hooks, dependency management, DEX modules and compatibility bridges are available as source code rather than distributed as a closed component.

## Features

- Extensive appearance controls, Monet colors, icon packs, tabs, chat headers and profile customization.
- Enhanced CameraX, video-message, media, story and clipboard workflows.
- Additional privacy, biometric, translation, filtering and chat-management tools.
- Built-in networking and call-transport options with configurable service endpoints.
- Python and DEX plugin support with hooks, dependency management, safe lifecycle handling and compatibility APIs.
- ARM64 and ARMv7 support in a single standalone APK.

## Build from source

### Requirements

| Component | Version |
| --- | --- |
| JDK | 17 |
| Android SDK / Build Tools | 36 / 36.0.0 |
| Android NDK | 26.3.11579264 |
| CMake | 3.22.1 |
| Python | 3.11 for the Chaquopy build environment |

Clone the repository and all native submodules:

```bash
git clone --recursive https://github.com/Ettacent/NimarkoGram.git
cd NimarkoGram
```

Create the local configuration:

```bash
cp private.properties.example private.properties
```

Add your own `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` from [my.telegram.org](https://my.telegram.org). Keep `private.properties`, signing keys and service configuration files private.

Build the standalone hybrid ARM APK:

```bash
./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone
```

The APK is written to:

```text
TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk
```

For a faster local ARM64-only build, add `-PngArm64Only`. See the [complete build guide](docs/BUILDING.md) for configuration and troubleshooting notes.

## Plugin platform

The plugin implementation lives in `TMessagesProj/src/main/java/app/nimarkogram/messenger/plugins` and `TMessagesProj/src/main/python`. It includes the Python runtime, hook bridge, package management, plugin UI models, DEX loading and lifecycle coordination.

Packages under `com.exteragram` and related Python aliases are interoperability shims for existing third-party plugins. They do not change the NimarkoGram application ID or branding.

## Telegram API

Every independent build must use its own Telegram API credentials. Read Telegram's documentation for [obtaining an API ID](https://core.telegram.org/api/obtaining_api_id), the [Telegram API](https://core.telegram.org/api) and [MTProto](https://core.telegram.org/mtproto).

## Project layout

| Path | Purpose |
| --- | --- |
| `TMessagesProj` | Telegram core, NimarkoGram features and Android resources |
| `TMessagesProj/src/main/python` | Python plugin API and runtime |
| `TMessagesProj_AppStandalone` | Standalone NimarkoGram application |
| `TMessagesProj/jni` | Native code and third-party native libraries |
| `third_party/pine` | Pinned Pine hook-engine source |
| `patches/pine-nimarkogram.patch` | Reproducible NimarkoGram changes for Pine |

## Contributing and security

Bug reports and focused pull requests are welcome in English, Russian or Chinese. Read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting a change. For sensitive reports, follow [SECURITY.md](SECURITY.md) and use a private security advisory instead of a public issue.

## Credits

- [Telegram for Android](https://github.com/DrKLO/Telegram), the upstream client.
- [Cherrygram](https://github.com/arsLan4k1390/Cherrygram), for selected open-source interface components retained with source attribution.
- [Pine](https://github.com/canyie/pine), used by the hook runtime.
- The maintainers of the open-source libraries whose licenses and notices are retained in this repository.

## License

NimarkoGram is distributed under the [GNU General Public License v2.0](LICENSE). Telegram and bundled third-party components retain their respective copyright and license notices.
