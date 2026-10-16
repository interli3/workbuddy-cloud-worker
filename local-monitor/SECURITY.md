# Security policy

## Sensitive local data

Never commit or upload any of the following:

- `credentials.json` or any backup of it
- WorkBuddy `*.info` authentication files
- access or refresh tokens
- signing keystores and their passwords
- runtime logs, database snapshots, exported sessions, or `config.local.json`

The repository `.gitignore` excludes these paths. Run the documented secret scan before every public push.

## LAN bridge authentication

The first-run configurator generates a separate random `bridgeToken`. Private bridge endpoints require it from non-loopback clients; local diagnostics, `/health`, and the APK download remain available without it. This token is not a WorkBuddy credential. A locally built APK receives it through the gitignored `LocalConfig.java`; users of the generic release APK enter it in App settings.

## Public MQTT broker

LAN HTTP is unencrypted: use only a trusted network. Do not expose the bridge through port forwarding or an unauthenticated reverse proxy. Loopback requests bypass token checks, so a local reverse proxy must enforce its own authentication.

MQTT is disabled by default. The optional broker is public and anonymous; enabling it makes `scripts/configure.py` generate a long random topic, but a random topic is not encryption. Anyone who learns the topic can read published snapshots. Keep LAN-only mode or use a private authenticated broker for stronger privacy.

## Reporting a vulnerability

Please open a GitHub security advisory rather than a public issue when the report may expose credentials or private data.
