# PSBT v0 legacy export (BlueWallet compatibility)

## The problem

MEGA-signed PSBTs imported cleanly into Sparrow, but BlueWallet rejected
every signed-PSBT QR code MEGA produced, while other wallets (Sparrow
first and foremost) accepted the exact same codes without complaint.

The failure was NOT in the QR transport layer. Reproduced end to end
(2026-10-13): MEGA's BBQr parts decode correctly through BlueWallet's
own decoder stack (`@scure/base` base32 decode, header framing,
multi-part reassembly — a faithful port of BlueWallet's `bbqr` module
decodes MEGA's output to the exact joined PSBT bytes). The rejection
happens one step later, at BlueWallet's
`bitcoinjs-lib@7.0.2`-`Psbt.fromBase64()` call:

```
bip174_parseFAIL: "Unknown global type 2"
```

## Root cause

MEGA **passes a PSBT through** exactly as it arrived: `parsePsbt` →
sign → `serializePsbt` preserves every entry MEGA didn't personally
author, entry order included. When a v2-built coordinator (Sparrow
being the one in daily use) constructs a PSBT, its global map carries
BIP174 v2 fields (0x02 transaction version, 0x03 fallback locktime,
0x05 output count, ...) — and those travel, intact, into MEGA's
signed export, because faithful passthrough is precisely what a
signer must do with a document another wallet built. Sparrow's parser
reads the result fine. BlueWallet's bundled parser
(bitcoinjs-lib 7.0.2) implements only BIP174 **v0** and treats any
global key type 0x02 as fatal, regardless of what else the document
contains. This is a hard import wall, not a degraded import.

Reproduction of the failure at the `Psbt.fromBase64()` level — with a
real signed/finalized MEGA export carrying v2 globals — is covered
mechanically by the committed test `a document carrying v2 global
0x02 is exactly what a strict v0 parser must reject and what
downgrade fixes` (an independent strict-v0 validator enforcing
bitcoinjs-lib's exact rejection rule, plus the downgrade repairing
it).

## The fix

`downgradePsbtToV0()` in
`entropy-core/src/main/kotlin/org/mega/entropycore/PsbtV0Downgrade.kt`
re-serializes a PSBT as a strict v0 document, for legacy coordinators.
The primary export path (`PsbtSignResultScreen`'s default encoding) is
unchanged — v2 stays the default, and the new code only ever produces a
**second, opt-in** output.

A v2 document's global fields carry information a v0 document encodes
differently, not additional information:

| v2 global | v0 representation |
|---|---|
| 0x02 tx version, 0x03 fallback locktime, 0x04/0x05 input/output counts | the v0 document's own embedded unsigned transaction (global key 0x00) already encodes exactly these |
| 0x06/0x07 v2 version / modifiable flags | v0 has no equivalent concept; BIP174 v2's own text requires them to be consistent with the unsigned transaction, so a v0 reader loses nothing |
| 0x0E/0x0F/0x10 (per-input previous-txid, output index, sequence) | the same data, read positionally out of the v0 document's embedded unsigned transaction's own input list |
| 0x11/0x12 (required time/height locktime) | v0 has no per-input locktime expression at all — a v0 reader only understands the embedded transaction's nLockTime |

so the downgrade **drops** them, never silently: `downgradePsbtToV0`
refuses any global key type outside `{0x00, 0x01, 0x02..0x07, 0xFB}`
rather than guessing, verifies 0x02's value actually matches the
embedded unsigned transaction's own version before dropping it, and
refuses (rather than reorders or re-encodes) any input entry whose key
type a v0 reader wouldn't understand.

What a v0 document carries that the v2 source also carried is preserved
exactly, byte for byte: global 0x01 (xpub) and 0xFB (proprietary)
entries keep their raw `keyData`/`value`; every input entry with a
v0-legal key type (0x00..0x0D — non-witness and witness UTXOs, partial
signatures, sighash type, redeem/witness scripts, BIP32 derivations,
final scriptSig/witness, taproot key-sigs/scripts/control-blocks — and
0xFB) is copied verbatim. Before returning, the function verifies,
against the **original** document (not just the one it just built),
that:

- the output re-parses;
- the embedded unsigned transaction is byte-identical to
  `serializeTransaction` of the original's; and
- every v0-legal original input entry is still present, byte-identical.

Anything else is a hard refusal.

## UI

`PsbtSignResultScreen` ("Updated PSBT", the partially-signed export
step) gains a **"Legacy wallet mode (PSBT v0, e.g. BlueWallet)"**
checkbox, off by default. Enabling it re-encodes the export QR/base64
copy from the downgraded bytes. If a document refuses downgrade, the
checkbox surfaces a plain message to export normally and use a
v2-aware coordinator — the app never shows a second QR that is subtly
different from, or broken relative to, what the primary export produced.

## Test & verification trail

`PsbtV0DowngradeTest`:

- **Golden vector**: MEGA's own deterministic single-sig
  signed+finalized output for a fixed fixture, downgraded, asserted
  **byte-identical** to a committed golden base64. That golden was
  produced independently (a separate Node script, not this module) and
  **verified end to end with bitcoinjs-lib 7.0.2** — the exact parser
  BlueWallet ships — for `Psbt.fromBase64`, finalization pass-through,
  and `extractTransaction` (txid
  `b4ca8f48572bf08354f8302adfbd9e5c2fc2a52731de5401a39aa048f68c9c21`).
- An **independent strict-v0 validator** (deliberately shares no code
  with the production parser, in the spirit of
  `BbqrSparrowCompatTest`) rejects MEGA's native v2 bytes and accepts
  the golden.
- Signable fields (witness UTXO, BIP32 derivations, final witness)
  asserted present and byte-identical after downgrade.
- Downgrade is idempotent.
- Unrecognized global key types are a hard refusal.
