# Changelog

Notable changes to MEGA, most recent first. The current beta build is `v0.1.11` (see [`README.md`](README.md#download-the-beta-apk)).

## [0.1.11] — 2026-08-20

### Added
- **"Structure a Transaction"** (Advanced Mode) — splits a wallet's balance into equal-sized UTXOs entirely offline. MEGA has no blockchain access, so it never asks the user to type a source UTXO's txid/vout/amount by hand: instead, you build an ordinary transaction in a chain-aware watch-only wallet (e.g. Sparrow), scan the resulting PSBT, and MEGA harvests the real inputs directly from it (`harvestOwnedInputsForStructuring`). You choose a split amount, fee rate, RBF on/off, a starting receive-address index, a destination (self-split or another wallet's xpub, scan or paste), and where any leftover balance goes — swept into the next destination address (fully clearing the source wallet) or back to an explicit change address in the source wallet. Before signing, an explicit preview lists every output's derivation index, address, and amount. A mandatory acknowledgment screen, shown before the camera opens, explains that MEGA can only structure a transaction from whatever UTXOs the scanned PSBT actually included — see `docs/STRUCTURE-A-TRANSACTION.md`.
- Optional Android device security verification screen (Advanced Mode entry), with direct links to relevant device settings.

### Changed
- Moved PSBT review, diagnostics, and signing off the Compose main thread with cancellable, single-execution workers; the UI now shows an explicit progress state instead of hanging or re-running cryptography during recomposition.
- Streamlined PSBT signing so fingerprint diagnostics no longer gate the signing flow; the 00000000 origin-fingerprint condition is shown informationally after signing. Added a top-bar Save action for signed PSBT files and placed signed transaction hex below the broadcast QR.

### Fixed
- Fixed RFC6979 `bits2octets` handling for the secp256k1 boundary where a SHA-256 hash is at or above the curve order, with a regression test.

## [0.1.10] — 2026-08-13

Findings from a full security audit of v0.1.9 and an independent follow-up review (PSBT signing, multisig
vaults, BBQr/SeedQR, storage, release pipeline). All fixes carry
regression tests. See `docs/PSBT-SECURITY.md` for the resulting signing
security model.

### Fixed (security)
- **PSBT signing accepted arbitrary sighash types.** A malicious PSBT
  could request SIGHASH_NONE/SINGLE/ANYONECANPAY, producing a signature
  that does NOT commit to every output shown on the review screen — the
  reviewed transaction would not be the transaction authorized. Signing
  now refuses any input whose requested sighash type is present and not
  SIGHASH_ALL, and the review screen blocks its Confirm action and says
  why. (Found in audit; present since PSBT signing was introduced.)
- **Duplicate keys in a PSBT map were not rejected**, despite BIP174
  requiring uniqueness — a divergence vector between what MEGA and
  another implementation see in the same file. Parsing now fails closed.
- **PSBT unsigned-transaction laxity**: trailing garbage, witness
  serialization (0x00 0x01 marker/flag), and non-empty scriptSigs are now
  all rejected, per BIP174.
- **Signing did not verify the spent UTXO commits to the script being
  signed.** A witnessScript/P2WPKH-pubkey that doesn't match the
  witness_utxo's scriptPubKey produced signatures that can never
  validate (and let P2SH-wrapped inputs into a finalizer that can't
  build their final_scriptSig). Mismatched inputs are now skipped;
  already-finalized inputs are never re-signed.
- **Finalizer could produce invalid "final" witnesses** for scripts that
  only loosely resembled multisig (a leading byte ≤ OP_0 could even
  finalize an input with zero signatures). Finalization now requires the
  exact OP_M <keys> OP_N OP_CHECKMULTISIG template plus a matching UTXO,
  and P2WPKH finalization requires the signature's pubkey to match the
  UTXO program. Anything else stays unfinalized.
- **BBQr 'Z' (deflate) decoding had no output cap** — a hostile animated
  QR series could act as a zip bomb (memory-exhaustion DoS). Inflate
  output is now capped at 8 MB.
- **BBQr scanner silently overwrote a frame when a different payload
  arrived for an already-scanned index**, allowing two QR series to mix.
  Conflicts are now detected, reported, and never applied.
- **"Likely change" could be spoofed by coordinator metadata.** In the
  saved-vault flow, change outputs are now verified cryptographically
  against the vault's own cosigner keys (`verifyVaultChangeOutput`) and
  only then labeled "Change back to this vault (verified)"; unverified
  lookalikes are flagged as NOT verified.

### Fixed (security — independent re-review)

A second review pass re-derived every finding above from the code rather
than trusting the first pass's report, and found five more it had missed.
Each was reproduced against the pre-fix code before a fix was written. See
[`docs/SECURITY-AUDIT-V0.1.9.md`](docs/SECURITY-AUDIT-V0.1.9.md).

- **A decoy PSBT key could supply the unsigned transaction.** BIP174 defines
  `PSBT_GLOBAL_UNSIGNED_TX` as the type byte alone, but the duplicate-key
  rule compares *full* keys — so `00` and `00 aa` counted as different keys
  and a file could carry both. Parsing resolved to whichever came first
  while Bitcoin Core rejects such a file outright, meaning MEGA could review
  and sign a transaction a strict peer refuses to read. The same shape
  applied to every un-keyed type an accessor resolves with
  `find { keyType == N }` (`witness_utxo`, `sighash_type`, `witness_script`,
  `final_scriptWitness`). Parsing now requires exactly one global
  unsigned-transaction key and rejects keydata on any type BIP174 defines
  as un-keyed.
- **Trailing bytes after the final output map were silently ignored**, so
  the reviewed-and-signed PSBT could be a mere prefix of the scanned bytes.
  Now rejected.
- **Non-minimal compact-size varints were accepted**, giving the same
  logical content multiple valid encodings; and the 8-byte form could read
  back as a negative Kotlin `Long`, slipping past every `offset + len > size`
  bounds check. Both now rejected.
- `signPsbt`'s already-signed-pubkey guard is now updated as it signs, so it
  no longer depends on the parser's duplicate-key rule to avoid emitting two
  `partial_sig` entries with identical keys (defence in depth).

One concern raised during this pass — that the PIN entry/setup screens lack
`FLAG_SECURE` — was investigated and **rejected**: both delegate to
`PinEntryScreen`, which applies it. The audit report records the rejection
alongside the confirmed findings.

### Added / Changed (hardening)
- Transaction review additions: per-input sighash surfacing, blocking on
  unsupported sighash or negative fee, a >10%-of-inputs high-fee warning,
  and network inference from derivation-path coin types (labeled as
  inferred) so the single-seed flow shows bech32 addresses instead of
  raw hex when paths permit.
- Removed the app's only `Log` call (PDF export failure path).
- New regression suites: `PsbtCanonicalEncodingTest` (12 tests) for the
  canonical-encoding fixes above, and `PsbtFinalizationOrderingTest`
  (5 tests) pinning OP_CHECKMULTISIG's positional signature ordering and
  exact-threshold selection, which were correct but previously untested.

### Fixed (security — signature and amount hardening)

A further pass over PSBT amount handling and the finalization boundary,
closing gaps the audits above didn't reach: amounts were range-checked at
parse time but not everywhere they're later combined, and finalization
counted signatures without ever verifying them.

- **finalizePsbt trusted every `partial_sig` it saw.** Any bytes attached to
  a matching pubkey counted toward a multisig threshold and could be
  emitted straight into `finalScriptWitness`, including malformed DER,
  high-S, wrong-pubkey, or wrong-sighash-byte signatures — a corrupted or
  hostile PSBT could make the app "finalize" a transaction with a witness
  that would never actually validate on the network. Every candidate
  signature is now cryptographically verified (correct DER, low-S, correct
  pubkey, correct sighash) before it counts toward a threshold or is
  written into a witness; multisig ordering, exact-threshold selection, and
  P2WPKH UTXO-pubkey binding are unchanged. The transaction-summary
  "will this finalize" prediction now shares this same verification path,
  so the review screen can no longer suggest a PSBT is ready to broadcast
  based on a signature count alone.
- **Satoshi amounts weren't bounded everywhere they're read or combined.**
  Transaction-output and PSBT `witness_utxo` amounts are now checked
  against Bitcoin's `MAX_MONEY` bound (0 to 21,000,000 BTC in satoshis) at
  parse time — including the case where an attacker-controlled 8-byte
  little-endian field's high bit reads back as a negative `Long` — and
  totals/fees are computed with overflow-checked addition/subtraction that
  fails closed instead of wrapping.
- **`witness_utxo`'s value wasn't required to be consumed exactly** —
  trailing bytes after its script were silently ignored rather than
  rejected. **`PSBT_IN_SIGHASH_TYPE` accepted any length ≥ 4** instead of
  requiring exactly 4 bytes.
- **A truncated BBQr 'Z' (deflate) stream could return partial bytes as a
  successful decode.** Decompression now only succeeds once the underlying
  inflater reports the stream actually finished; a truncated or corrupted
  compressed payload is now rejected instead of silently handing back
  whatever prefix happened to decode. The existing 8 MB output cap is
  unchanged.
- New/expanded regression suites covering every rejection path above:
  `TransactionAmountValidationTest`, `PsbtWitnessUtxoAndSighashHardeningTest`,
  `PsbtFinalizationSignatureVerificationTest`, plus new truncated/corrupted-
  stream cases in `BbqrHardeningTest` and a high-S case in
  `EcdsaSigningTest`.

## [0.1.9] — 2026-08-13

Driven by real multisig testing against Sparrow Wallet.

### Fixed
- **BBQr export rejected by Sparrow** ("Invalid input length 150"): the
  BBQr spec requires every individual QR frame's Base32 chunk — not just
  the full reassembled payload — to independently decode to a whole
  number of bytes. MEGA's animated-QR export chunked at a fixed
  150-character part size (150 mod 8 = 6, not a valid "complete bytes"
  length), which round-tripped fine through MEGA's own decoder (which
  used to concatenate every frame's text before decoding, masking the
  problem) but was rejected outright by Sparrow's stricter, spec-
  compliant per-frame decoding. `encodeBbqr`'s default part size is now
  152 (a multiple of 8) and it rejects any non-multiple-of-8 size by
  construction; the decoder side now also decodes each frame
  independently before concatenating, matching the spec and catching a
  malformed chunk immediately instead of only after full reassembly.
  Verified against an independently-written reference Base32 decoder
  applying Sparrow's exact strict validation rule, not just MEGA's own.

### Added
- **Transaction review before signing**: every PSBT-signing flow (single-
  seed and saved-vault) now shows a full review screen — network, input/
  output counts and amounts, destination addresses, change-output
  identification, total input/output amounts, fee and estimated fee
  rate, existing-signature count, required threshold, whether this
  device can sign, and whether signing will finalize the transaction —
  with an explicit "Confirm and Sign" action required before anything is
  signed. Scanning a PSBT no longer signs it immediately; Cancel or Back
  at any point discards the scanned PSBT without signing. Any value that
  can't be reliably determined is shown as "Unknown" rather than
  guessed. New `computePsbtSummary` (entropy-core) never derives or
  touches a private key — only compares an already-known fingerprint
  string against fingerprints already embedded in the PSBT.
- **Sign PSBT for a saved multisig vault**: a saved vault's detail screen
  now has a "Sign PSBT" action. Since a saved vault stores only public
  cosigner data (fingerprint/path/xpub — never a link to a local seed),
  the flow makes the user pick which cosigner slot this device
  represents, pick a candidate saved session, and verifies — fail
  closed — that the session's own BIP32 master fingerprint actually
  matches the claimed cosigner's stored fingerprint before ever
  reaching the PSBT scanner (`entropy-core`'s new `signPsbtForCosigner`
  re-checks the same fingerprint again immediately before signing, as
  defense in depth). A mismatch that actually belongs to a *different*
  cosigner in the same vault is called out by name rather than shown as
  a generic failure. Reuses the existing PSBT scanner and sign-result
  screens unchanged.
- **Sign PSBT**: Advanced Mode can now sign a PSBT (Partially Signed
  Bitcoin Transaction) with the loaded seed. Scan a PSBT as a single
  base64 QR or an animated BBQr series, and MEGA signs every input it
  holds a key for (BIP174 parsing, RFC6979-deterministic ECDSA, BIP143
  segwit sighash, PSBT finalization — all in `entropy-core`). A fully
  signed transaction is shown as hex plus a broadcast-ready animated QR;
  a still-partially-signed multisig PSBT is re-exported the same way to
  hand off to the next cosigner.
- **BBQr animated QR support**: the multisig scanner now decodes BBQr —
  the `B$<encoding><type><total><index>` header Sparrow (and other
  Bitcoin tools) use to spread a larger export, such as a full multisig
  descriptor, across a series of QR codes. Hand-implemented Base32
  (RFC4648) and raw-Zlib-inflate (wbits=10) decoding in
  `entropy-core/.../Bbqr.kt`, verified against real vectors generated
  with Python's own zlib/base64. The scanner accumulates parts with a
  "Scanned X of Y" progress indicator until the full set is read.
- **SeedQR import resumed**: "Import via SeedQR" (Standard and Compact,
  SeedSigner's format, also read by Sparrow) is back in Advanced Mode —
  shelved on 2026-08-10 after its original scan screen failed to decode
  reliably and crashed on the back button. Rebuilt on the same camera
  plumbing since proven out by the multisig scanner, rather than
  debugging the original separate implementation.
- **BIP-380 descriptor checksums**: MEGA's own generated descriptors now
  carry a correct trailing `#CHECKSUM` (`buildMultisigWallet`), so they
  round-trip cleanly into Sparrow, Bitcoin Core, and other BIP-380 tools.
  The checksum algorithm is a direct port of Bitcoin Core's own,
  verified byte-for-byte against its reference test vectors.
- **Descriptor-import confirmation dialog**: scanning or pasting a full
  descriptor that would overwrite cosigner slots you've already filled
  now stages the import behind an explicit confirm/cancel step, instead
  of silently replacing a (possibly already-verified) cosigner set.
- **Per-field cosigner editing**: every filled cosigner slot has three
  independent pencil icons — label, master fingerprint, and derivation
  path — each correctable after the fact without re-entering the whole
  cosigner.
- **Home button** on the finished-vault screen, shown only once the
  vault has actually been saved, jumping straight back to Advanced Mode
  instead of stepping back through Slots/Policy one screen at a time.
- Local/USB **PDF save** for a multisig vault, hinting
  `Intent.EXTRA_LOCAL_ONLY` to steer the system picker away from
  cloud-storage destinations.

### Changed
- "Complete Cosigner Info" (completing a bare xpub scanned or pasted
  without its own `[fingerprint/path]` origin) no longer asks for an
  account index or custom derivation path up front — the exporting
  wallet already fixed that when it produced the key, so re-typing it
  was friction for the common case. The cosigner completes at the
  vault's standard account-0 BIP48 path; a genuinely non-default path
  is set afterward via its own pencil icon.
- That same dialog now requires a **label** instead of auto-generating
  one from the fingerprint and path — the auto-generated label used to
  go stale the moment either value was corrected afterward.
- A bare-xpub cosigner's master fingerprint is no longer required up
  front either — it defaults to the `00000000` unknown-origin
  placeholder (the same convention Sparrow uses when it doesn't know
  the real one), editable later via pencil. Confirmed via research that
  an all-zero fingerprint doesn't affect a descriptor's spendability or
  recoverability, only automatic signer-matching convenience.
- `parseMultisigDescriptor` now accepts (and verifies) an optional
  trailing BIP-380 checksum instead of rejecting any descriptor that has
  one — Sparrow includes one by default on export.

### Fixed
- Saved-session auto-lock: completing PIN setup or "Change PIN" could
  leave the unlock timestamp unset, causing an immediate spurious
  re-lock right after setting/changing a PIN. Lock-timing logic was
  extracted into pure, unit-tested functions
  (`SavedSessionLockDecisions.kt`) as part of the fix.
- Multisig descriptor/fragment parsing now rejects pathologically long
  input (over 8000 characters) before running any regex against it.
