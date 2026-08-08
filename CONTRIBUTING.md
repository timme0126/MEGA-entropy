# Contributing to MEGA

MEGA is a security-sensitive project. Thank you for your interest in contributing. Please read this guide carefully before submitting changes.

## Building and Testing Locally
Ensure you have the exact toolchain specified in [docs/BUILD-AND-INSTALL.md](docs/BUILD-AND-INSTALL.md). Run the full verification suite before submitting any change:
```bash
./gradlew clean test lint assembleDebug securityAudit
```
The test suite includes 59 automated JUnit tests in `entropy-core` (dice mapping, batch accumulator math, rejection sampling boundaries, BIP39 test vectors, and word list integrity). Run them specifically with:
```bash
./gradlew :entropy-core:test
```

## entropy-core Restrictions
The `entropy-core` module must remain a pure Kotlin/JVM library with zero Android or third-party dependencies. It must never reference:
- `SecureRandom`, `Random`, `UUID`
- `System.currentTimeMillis` / `System.nanoTime`
- `java.time` or `java.util.Date`
- Any `android.*` API

These restrictions are strictly enforced by the security audit task. Run it to verify compliance:
```bash
./gradlew :entropy-core:securityAudit
```
Any PR that fails this audit should not be merged.

## Code Style and Architecture
- Prefer small, well-named functions that read like the specification being implemented over clever one-liners. This is especially critical in `entropy-core` and the storage/PIN security code (`org.mega.entropy.storage` / `org.mega.entropy.security.pin`).
- Changes to `entropy-core` or the storage/PIN modules warrant extra scrutiny and must include or update relevant tests.
- No dependency-injection framework is used. Manual constructor injection is preferred throughout the project.

## Reporting Security Issues
For anything security-relevant, please follow the reporting process outlined in [SECURITY.md](SECURITY.md) instead of opening a public issue.
