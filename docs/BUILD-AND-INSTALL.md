# Build and Install Guide

## Toolchain Versions
This project uses pinned, exact versions to ensure consistency and security. Do not upgrade these versions without careful review.

| Component | Version |
|-----------|---------|
| JDK | Temurin 17.0.20 (Eclipse Adoptium) |
| Gradle | 8.14.5 |
| Android Gradle Plugin (AGP) | 8.13.2 |
| Kotlin | 2.4.10 |
| Jetpack Compose BOM | 2026.06.01 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 29 |
| Android SDK Components | platform-tools, platforms;android-36, build-tools;36.1.0 |

*Note: AGP 8.13.2 is deliberately pinned over the newer 9.x line. AGP 9.x requires Gradle 9.6+ and changes Kotlin application (built-in support replaces `org.jetbrains.kotlin.android`). The 8.13.2 + 8.14.5 combination is well-established and thoroughly documented, which is prioritized for this security-sensitive app.*

## Prerequisites
- Install the required Android SDK components via the Android SDK cmdline-tools' `sdkmanager`.
- JDK 17 is installed via SDKMAN.
- Gradle does not need to be installed separately; use the committed wrapper (`gradlew`/`gradlew.bat`).

## Step-by-Step Build Instructions

**For local development** (debug build — debuggable, debug-signed, never
distribute this one; see `docs/RELEASE-SIGNING.md` for why):
1. Open a terminal in the repository root.
2. Run the full local verification command:
   ```bash
   ./gradlew clean test lint assembleDebug
   ```
3. The debug APK will be generated in `app/build/outputs/apk/debug/`. The expected filename is `app-debug.apk`, but if the build system ever produces a differently-named file, that's what actually exists — check the `app/build/outputs/apk/debug/` directory rather than assuming the filename.

**For the distributed beta APK** (release build — non-debuggable, signed
with the local beta-release key): see `docs/RELEASE-SIGNING.md` for
one-time keystore setup, then:
```bash
./gradlew clean test lint assembleRelease verifyReleaseArtifact securityAudit
```
The signed release APK is generated in `app/build/outputs/apk/release/app-release.apk`.
`verifyReleaseArtifact` fails the build if that APK is debuggable, isn't
signed by the expected key, or declares a forbidden permission — this is
the artifact that gets renamed to `mega-beta-vX.Y.Z.apk` and published as
the primary beta APK. A separately named `*-debug-compat.apk` may be
attached to a GitHub release only as a temporary migration aid for testers
who already installed an older debug-signed build and need to update
without uninstalling.

## Step-by-Step ADB Install Instructions
1. Connect your Android device (Android 10+) via USB and enable USB debugging.
2. Verify the device is connected and authorized:
   ```bash
   adb devices
   ```
3. If a device is listed with a status of `device`, proceed. If no device is listed or it shows `unauthorized`, see the troubleshooting section below.
4. Install the APK (debug build shown; substitute the release path for a beta build):
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
5. No signing or keystore setup is required to install a debug build locally; Android's default debug signing configuration is used automatically for `assembleDebug`. The release build requires the local keystore described in `docs/RELEASE-SIGNING.md` to build (not to install) — installing a signed release APK via `adb install` needs no keystore on the installing side either.

## Publishing Note
This project has NO Google Play Store publishing configuration and none is planned for v1. Sideloading via ADB (or a file manager) onto a personal device is the only supported install method.

## Troubleshooting
**Device not authorized for ADB:**
If `adb devices` does not show your device as `device`, check your phone screen for the "Allow USB debugging?" prompt. Tap "Allow" and ensure "Always allow from this computer" is checked. You may also need to verify that USB debugging is enabled in your device's Developer Options.
