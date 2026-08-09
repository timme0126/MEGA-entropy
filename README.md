# MEGA — Make Entropy Great Again

A transparent, offline Android tool for converting **50 or 100 physical
six-sided die rolls, supplied entirely by the user,** into a valid 12- or
24-word English BIP39 mnemonic.

> **Status: experimental, not independently audited.** Do not use MEGA to
> protect meaningful funds yet. See [`SECURITY.md`](SECURITY.md) for the
> current audit status and what independent review is still needed.

## Download the beta APK

**Current beta:** [`mega-beta-v0.1.5.apk`](https://github.com/timme0126/MEGA-entropy/releases/download/v0.1.5/mega-beta-v0.1.5.apk)

Release page: [`MEGA Beta v0.1.5`](https://github.com/timme0126/MEGA-entropy/releases/tag/v0.1.5)

Verify the download before installing:

```bash
sha256sum mega-beta-v0.1.5.apk
# 01186366f7ce25f3977d3cb82109c1ecb164c771c3ccadb180689825090982d2
```

This is an experimental beta build for disposable test roll sequences only.

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

## Beta testing and hand verification

If you are testing MEGA, start with
[`docs/BETA-TESTING.md`](docs/BETA-TESTING.md). It explains the problem MEGA
is trying to solve: wallet seed generation often asks users to trust hidden
randomness, while MEGA makes the entropy source physical, visible, and
reviewable.

MEGA is designed to be verified by hand. The repository includes printable
reference PDFs under [`docs/references/`](docs/references/):

- [`dplusplus-hex.pdf`](docs/references/dplusplus-hex.pdf), mirrored from
  <https://dplusplus.me/hex.pdf>
- [`bip39-24-word-dice-worksheet.pdf`](docs/references/bip39-24-word-dice-worksheet.pdf),
  the MEGA 100 d6 / 24-word worksheet

The official BIP39 English word list is also vendored as plain text at
[`entropy-core/src/main/resources/bip39/english.txt`](entropy-core/src/main/resources/bip39/english.txt)
and hash-checked at runtime. Use
[`docs/HAND-VERIFICATION.md`](docs/HAND-VERIFICATION.md) to check MEGA's
displayed BIP39 indexes, hex values, checksum-sensitive final word, and final
mnemonic against those references. A small educational GitHub Pages-style site
is available at [`docs/index.html`](docs/index.html).

## What it does

1. You roll a fair six-sided die 100 times and enter each result.
2. MEGA maps each roll to a base-6 digit and interprets all 100 digits as
   one large integer `X`, shown incrementally as you enter each batch.
3. Unbiased rejection sampling: if `X` falls in the small range of values
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
6. The words are revealed only after a deliberate confirmation, with no
   copy/share/export anywhere in the app.
7. Optional: from the final mnemonic screen, MEGA can derive BIP-85 English
   BIP39 child mnemonics at a chosen index for 12 or 24 words. This is
   deterministic child derivation from the parent mnemonic, not fresh dice
   entropy.

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
grouping, word-list lookup, and BIP-85 child mnemonic derivation. See
[`docs/NO-RNG-PROOF.md`](docs/NO-RNG-PROOF.md) §2 for the full function
table.

## Documentation index

| Document | What it covers |
|---|---|
| [`docs/NO-RNG-PROOF.md`](docs/NO-RNG-PROOF.md) | The core "no device randomness in the seed" argument — read this first |
| [`docs/BETA-TESTING.md`](docs/BETA-TESTING.md) | Beta testing purpose, GrapheneOS guidance, and what to report |
| [`docs/HAND-VERIFICATION.md`](docs/HAND-VERIFICATION.md) | How to verify MEGA's BIP39 words by hand using the committed PDFs |
| [`docs/references/`](docs/references/) | Committed reference PDFs and their SHA-256 hashes |
| [`docs/index.html`](docs/index.html) | Mini GitHub Pages-style project site |
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
