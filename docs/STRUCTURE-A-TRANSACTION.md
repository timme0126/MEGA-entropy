# Structure a Transaction

Advanced Mode's UTXO-split builder — turns a wallet's balance into several
equal-sized UTXOs (plus a leftover output), fully offline. This document
explains why it works the way it does, not just what the buttons do.

## The problem

MEGA has no blockchain access — no `INTERNET` permission, no node
connection, no way to look up what UTXOs a wallet actually holds. Splitting
a balance into N equal outputs requires knowing the *inputs*: their
`txid`, `vout`, exact amount, and scriptPubKey. A first version of this
feature asked the user to type all of that by hand, per UTXO. It was
unusable for anything beyond a couple of inputs, and error-prone — a single
mistyped `txid` character silently produces a transaction spending a UTXO
that doesn't exist.

## The approach: harvest from a scanned PSBT

A watch-only companion wallet (Sparrow, or anything tracking the same
account) *does* have blockchain access, and already knows exactly which
UTXOs exist. So instead of manual entry:

1. Build an ordinary transaction in Sparrow, spending whatever balance you
   want structured — the destination/amount you pick there doesn't matter,
   it gets discarded.
2. Export it as an unsigned PSBT (single QR or animated BBQr) and scan it
   into MEGA via **Advanced Mode → Structure a Transaction**.
3. MEGA parses the PSBT and extracts every input this device's loaded seed
   can prove ownership of — `txid`, `vout`, amount, scriptPubKey, and BIP32
   derivation — directly from the PSBT's own `witness_utxo` and
   `bip32_derivation` fields (`harvestOwnedInputsForStructuring` in
   `entropy-core`). No typing.
4. The original outputs are discarded entirely. MEGA builds new ones from
   the split parameters below.

**Ownership proof is strict.** An input is only accepted if its
`bip32_derivation` matches this device's master key — either a real,
verified fingerprint, or the BIP174 "unrecorded" `00000000` placeholder
*plus* an independently verified derived public key along the claimed path
(the same fallback ordinary PSBT signing already uses for watch-only
imports that never recorded an origin fingerprint). A PSBT with even one
input this device can't prove it owns is refused outright — never silently
partially structured.

**This also means MEGA is blind to anything not in the scan.** If your
wallet's coin selection left a UTXO out of the transaction you built in
Sparrow, MEGA has no way to know it exists — it isn't part of the balance
being structured. The app shows a mandatory acknowledgment screen, before
the camera ever opens, explaining this and recommending you double-check
which UTXOs are selected (and freeze any you want excluded) before building
the source transaction.

## Split parameters

- **Split amount** — BTC per output. MEGA computes the largest number of
  equal-sized outputs (`N`) the harvested balance can cover at the given
  fee rate, trying `N`, `N-1`, `N-2`, … until the fee fits.
- **Fee rate** (sats/vByte) — used with `estimateSplitTransactionFeeSats`
  (a P2WPKH weight estimate shared with the PSBT review screen's own fee
  display, so the number targeted here and the number shown at review
  never disagree).
- **RBF** — sets sequence `0xFFFFFFFD` (opt-in replace-by-fee, BIP125) vs
  `0xFFFFFFFF`.
- **Starting receive-address index** — where the split outputs begin.
  Index `0` fills `0..N-1`; index `9` fills `10..N+9`, skipping `0-9`. MEGA
  has no chain awareness of which addresses are already used, so this is
  the user's responsibility — cross-check against the destination wallet's
  own next-unused index.
- **Destination** — the source wallet itself (self-split) or another
  wallet's account xpub/zpub (scan or paste). Both derive plain P2WPKH
  (BIP84) addresses only in this version.
- **Remaining balance** — after the largest possible number of equal
  outputs, any leftover either sweeps into one more output at the next
  sequential destination index (fully clears the source wallet), or goes
  to an explicit change-address index in the source wallet instead
  (`RemainderDestination`). A remainder below the 546-sat dust limit is
  folded into the fee — Bitcoin has no way to create a sub-dust output.

Before signing, every output — including the leftover, whichever form it
took — is listed with its derivation index, address, and amount, for one
explicit confirmation. The result then flows into the same PSBT review →
sign → result screens the ordinary "Sign PSBT" flow uses, ending in a
broadcast-ready transaction (animated QR + hex) for Sparrow/BlueWallet to
relay.

## Scope (this version)

Native SegWit (P2WPKH) single-sig sources only — matches `entropy-core`'s
existing PSBT finalizer exactly. Multisig-vault sources (P2WSH) are not yet
supported.
