# Wide-compatibility broadcast QR (Sentinel / pre-BBQR coordinators)

## Why

MEGA's native broadcast export is **BBQR**. Sentinel (Samourai lineage;
current community fork `wanderingking072/sentinel-android`) does not
recognize BBQR — scanning a MEGA BBQR broadcast code yields:

```
Incompatible QR format. Cannot parse transaction.
```

## What Sentinel actually accepts (source-verified)

From `app/src/main/java/com/samourai/sentinel/ui/broadcast/BroadcastTx.kt`:

- `validate(hex, isQRFormat)` parses **`Hex.decode(input)` as a raw
  transaction** first. This is the only path that reaches
  BROADCAST — Sentinel broadcasts **raw signed transaction hex**,
  never a PSBT.
- A successfully-parsed *PSBT* input deliberately shows
  "Incompatible format. Cannot parse transaction." and disables the
  button — Sentinel does not broadcast signed PSBTs by design.
- The scanner module (`hummingbird-android`, wrapping
  `com.sparrowwallet.hummingbird`) accepts **UR2 animated QRs**; a
  `crypto-psbt` UR is hex-encoded and fed through the same
  `validate()` — so it hits the same PSBT rejection unless it carries
  a raw transaction.
- The plain single-frame QR callback delivers the string straight to
  `validate()` — **a static QR of the raw transaction hex is the
  correct wide-compatibility scan**.

## What MEGA now offers

An opt-in **"Wide-compatibility QR (Sentinel: plain hex, single
frame)"** checkbox on the fully-signed screen, off by default. When
enabled it renders the exact same transaction hex shown in the
"Transaction (hex)" card as **one static QR** — no BBQR framing, no
transformation. Transactions whose hex exceeds the single-QR
capacity (~2900 characters) show a copy-and-paste instruction
instead of a broken render.

**Clipboard route (no QR):** the existing copy button on
"Transaction (hex)" + Sentinel's paste field is equivalent and
handles arbitrarily large transactions. Note Sentinel expects **hex**,
not base64 — MEGA's PSBT base64 copy is for PSBT-importing wallets,
not Sentinel.

## Deliberately not included

An earlier draft added a Specter-style `p(i)of(N)` base64 PSBT
animation. The source inspection above shows Sentinel has no Specter
decoder and rejects parsed PSBTs regardless of framing, so that
export would have solved nothing for the reported failure and was
removed before merge. If a pre-BBQR Specter-era coordinator ever
needs it, the framing helper is trivial to reintroduce with tests.

## Verification

- Frames are the raw transaction hex only — the same bytes as the
  copyable card, asserted by the existing extraction tests
  (`extractFinalTransactionHex`).
- Full gate on the merge commit: `test`, `securityAudit` (×4),
  `lint`, independent derivation — all pass.
