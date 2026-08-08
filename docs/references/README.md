# Reference PDFs

These PDFs are committed so a beta tester can clone the repository, go offline, and still have the paper references needed to verify MEGA's output by hand.

Do not write real seed material on a reference sheet you intend to keep. If you use any worksheet with a real mnemonic, treat it as the secret itself and destroy any working copy when finished.

## Files

| File | Source | Purpose | SHA-256 |
|---|---|---|---|
| [`dplusplus-hex.pdf`](dplusplus-hex.pdf) | <https://dplusplus.me/hex.pdf> | BIP39 English words with 3-character hex lookup values. Useful for checking MEGA's displayed word indexes against an independent table. | `de463dbd4a1e2bee9312081858560af5a5d86d907b4e95db5f511257345fd317` |
| [`bip39-24-word-dice-worksheet.pdf`](bip39-24-word-dice-worksheet.pdf) | Local MEGA worksheet from `app/build/outputs/apk/debug/BIP39_24_Word_Dice_Worksheet.pdf` | Project-specific 24-word worksheet for recording 100 d6 rolls and checking the 24 derived BIP39 words. | `f8e9c1cf7e0ef746c08c914384db105632d7c804336caaef2d831e78a05af8d1` |

## Vendored Word List

MEGA vendors the official BIP39 English word list as plain text at
[`../../entropy-core/src/main/resources/bip39/english.txt`](../../entropy-core/src/main/resources/bip39/english.txt).
The expected SHA-256 is stored next to it in
[`../../entropy-core/src/main/resources/bip39/english.txt.sha256`](../../entropy-core/src/main/resources/bip39/english.txt.sha256)
and checked by the app at runtime.

## Verify Local Copies

From the repository root:

```bash
sha256sum docs/references/*.pdf entropy-core/src/main/resources/bip39/english.txt
```

Compare the PDF hashes with the table above and the word-list hash with
`entropy-core/src/main/resources/bip39/english.txt.sha256`.
