<p align="center">
  <img src="docs/assets/nimarkogram-icon.png" width="128" height="128" alt="Иконка NimarkoGram">
</p>

<h1 align="center">NimarkoGram</h1>

<p align="center">
  <a href="README.md">English</a> · <strong>Русский</strong> · <a href="README.zh-CN.md">简体中文</a>
</p>

<p align="center">
  Открытый неофициальный клиент Telegram для Android с расширенной настройкой, медиавозможностями и поддержкой плагинов.
</p>

<p align="center">
  <img alt="NimarkoGram 12.9.2" src="https://img.shields.io/badge/NimarkoGram-12.9.2-ff4fa3">
  <a href="LICENSE"><img alt="Лицензия GPL-2.0" src="https://img.shields.io/badge/license-GPL--2.0-6f42c1"></a>
  <img alt="Android 7.0 и новее" src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Telegram 12.9.2" src="https://img.shields.io/badge/Telegram-12.9.2-26A5E4?logo=telegram&logoColor=white">
</p>

<p align="center">
  <a href="#возможности">Возможности</a> ·
  <a href="#сборка-из-исходного-кода">Сборка</a> ·
  <a href="#платформа-плагинов">Плагины</a> ·
  <a href="CONTRIBUTING.md">Участие</a> ·
  <a href="https://github.com/Ettacent/NimarkoGram/issues">Ошибки</a>
</p>

> [!IMPORTANT]
> NimarkoGram — независимый проект на основе официального исходного кода Telegram для Android. Проект не связан с Telegram Messenger Inc. и не одобрен этой компанией.

## О проекте

NimarkoGram расширяет возможности [Telegram для Android](https://github.com/DrKLO/Telegram), сохраняя привычную логику официального клиента. Основные направления проекта — гибкая настройка интерфейса, улучшенная работа камеры и медиа, инструменты приватности и сети, а также полноценная платформа расширений.

Среда выполнения плагинов целиком находится в этом репозитории. Python-плагины, перехваты методов, управление зависимостями, DEX-модули и мосты совместимости доступны в исходном коде и не вынесены в закрытый компонент.

## Возможности

- Гибкая настройка внешнего вида, цвета Monet, наборы иконок, вкладки, заголовки чатов и профили.
- Улучшенные сценарии CameraX, видеосообщений, медиа, историй и буфера обмена.
- Дополнительные инструменты приватности, биометрии, перевода, фильтрации и управления чатами.
- Встроенные сетевые инструменты и параметры транспорта звонков с настраиваемыми адресами сервисов.
- Поддержка Python- и DEX-плагинов, перехватов, зависимостей, безопасного жизненного цикла и API совместимости.
- Единый standalone APK для ARM64 и ARMv7.

## Сборка из исходного кода

### Требования

| Компонент | Версия |
| --- | --- |
| JDK | 17 |
| Android SDK / Build Tools | 36 / 36.0.0 |
| Android NDK | 26.3.11579264 |
| CMake | 3.22.1 |
| Python | 3.11 для среды сборки Chaquopy |

Клонируйте репозиторий вместе с нативными подмодулями:

```bash
git clone --recursive https://github.com/Ettacent/NimarkoGram.git
cd NimarkoGram
```

Создайте локальную конфигурацию:

```bash
cp private.properties.example private.properties
```

Укажите собственные `TELEGRAM_API_ID` и `TELEGRAM_API_HASH`, полученные на [my.telegram.org](https://my.telegram.org). Не публикуйте `private.properties`, ключи подписи и конфигурационные файлы сервисов.

Соберите standalone APK для двух ARM-архитектур:

```bash
./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone
```

Готовый APK будет находиться по адресу:

```text
TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk
```

Для ускоренной локальной сборки только под ARM64 добавьте `-PngArm64Only`. Дополнительные сведения и решения типичных проблем находятся в [полной инструкции по сборке](docs/BUILDING.md).

## Платформа плагинов

Реализация плагинов расположена в `TMessagesProj/src/main/java/app/nimarkogram/messenger/plugins` и `TMessagesProj/src/main/python`. В неё входят среда Python, мост перехватов, управление пакетами, UI-модели плагинов, загрузка DEX и координация жизненного цикла.

Пакеты в пространстве имён `com.exteragram` и связанные Python-псевдонимы являются мостами совместимости со сторонними плагинами. Они не меняют идентификатор приложения или бренд NimarkoGram.

## Telegram API

Каждая независимая сборка должна использовать собственные учётные данные Telegram API. Ознакомьтесь с инструкцией по [получению API ID](https://core.telegram.org/api/obtaining_api_id), документацией [Telegram API](https://core.telegram.org/api) и [MTProto](https://core.telegram.org/mtproto).

## Структура проекта

| Путь | Назначение |
| --- | --- |
| `TMessagesProj` | Ядро Telegram, функции NimarkoGram и ресурсы Android |
| `TMessagesProj/src/main/python` | Python API и среда выполнения плагинов |
| `TMessagesProj_AppStandalone` | Standalone-приложение NimarkoGram |
| `TMessagesProj/jni` | Нативный код и сторонние нативные библиотеки |
| `third_party/pine` | Зафиксированный исходный код движка перехватов Pine |
| `patches/pine-nimarkogram.patch` | Воспроизводимые изменения Pine для NimarkoGram |

## Участие и безопасность

Сообщения об ошибках и целевые pull request принимаются на русском, английском и китайском языках. Перед отправкой изменений прочитайте [CONTRIBUTING.md](CONTRIBUTING.md). Уязвимости следует передавать по инструкции из [SECURITY.md](SECURITY.md) через приватный security advisory, а не через публичный issue.

## Благодарности

- [Telegram для Android](https://github.com/DrKLO/Telegram) — исходный клиент.
- [Cherrygram](https://github.com/arsLan4k1390/Cherrygram) — отдельные открытые компоненты интерфейса, сохранённые с указанием происхождения в коде.
- [Pine](https://github.com/canyie/pine) — движок, используемый средой перехватов.
- Авторы открытых библиотек, чьи лицензии и уведомления сохранены в репозитории.

## Лицензия

NimarkoGram распространяется по лицензии [GNU General Public License v2.0](LICENSE). Telegram и встроенные сторонние компоненты сохраняют собственные авторские права и условия лицензий.
