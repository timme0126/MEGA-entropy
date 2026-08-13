# Changelog

Notable changes to MEGA, most recent first. The current beta build
remains `v0.1.8` (see [`README.md`](README.md#download-the-beta-apk)) —
everything below is unreleased on top of it, not yet in a tagged build.

## [Unreleased] — 2026-08-13

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
