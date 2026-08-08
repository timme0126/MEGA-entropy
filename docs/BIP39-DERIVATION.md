# BIP39 Derivation

## The word list: provenance and integrity

MEGA vendors the official BIP-0039 English word list verbatim at
`entropy-core/src/main/resources/bip39/english.txt` (2048 lines, one word
per line, in the official order — never re-sorted, alphabetized again, or
otherwise modified).

**Source.** Fetched from the official Bitcoin BIPs repository
(`bitcoin/bips`, `bip-0039/english.txt`), and cross-checked byte-for-byte
identical against two independent implementations' vendored copies:
`trezor/python-mnemonic` (`src/mnemonic/wordlist/english.txt`) and
`bitcoinjs/bip39` (`src/wordlists/english.json`). All three matched
exactly.

**Verification.** Its SHA-256 is recorded in the sibling file
`entropy-core/src/main/resources/bip39/english.txt.sha256`:

```
2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda
```

`WordList.loadOfficialEnglishWordList()`
(`entropy-core/src/main/kotlin/org/mega/entropycore/WordList.kt`) recomputes
this hash at **runtime**, every time the list is loaded, and refuses to
proceed (throws `IllegalStateException`) if it doesn't match, or if the
list isn't exactly 2048 non-blank lines, or if it contains any duplicate
entry. This means a corrupted or tampered word-list resource fails closed
rather than silently producing wrong words.

You can verify the vendored file yourself:

```bash
sha256sum entropy-core/src/main/resources/bip39/english.txt
# should print 2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda
```

## The derivation steps

Given 32 bytes of entropy `E` (see [`docs/ENTROPY-MATH.md`](ENTROPY-MATH.md)
for how `E` is derived from dice rolls):

1. **Checksum.** Compute `digest = SHA-256(E)` (32 bytes). Take the first 8
   bits of `digest` (the high 8 bits of `digest[0]`) as the checksum.
   (`Sha256Checksum.calculateChecksum`)
2. **Bitstream.** Concatenate the 256 entropy bits (MSB-first, byte 0
   first) with the 8 checksum bits, producing a 264-bit stream.
   (`Bip39BitStream.buildBitStream`)
3. **Split.** Split the 264-bit stream into 24 consecutive, non-overlapping
   11-bit groups, MSB-first within each group.
   (`Bip39BitStream.splitInto11BitGroups`)
4. **Index.** Interpret each 11-bit group as an unsigned integer, 0–2047.
5. **Word.** Use that integer as a **direct, zero-based index** into the
   2048-word list — index *is* list position; the list is never
   resorted, so this is a plain array/list lookup, not a search.
   (`WordList.deriveWords`)

Repeated for all 24 groups, this produces the 24-word mnemonic.

## Hex representation of an index

For display (spec section 12), each 11-bit index (0–2047) is also shown as
a canonical 3-character uppercase hex string:

| Decimal index | Hex |
|---|---|
| 0 | `000` |
| 1 | `001` |
| 2047 | `7FF` |

Some external lookup tables represent this as part of a 12-bit range by
setting a redundant high bit (e.g. showing both `001` and `801` for the
same underlying 11-bit index once that extra bit is masked off). MEGA's own
derivation is strictly 11-bit (0–2047) throughout — the padded/duplicated
12-bit convention is mentioned in the app's Word Derivation screen purely
as an educational note about what a user might see in other tools, and is
never part of MEGA's own algorithm.

## Scope: MEGA stops at the mnemonic

BIP-0039 separately defines how a mnemonic (plus an optional passphrase) is
turned into a 512-bit binary seed for BIP-0032 key derivation. MEGA v1
deliberately does **not** implement that next step, or anything past it
(no BIP-0032 keys, no addresses, no xpub/xprv, no signing) — this keeps the
scope, and the attack surface, of a security-sensitive app as small as
possible. MEGA's job ends at producing a verifiably-correct 24-word BIP39
mnemonic.
