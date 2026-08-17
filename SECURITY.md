# Security policy

## Reporting a vulnerability

Report security vulnerabilities through a [private security advisory](https://github.com/Ettacent/NimarkoGram/security/advisories/new). Do not create a public issue for a vulnerability, leaked credential or private user data.

Include the affected version, impact, reproduction steps and any proposed mitigation. Remove unrelated personal information from logs and attachments.

## Sensitive project data

Telegram API credentials, signing keys, Firebase or Huawei service files, private service endpoints and production tokens must remain outside the repository. Use `private.properties` or environment variables for local builds.

Only the current source state is actively maintained. Reports against older builds may require reproduction on the latest version before they can be investigated.
