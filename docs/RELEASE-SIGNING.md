# Release Signing

Every beta APK distributed to testers must be a **release build type** APK,
signed with a dedicated local key — never the `app-debug.apk` produced by
`assembleDebug`. A debug-signed, debuggable build is unsuitable for public
distribution: it's signed with Android's shared, well-known auto-generated
debug key (anyone can produce an APK claiming the same signer), and
`android:debuggable="true"` gives any app or tool on the device (or over
`adb` if USB debugging is ever enabled) the ability to attach a debugger,
inspect process memory, and read app-private data that would otherwise be
sandboxed — an unacceptable risk for an app that briefly holds mnemonic
words, seeds, and PINs in memory.

## What "release" means here

This is **not** a Play Store or CA-verified release — MEGA has no publisher
identity to verify against (see `SECURITY.md`: "experimental, not
independently audited"). The release keystore below is a **local,
self-signed, beta-testing-only signing key**. Its only job is *signer
continuity*: proving that a new beta APK came from the same source as a
previous one, the same way SSH host-key pinning works without a CA. It does
**not** mean "trust this build" — read `SECURITY.md`, review the source,
and see `docs/REPRODUCIBLE-BUILD.md` for the current build-verification
limits.

## Generating the local beta-release keystore (one-time, per machine)

```bash
mkdir -p keystore
keytool -genkeypair -v \
  -keystore keystore/mega-beta-release.jks \
  -alias mega-beta \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -storetype PKCS12 \
  -dname "CN=MEGA Beta Local Signing Key (unverified), OU=Unverified, O=Unverified, L=Unknown, ST=Unknown, C=US"
```

`keytool` will prompt for a store/key password — use a strong, unique one.
Then create `keystore.properties` in the repo root (both `keystore/` and
`keystore.properties` are git-ignored — **never commit either one**):

```properties
storeFile=keystore/mega-beta-release.jks
storePassword=<the password you chose>
keyAlias=mega-beta
keyPassword=<the same password, or a distinct key password if you set one>
```

Without `keystore.properties` present, `./gradlew assembleRelease` still
succeeds but produces an **unsigned** APK, and `verifyReleaseArtifact`
(below) refuses to pass — there is no silent fallback to debug signing.

## Building and verifying a release APK

```bash
./gradlew assembleRelease verifyReleaseArtifact
```

`verifyReleaseArtifact` fails the build unless the produced
`app/build/outputs/apk/release/app-release.apk`:
- is **not** debuggable (`aapt2 dump badging` shows no `application-debuggable` line),
- is signed by exactly the expected local key (see fingerprint below), and
- declares none of the forbidden network/storage permissions `securityAudit` already checks in the manifest. `CAMERA` is allowed only for local multisig QR scanning.

You can also check by hand:

```bash
# Must print nothing containing "application-debuggable":
$ANDROID_HOME/build-tools/<version>/aapt2 dump badging app/build/outputs/apk/release/app-release.apk | grep debuggable

# Must show the signer fingerprint below:
$ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## Expected signer certificate fingerprint

This is **not secret** — it's meant to be public, so anyone can confirm a
downloaded APK's signer matches previous releases:

```
SHA-256: 42:C9:DA:07:22:58:5A:04:C3:38:8E:99:89:B8:EB:CB:4B:62:73:16:29:92:4A:AE:3C:96:EE:C7:D5:36:48:9C
```

`verifyReleaseArtifact` hard-codes this same value
(`expectedBetaReleaseSignerSha256` in `app/build.gradle.kts`) and fails the
build on a mismatch.

## Distributing a beta build

1. `./gradlew clean test lint assembleRelease verifyReleaseArtifact securityAudit`
2. Compute the APK's own sha256 (`sha256sum app/build/outputs/apk/release/app-release.apk`) and put both the filename and hash in `README.md`'s "Download the beta APK" section — the same as before, just pointing at the release artifact instead of the debug one.
3. If a release also includes a `*-debug-compat.apk`, label it as a compatibility-only artifact for users updating from an older debug-signed install. Do not describe it as the security-preferred build.
4. Install/sideload it the same way as before (`adb install -r <path>`); no debug signing setup is needed on the tester's end.

## If the keystore is lost or rotated

Losing `keystore/mega-beta-release.jks` (or deliberately rotating it) just
means the next beta release signs with a **new** key — existing testers
get a normal "package signatures don't match" error from `adb`/their
launcher when trying to install an update over the old one, and must
uninstall the old APK first. No user data is at risk from this: MEGA never
syncs saved-session data anywhere the old signing key could have protected
access to, and reinstalling starts with an empty saved-session store like
any fresh install. After rotating, regenerate the keystore per the steps
above and update `expectedBetaReleaseSignerSha256` in `app/build.gradle.kts`
and the fingerprint in this doc to match.
