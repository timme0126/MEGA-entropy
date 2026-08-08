# Reproducible Build Notes

## Version Pinning
This repository pins exact tool and dependency versions rather than using floating ranges. This is done specifically so a given commit can be rebuilt reproducibly across different machines and environments.

| Component | Pinned Version |
|-----------|----------------|
| JDK | Temurin 17.0.20 (Eclipse Adoptium) |
| Gradle | 8.14.5 |
| Android Gradle Plugin (AGP) | 8.13.2 |
| Kotlin | 2.4.10 |
| Jetpack Compose BOM | 2026.06.01 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 29 |

## Debug Build Reproducibility
While source and tool versions are pinned, debug-build APK bytes can still differ between machines or environments even with identical source and pinned tool versions. Factors that introduce variance include:
- Different debug signing keys (Android's default debug keystore is generated per-user/machine)
- Build timestamps embedded by the toolchain
- Absolute path differences in build artifacts
- Minor variations in underlying OS or JDK implementations

Because of these factors, an APK hash alone isn't a rebuild-verification tool for debug builds the way it would be for a reproducible *release* build with a fixed signing key.

## Future Work
This project does not yet have a reproducible release-build setup (v1 only produces debug builds). Establishing a reproducible release-build pipeline is a specific area for future work and independent verification, as outlined in SECURITY.md's "next security steps".
