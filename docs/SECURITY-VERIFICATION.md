# Security Verification

Security Verification is a truthful preflight for sensitive MEGA operations. It is not a claim that an Android phone is air-gapped or equivalent to a dedicated offline GrapheneOS device.

## Authoritative app checks

The final merged manifest and release artifact must contain no `android.permission.INTERNET`. The Gradle `securityAudit` task checks the source manifest, merged debug/release manifests, and the built release APK. It also checks that backup is disabled and that runtime dependencies do not match prohibited networking, telemetry, advertising, crash-reporting, or cloud SDK patterns.

MEGA requests only `CAMERA`, optionally used for local QR scanning. It does not request microphone, contacts, location, nearby-device, or unnecessary storage/media permissions. Sensitive screens use `FLAG_SECURE` unless the user has explicitly enabled screenshots in the existing security settings. Seed copying is disabled by default.

These are build and application properties, not proof that the operating system or firmware is trustworthy.

## Device checks

Wi-Fi, mobile data, Bluetooth, NFC, location services, airplane mode, active network state, ADB, and developer-options state are read through ordinary documented APIs where available. A warning means the state appears enabled. `Unable to verify automatically` is shown when Android denies access or does not expose a reliable value. Airplane Mode is never treated as sufficient by itself.

The active-network check is separate from MEGA network capability: a phone may have connectivity while MEGA still cannot open ordinary Internet sockets because its APK has no INTERNET permission.

## Isolation profiles

The selected environment is user-supplied and not automatically verified:

- A dedicated offline GrapheneOS device is the preferred configuration.
- Samsung Secure Folder / Knox can provide useful separation, but is not a dedicated air-gapped signer.
- Android Private Space or another isolated profile can reduce casual cross-app exposure, but is not an air gap.
- Ordinary Android still provides application sandboxing and benefits from MEGA's missing INTERNET permission, but offers less separation.

MEGA does not use hidden APIs, root, accessibility, device-owner privileges, or invasive permissions to identify OEM containers.

## Limits

The screen cannot verify firmware integrity, baseband behavior, malicious keyboards, compromise of Android, physical observation, radio emissions, or whether a user-selected isolation profile is honest. For the strongest workflow, use a dedicated device, verify the APK signer and hash, disable radios physically or in system settings, and independently inspect the transaction or seed backup.
