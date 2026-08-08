# Hand Verification Guide

MEGA is meant to be checked, not trusted blindly. The app shows the dice input, base-6 value, rejection-sampling decision, 256-bit entropy, checksum, 11-bit groups, hex indexes, and final BIP39 words so a beta tester can spot-check the pipeline with paper references.

This guide is for disposable test mnemonics only. Do not put real wallet funds behind a mnemonic generated during beta testing.

## Reference Materials

The repository includes printable references in [`docs/references/`](references/):

- [`dplusplus-hex.pdf`](references/dplusplus-hex.pdf) from <https://dplusplus.me/hex.pdf>
- [`bip39-24-word-dice-worksheet.pdf`](references/bip39-24-word-dice-worksheet.pdf), the MEGA 100 d6 / 24-word worksheet

The MEGA worksheet is useful for recording a 100-roll / 24-word check, but it is not a substitute for MEGA's dice-to-entropy derivation. MEGA currently derives a 24-word mnemonic from 100 d6 rolls. The official BIP39 English word list is vendored in this repository as `entropy-core/src/main/resources/bip39/english.txt` and hash-checked at runtime.

## What To Verify

For a beta test, verify at least these points:

1. The app accepted exactly the 100 die faces you entered.
2. The bias check either accepts the whole 100-roll sequence or rejects the whole sequence. There should be no partial retry path.
3. The 256-bit entropy shown by MEGA matches the accepted value derived from the dice math described in [`ENTROPY-MATH.md`](ENTROPY-MATH.md).
4. The checksum byte shown by MEGA is the first 8 bits of `SHA-256(entropy)`, as described in [`BIP39-DERIVATION.md`](BIP39-DERIVATION.md).
5. The 264-bit entropy-plus-checksum stream splits into 24 11-bit groups.
6. Each 11-bit group is the same number as MEGA's displayed decimal index and 3-character hex index.
7. Each index maps to the same BIP39 word in the app and at least one paper reference.

## Hex Lookup Notes

MEGA's derivation uses 11-bit BIP39 indexes: decimal `0` through `2047`, or hex `000` through `7FF`.

Some paper references, including `dplusplus-hex.pdf`, also show a second hex value with `8` as the leading nibble, such as `001 or 801`. That is a 12-bit display convention. For MEGA verification, use the lower 11 bits: `801` and `001` point to the same BIP39 index once the extra high bit is ignored.

## Suggested Manual Check

For every word, or for a smaller spot-check during early beta testing:

1. Open MEGA's Word Derivation screen.
2. Pick a row and copy its decimal index and hex index onto scratch paper.
3. Open `dplusplus-hex.pdf`, or inspect the vendored `english.txt` word list.
4. Find the displayed hex index.
5. Confirm the word in the PDF is the same word MEGA displays.
6. Repeat for the first word, last word, and several middle words. The last word is especially important because it contains checksum bits.

For a full paper pass, use [`bip39-24-word-dice-worksheet.pdf`](references/bip39-24-word-dice-worksheet.pdf) to record the 100 d6 rolls, all 24 words, and their lookup values. Destroy the worksheet when finished if it contains real seed material.

## Independent Software Check

MEGA also includes an independent derivation helper with documented vectors:

```bash
python3 tools/independent_derivation.py --self-test
```

The script is intentionally separate from the Android UI so reviewers can compare the app result against a smaller reference implementation for known cases.
