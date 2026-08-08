# Privacy Policy

MEGA’s privacy model is intentionally simple: there is no network channel for data to travel through. The app operates entirely offline.

## What We Do NOT Collect
- No `INTERNET` permission in the manifest
- No analytics, telemetry, or crash reporting
- No user accounts or server communication
- No cloud sync or advertising SDKs
- No export features (no clipboard, share, QR, screenshot, or file export of dice rolls or mnemonics)
- No collection of user data by the developer under any circumstance

## On-Device Storage
Anything you explicitly save stays encrypted (AES-256-GCM via Android Keystore) in this app's private on-device storage only. This data is excluded from Android backups (both legacy `android:allowBackup="false"` and modern `android:dataExtractionRules` mechanisms).

For full technical details on how data is stored and protected on-device, see [docs/STORAGE-DESIGN.md](docs/STORAGE-DESIGN.md).

## Supported Environments
MEGA is designed to work as a normal sandboxed app with no Google Play Services dependency. It fully supports GrapheneOS, where app-private data naturally disappears when its per-user profile is deleted via Android’s own sandboxing.
