# MEGA — Make Entropy Great Again

*mega.it*

A transparent, offline Android tool for converting **100 physical
six-sided-die rolls, supplied entirely by the user,** into a valid 24-word
English BIP39 mnemonic.

> **Status: experimental, not independently audited.** Do not use MEGA to
> protect meaningful funds yet. See [`SECURITY.md`](SECURITY.md) for the
> current audit status and what independent review is still needed.

## The core guarantee

```
24-word mnemonic = f(the 100 user-entered dice rolls)
```

and nothing else. MEGA's entropy pipeline (`:entropy-core`) cannot import
`SecureRandom`, `Random`, `UUID`, a clock, or any Android API — this is
enforced by a static check that fails the build (`./gradlew
:entropy-core:securityAudit`), not just a coding convention. Every other
place in the app that legitimately uses randomness (encrypting saved data,
salting a PIN hash, scrambling the PIN keypad) is enumerated with its exact
location and why it can't reach the entropy pipeline in
[`docs/NO-RNG-PROOF.md`](docs/NO-RNG-PROOF.md) — read that first if you're
reviewing this project.

## Badges

- **OFFLINE** — no `INTERNET` permission, no networking code
- **100 DICE ROLLS** — one d6, rolled by you, entered as 20 batches of 5
- **ZERO DEVICE ENTROPY IN SEED** — see above

## What it does

1. You roll a fair six-sided die 100 times and enter each result.
2. MEGA maps each roll to a base-6 digit and interprets all 100 digits as
   one large integer `X`, shown incrementally as you enter each batch.
3. Unbiased rejection sampling: if `X` falls in the small slice of values
   that would introduce modulo bias, the entire sequence is rejected and
   you re-roll from scratch — no partial retry, no workaround. About 1 in 8
   valid sequences hits this by design (see
   [`docs/ENTROPY-MATH.md`](docs/ENTROPY-MATH.md) for why).
4. An accepted `X` yields exactly 256 bits of entropy (`E = X mod 2^256`),
   from which MEGA computes the BIP39 checksum, splits into 24 groups of 11
   bits, and looks up each group in the official English word list.
5. Every step is shown on screen, with an expandable "show the math" for
   anyone who wants to verify by hand — see
   [`docs/BIP39-DERIVATION.md`](docs/BIP39-DERIVATION.md).
6. The 24 words are revealed only after a deliberate confirmation, with no
   copy/share/export anywhere in the app.

## Building and installing

Full instructions, including exact tool versions and troubleshooting, are
in [`docs/BUILD-AND-INSTALL.md`](docs/BUILD-AND-INSTALL.md). Short version:

```bash
./gradlew clean test lint assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Repository layout

```
app/            Android UI and app integration (Jetpack Compose, Material 3)
entropy-core/   Pure deterministic Kotlin/JVM module — the entropy pipeline.
                No Android dependency, no networking, no RNG/clock API
                (enforced by its own securityAudit Gradle task).
docs/           Technical documentation — start with NO-RNG-PROOF.md
```

Inside `entropy-core`, each concern is its own small file, deliberately
reading like the specification it implements rather than being clever:
dice mapping, batch accumulation, direct base-6 interpretation, rejection
sampling, entropy extraction, the SHA-256 checksum step, BIP39 bit
grouping, and word-list lookup. See
[`docs/NO-RNG-PROOF.md`](docs/NO-RNG-PROOF.md) §2 for the full function
table.

## Documentation index

| Document | What it covers |
|---|---|
| [`docs/NO-RNG-PROOF.md`](docs/NO-RNG-PROOF.md) | The core "no device randomness in the seed" argument — read this first |
| [`docs/ENTROPY-MATH.md`](docs/ENTROPY-MATH.md) | Why 100 rolls, why rejection sampling, the math behind each step |
| [`docs/BIP39-DERIVATION.md`](docs/BIP39-DERIVATION.md) | The exact BIP39 checksum/word-derivation steps, and the vendored word list's provenance |
| [`docs/SECURITY-MODEL.md`](docs/SECURITY-MODEL.md) | Plain-English threat model: what MEGA does and doesn't protect against |
| [`docs/STORAGE-DESIGN.md`](docs/STORAGE-DESIGN.md) | How saved sessions are encrypted and stored, and what deletion actually does |
| [`docs/BUILD-AND-INSTALL.md`](docs/BUILD-AND-INSTALL.md) | Exact tool versions, build commands, ADB install, GrapheneOS notes |
| [`docs/TEST-VECTORS.md`](docs/TEST-VECTORS.md) | Worked example vectors you can independently re-derive |
| [`docs/REPRODUCIBLE-BUILD.md`](docs/REPRODUCIBLE-BUILD.md) | Pinned tool/dependency versions and how to reproduce this APK |
| [`SECURITY.md`](SECURITY.md) | Vulnerability reporting and current audit status |
| [`PRIVACY.md`](PRIVACY.md) | What MEGA collects (nothing) |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | How to contribute |

## License

MIT — see [`LICENSE`](LICENSE).
