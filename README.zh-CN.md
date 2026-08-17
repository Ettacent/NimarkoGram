<p align="center">
  <img src="docs/assets/nimarkogram-icon.png" width="128" height="128" alt="NimarkoGram 图标">
</p>

<h1 align="center">NimarkoGram</h1>

<p align="center">
  <a href="README.md">English</a> · <a href="README.ru.md">Русский</a> · <strong>简体中文</strong>
</p>

<p align="center">
  面向 Android 的开源非官方 Telegram 客户端，提供深度定制、媒体工具与插件支持。
</p>

<p align="center">
  <img alt="NimarkoGram 12.9.2" src="https://img.shields.io/badge/NimarkoGram-12.9.2-ff4fa3">
  <a href="LICENSE"><img alt="GPL-2.0 许可证" src="https://img.shields.io/badge/license-GPL--2.0-6f42c1"></a>
  <img alt="Android 7.0 及更高版本" src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Telegram 12.9.2" src="https://img.shields.io/badge/Telegram-12.9.2-26A5E4?logo=telegram&logoColor=white">
</p>

<p align="center">
  <a href="#功能">功能</a> ·
  <a href="#从源码构建">构建</a> ·
  <a href="#插件平台">插件</a> ·
  <a href="CONTRIBUTING.md">参与贡献</a> ·
  <a href="https://github.com/Ettacent/NimarkoGram/issues">问题反馈</a>
</p>

> [!IMPORTANT]
> NimarkoGram 是基于官方 Telegram Android 源码的独立项目，与 Telegram Messenger Inc. 不存在隶属关系，也未获得其官方认可。

## 项目简介

NimarkoGram 在保留官方客户端使用体验的基础上扩展了 [Telegram for Android](https://github.com/DrKLO/Telegram)。项目重点包括灵活的界面定制、更完善的相机与媒体流程、隐私与网络工具，以及完整的扩展平台。

插件运行时的全部实现均包含在本仓库中。Python 插件、方法钩子、依赖管理、DEX 模块和兼容桥接均以源代码形式公开，而不是作为闭源组件分发。

## 功能

- 丰富的外观设置，包括 Monet 动态配色、图标包、标签页、聊天标题和个人资料定制。
- 改进的 CameraX、视频消息、媒体、故事和剪贴板工作流。
- 额外的隐私、生物识别、翻译、过滤和聊天管理工具。
- 内置网络工具与通话传输选项，并支持配置服务地址。
- 支持 Python 与 DEX 插件、方法钩子、依赖管理、安全生命周期和兼容 API。
- 单个 standalone APK 同时支持 ARM64 与 ARMv7。

## 从源码构建

### 环境要求

| 组件 | 版本 |
| --- | --- |
| JDK | 17 |
| Android SDK / Build Tools | 36 / 36.0.0 |
| Android NDK | 26.3.11579264 |
| CMake | 3.22.1 |
| Python | 3.11，用于 Chaquopy 构建环境 |

克隆仓库及所有原生子模块：

```bash
git clone --recursive https://github.com/Ettacent/NimarkoGram.git
cd NimarkoGram
```

创建本地配置文件：

```bash
cp private.properties.example private.properties
```

填写从 [my.telegram.org](https://my.telegram.org) 获取的 `TELEGRAM_API_ID` 和 `TELEGRAM_API_HASH`。请勿公开 `private.properties`、签名密钥或服务配置文件。

构建同时支持两种 ARM 架构的 standalone APK：

```bash
./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone
```

APK 输出位置：

```text
TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk
```

如需更快的本地 ARM64 单架构构建，请添加 `-PngArm64Only`。更多配置与故障排除信息请参阅[完整构建指南](docs/BUILDING.md)。

## 插件平台

插件实现位于 `TMessagesProj/src/main/java/app/nimarkogram/messenger/plugins` 和 `TMessagesProj/src/main/python`。其中包含 Python 运行时、钩子桥接、包管理、插件 UI 模型、DEX 加载以及生命周期协调。

`com.exteragram` 命名空间下的包和相关 Python 别名仅用于兼容现有第三方插件，不会改变 NimarkoGram 的应用 ID 或品牌。

## Telegram API

每个独立构建都必须使用自己的 Telegram API 凭据。请阅读[获取 API ID](https://core.telegram.org/api/obtaining_api_id)、[Telegram API](https://core.telegram.org/api) 与 [MTProto](https://core.telegram.org/mtproto) 文档。

## 项目结构

| 路径 | 用途 |
| --- | --- |
| `TMessagesProj` | Telegram 核心、NimarkoGram 功能和 Android 资源 |
| `TMessagesProj/src/main/python` | Python 插件 API 与运行时 |
| `TMessagesProj_AppStandalone` | NimarkoGram standalone 应用 |
| `TMessagesProj/jni` | 原生代码与第三方原生库 |
| `third_party/pine` | 固定版本的 Pine 钩子引擎源码 |
| `patches/pine-nimarkogram.patch` | 可复现的 NimarkoGram Pine 修改 |

## 贡献与安全

欢迎使用中文、英语或俄语提交错误报告和范围明确的 pull request。提交更改前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。安全漏洞请按照 [SECURITY.md](SECURITY.md) 通过私有 security advisory 报告，不要创建公开 issue。

## 致谢

- [Telegram for Android](https://github.com/DrKLO/Telegram)，本项目的上游客户端。
- [Cherrygram](https://github.com/arsLan4k1390/Cherrygram)，部分保留源码归属说明的开源界面组件来源。
- [Pine](https://github.com/canyie/pine)，钩子运行时所使用的引擎。
- 本仓库中保留了许可证和版权声明的所有开源库维护者。

## 许可证

NimarkoGram 采用 [GNU General Public License v2.0](LICENSE) 发布。Telegram 与随附的第三方组件保留各自的版权和许可证声明。
