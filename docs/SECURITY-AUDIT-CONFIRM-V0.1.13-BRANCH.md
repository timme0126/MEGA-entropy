# Audit confirmation — pre-push verification of `feature/tablet-responsive-layout`

**Date:** 2026-08-29
**Tree:** `mega-clean` working copy, branch `feature/tablet-responsive-layout`, commit `bde4246` ("Add tests for the responsive layout breakpoints and primitives"), 6 commits ahead of `origin/main` (`7822e33`), clean working tree.
**Auditor:** Hermes agent (Troy's workstation session), on the OpenClaw build VM. This is an internal re-verification of the automated security checks plus an independent spot-review; it is **not** an independent third-party audit. `SECURITY.md` status ("not yet independently audited") is unchanged.

## Scope

The 6 unpushed commits are UI-layout changes only (responsive primitives, PIN-keypad width cap, responsive dice-flow / Welcome / Loading screens, two-pane Saved Sessions and Settings, layout tests). The audit re-ran every automated security gate against the exact branch tip and independently spot-checked the results.

## Results

| Gate | Command | Result |
|---|---|---|
| Entropy-core no-RNG static audit | `./gradlew :entropy-core:securityAudit` | **PASSED** — no prohibited RNG/clock/Android/reflection references |
| App manifest / backup-exclusion audit | `./gradlew :app:securityAudit` | **PASSED** — no forbidden network/storage permissions; `allowBackup=false`; `dataExtractionRules` + `fullBackupContent` present |
| Dependency audit | `./gradlew :app:dependencyAudit` | **PASSED** — release runtime classpath free of network/telemetry/ads/cloud SDK artifacts |
| Merged-manifest permission verify | `./gradlew :app:verifyMergedManifestPermissions` | **PASSED** — debug and release merged manifests checked |
| Full test suite | `./gradlew test` | **820 tests, 0 failures, 0 skipped** (entropy-core, debug, release unit tests) |
| Lint | `./gradlew lint` | **0 errors, 35 warnings** (`app/build/reports/lint-results-debug.html`) |
| Independent derivation cross-check | `python3 tools/independent_derivation.py` | **PASS** — code-free Python re-derivation agrees with the Kotlin pipeline on all vectors, including a rejection-sampling case and a 50-roll (128-bit) vector |

## Independent spot-checks

- **`SecureRandom` call sites:** exactly four in `:app` (`BackupRepository.kt`, `BackupSharesViewModel.kt`, `PsbtSignResultScreen.kt`, `ScrambledKeypad.kt`, `PinCrypto.kt` salt paths), all in storage/PIN/UX roles per spec section 2, none reachable from entropy derivation. Consistent with `docs/NO-RNG-PROOF.md`.
- **Third-party dependency surface:** `com.sparrowwallet:hummingbird` is imported at a single site (`PsbtScanScreen.kt`, UR/QR decoding only); BouncyCastle is used only for scrypt in backup crypto. No other non-AndroidX/Jetpack third-party runtime code observed.

## Hardening findings (on the audit tooling itself, not the app)

None of these are active vulnerabilities; they are durability gaps in the checks the security story leans on. Recorded here so an independent auditor sees them as known.

1. **`entropy-core` audit is substring-based.** It blocks literal patterns (`SecureRandom`, `Class.forName`, `java.lang.reflect`, …). Deliberately obfuscated references (string-concatenated symbols, less-common dynamic-loading paths) would not trip it. A prior bypass was already found and closed (see `CODEX-AUDIT-ENTROPY-CORE.md`). Robust fix: replace the grep with an AST-level check (detekt custom rule or Kotlin compiler plugin). **Highest-priority follow-up.**
2. **`dependencyAudit` forbids `webview` as an *artifact name*.** WebView risk is framework class usage, not a dependency; a code-level scan for `android.webkit` imports would cover the actual threat. No app code currently uses it.
3. **Forbidden-artifact list gaps:** `volley`, `cronet`, `grpc`, `googlerpc` are network-adjacent and not in the pattern list. Cheap to extend.

## Decision

Branch `feature/tablet-responsive-layout` @ `bde4246` was considered verified-clean and pushed to `origin` on 2026-08-29 with this confirmation attached. Merge into `main` and any v0.1.14 release remain separate, explicit decisions.
