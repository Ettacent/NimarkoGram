# Localized OTA release notes

The language is the application's language, not the SIM, IP address, country or
Android system language. Notes are selected when the update sheet is opened.
Selection: app language (regional/script fallbacks), custom pack base language,
pack plural language, app locale, English, legacy `changelog`.

API `/api/app-version?id=nimarkogram` retains `changelog` (string) and adds
`changelogs` (object of language tag → Markdown). Both are cached together with
the release, so reopening after a process restart or language change works.
An absent/malformed map must not prevent installing an old-format update.

Keep one source of translations in `docs/releases/<versionCode>.changelogs.json`.
Current translations: Russian (`ru`), English (`en`), simplified Chinese (`zh`).
Other languages can be added without client changes. No on-device or server-side
machine translation is performed. Use `zh-Hant` for a traditional Chinese
translation when available; `zh` is the generic Chinese fallback.

## Publishing

The local updater server's `/api/upload-apk` accepts the existing multipart
`changelog` and optional `changelogs` containing the JSON object. Supply English
in every translated release as the fallback. A legacy string-only upload remains
supported and resets translations (never inherits another build's notes).
The combined JSON notes are limited to 56 KiB to fit old clients' 64 KiB response
limit. Invalid translations are rejected before APK replacement.

The upload bot also accepts explicitly delimited sections in a single message:

```text
[ru]
### Исправления
- Описание исправления.
[en]
### Fixes
- Description of the fix.
[zh]
### 修复
- 修复说明。
```

Do not infer languages by country or parse arbitrary prose headings. Explicit
tags avoid mixing translations or accidentally dropping a section.

GitHub release bodies remain multilingual Markdown (GitHub has no app-language
selection). Generate the body from the same JSON translations, with language
headings or collapsible sections. Do not publish unreleased working-tree fixes
as notes for an existing APK. Changing OTA notes does not replace the APK,
change its hash/date/version, or publish a GitHub release.
