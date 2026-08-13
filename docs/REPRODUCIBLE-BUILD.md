# Build Verification Notes

## Version Pinning
This repository pins exact tool and dependency versions rather than using
floating ranges. That keeps local review builds consistent and makes changes
easier to audit, but it is not yet a guarantee that two machines, or even two
clean builds on the same machine, will produce byte-for-byte identical APKs.

| Component | Pinned Version |
|-----------|----------------|
| JDK | Temurin 17.0.20 (Eclipse Adoptium) |
| Gradle | 8.14.5 |
| Android Gradle Plugin (AGP) | 8.13.2 |
| Kotlin | 2.4.10 |
| Jetpack Compose BOM | 2026.06.01 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 29 |

## What to Verify for the Beta APK

For v0.1.9 beta testing, verify the distributed APK itself:

1. Its SHA-256 hash matches the value published in `README.md`.
2. It is not debuggable.
3. Its signer certificate SHA-256 matches the pinned beta-release fingerprint
   in `docs/RELEASE-SIGNING.md`.
4. It declares no forbidden network or storage permissions.

The release gate for those artifact-level checks is:

```bash
./gradlew assembleRelease verifyReleaseArtifact
```

`verifyReleaseArtifact` checks the built release APK for debuggability, signer
fingerprint, and forbidden permissions. It does not prove that the APK is
byte-for-byte reproducible from source.

## Rebuild Reproducibility Status

Bit-for-bit reproducible Android APK builds are not yet guaranteed for this
project. Recent release builds have been observed to produce different APK
hashes from identical source across clean rebuilds, while still passing the
important artifact security checks: non-debuggable, expected signer, and no
forbidden permissions.

Sources of variance can include:

- signing metadata and APK packaging details,
- timestamps or version-control metadata embedded by the Android toolchain,
- absolute path or environment differences,
- minor variations in OS, JDK, Android SDK, Gradle, or AGP behavior.

Because of this, a locally rebuilt APK hash should not currently be expected
to match the published beta APK hash. Use source review plus the artifact
verification checks above for now.

## Future Work
Establishing a reproducible release-build pipeline is a specific area for
future work and independent verification. A future release should document the
exact environment and build flags required, then include a CI or reviewer
procedure that proves repeated clean builds produce the same APK bytes.
