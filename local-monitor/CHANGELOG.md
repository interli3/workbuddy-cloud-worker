# Changelog

## 3.8.0 - 2026-10-04

- Add a **DeepSeek balance** section as a fourth tab. The phone calls the official DeepSeek balance endpoint directly, so this part keeps working even when the desktop is off.
- Add a DeepSeek line to the **bottom of the existing desktop widget** — balance plus today's spend, in a distinct blue-violet so it never reads as WorkBuddy credit.
- Keep the two ledgers strictly separate: own preferences file, own sampling history, own alarm chain, own notification channel.
- Show a DeepSeek summary card on the overview page so both accounts are visible at a glance.
- DeepSeek spend is derived from balance deltas between samples (the official API exposes no usage endpoint); samples shorter than 6 hours are reported as "not enough data" instead of extrapolating a fake runway.
- Compact the widget layout so the account rows that were previously clipped by the 4×2 cell are visible again.

## 3.7.0 - 2026-09-28

- Replace the light glass UI with a **dark glass** theme: deep base, translucent cards, neon cyan-blue accents.
- Skin-only change — the layout structure and information architecture are unchanged.
- Fix the settings Spinner collapsing to zero height under a dark theme by shipping a private item layout instead of relying on the system one.

## 3.6.2 - 2026-09-20

- Isolate WorkBuddy/WorkDaddy envelope-wrapped credentials instead of crashing the bridge snapshot service.
- Keep `/health` available and return a partial, explicit account error when one credential cannot be used.
- Clarify the difference between the personal configured APK and the unconfigured public generic APK.

## 3.6.1 - 2026-09-19

- Validate every cached login candidate against the official resource endpoint before replacing an account token.
- Keep multi-account scheduled check-in synchronized with current and historical WorkBuddy login snapshots.
- Treat HTTP 400 / business code 10001 as the official idempotent “already checked in” result.
- Add regression tests for revoked newer snapshots, invalid candidates and check-in business responses.

## 3.6.0 - 2026-09-16

- Dynamic health cards for all current and future accounts.
- Fresh light glass Android UI with overview, accounts and records sections.
- Recent deductions on the overview and complete history in records.
- Corrected cumulative-credit and daily-usage calculations.
- Idempotent multi-account daily check-in with Windows scheduling helpers.
- LAN HTTP and optional MQTT snapshot delivery.
- JWT expiry fallback when legacy credential entries lack `expiresAt`.
- Credential expiry affects health only after the authorization is actually invalid.
- Privacy-safe first-run configuration and local signing material generation.
