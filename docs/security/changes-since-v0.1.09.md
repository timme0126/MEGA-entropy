# Changes Since v0.1.9 (the previous audit's scope)

`docs/SECURITY-AUDIT-V0.1.9.md` covered everything between tags `v0.1.7` and
`v0.1.9`. This document scopes what changed from `v0.1.9` up to this Taproot/
inheritance project's baseline (`feature/taproot-inheritance` @ `7b6fbc9`), for
the new audit (Phase 5) to treat as in-scope, in addition to the new Taproot/
inheritance work itself.

33 commits, `v0.1.9..7b6fbc9` (chronological):

## Security-hardening commits (in the v0.1.9 audit's own remediation set)
These are the fixes that AUDIT-V0.1.9 itself produced, landing after the tag:
- `b2c6df7` Reject duplicate PSBT map keys and non-canonical unsigned transactions
- `e196fa7` Sign only SIGHASH_ALL and bind every signature to its spent UTXO
- `8e50baa` Finalize PSBT inputs only for fully understood, UTXO-bound scripts
- `df6d64b` Cap BBQr 'Z' inflate output at 8 MB (zip-bomb guard)
- `7fa47d2` Surface sighash types, inferred network, and negative fees in PSBT summaries
- `1bf5f3e` Verify multisig vault change outputs cryptographically
- `e994a15` Block signing on unsupported sighash or negative fee; verify change in review
- `fcd39cc` Detect conflicting BBQr frames instead of silently mixing series
- `2130520` Remove the app's only Log call
- `5276c92` Document the PSBT signing security model and v0.1.9 audit hardening
- `d13c700` Require canonical PSBT encoding: one un-keyed global tx, no trailing bytes
- `35a3acf` Pin OP_CHECKMULTISIG signature ordering and exact-threshold selection
- `f7477c5` Document the v0.1.7-to-current security audit delta
- `3a28e63` Harden PSBT amount validation and finalization signature verification

These are **already covered** by `docs/SECURITY-AUDIT-V0.1.9.md`'s own findings
table — re-verify they're still correctly in place (regression, not re-discovery)
rather than re-auditing from scratch.

## Feature work since v0.1.9 (NOT covered by any prior audit — new scope)
- `85b8211` Release MEGA v0.1.10
- `026f41a` Fix PSBT signing failure for non_witness_utxo with a SegWit ancestor tx
- `a7e78a0` Accept unrecorded (00000000) PSBT fingerprints for single-seed signing only
- `b39d964` Prevent PSBT signing UI hangs and harden RFC6979
- `089c3eb` Harden asynchronous PSBT signing result handling
- `f47ed88` Streamline PSBT signing result workflow
- `ff77774` Add optional Android security verification screen
- `9b51847` Refresh v0.1.10 beta APK hash
- `f4bc692` Add direct device settings actions to security checks
- `f3ff077` Refresh beta APK after settings actions
- `983dde7` Add Structure a Transaction: offline UTXO-split PSBT builder
- `19861cf` Rework Structure a Transaction to harvest inputs from a scanned PSBT
- `bfd3d1c` Accept unrecorded (00000000) origin fingerprints when harvesting inputs
- `f67c545` Fix Structure a Transaction: async build, no separate change address
- `9e3eec8` Add a toggle for where the remaining balance goes
- `dc4ad59` Fix hub return navigation, show change address, split into two buttons
- `4bbe52e` Require acknowledgment before scanning for Structure a Transaction
- `567808e` Release MEGA v0.1.11
- `a497fca` Add entropy-rooted Shamir backup shares and saved-item tags
- `7b6fbc9` Fix entropy-core securityAudit violation (see below)

## New audit surface this introduces

1. **Structure a Transaction** (`983dde7`..`4bbe52e`) — an entirely new PSBT
   *construction* path (harvests real inputs from a scanned externally-built
   PSBT, discards its outputs, rebuilds new ones from user-entered split
   parameters). This is new attack surface distinct from ordinary
   scan-and-sign: a malicious "source" PSBT could attempt to trick the harvest
   logic (`harvestOwnedInputsForStructuring`) into misattributing an input's
   ownership, or a malicious split configuration could construct outputs that
   don't match what the review screen shows. Needs its own audit pass, not
   just a diff against the ordinary signing flow's already-audited logic.
2. **Accepting "unrecorded" (`00000000`) PSBT/origin fingerprints**
   (`a7e78a0`, `bfd3d1c`) — a deliberate relaxation of fingerprint
   verification for a specific case (single-seed signing / structuring
   without a recorded origin fingerprint). This is exactly the kind of
   "verified vs. unverified" distinction the project's coordinator-compromise
   threat model cares about — confirm the UI actually surfaces "Unverified
   Master Fingerprint" in both new code paths the same way `PsbtSignResultScreen`
   does for plain signing (per that screen's own established notice), and audit
   whether either relaxation could be abused to spoof ownership of an input
   that isn't actually this device's.
3. **Android security verification screen** (`ff77774`, `f4bc692`) — new
   device-settings-inspection surface; confirm it only reads settings state
   (no new permission, no new attack surface) and that "direct device settings
   actions" doesn't imply this app can silently change device configuration.
4. **Backup shares + tags** (`a497fca`, `7b6fbc9`) — new cryptographic feature
   (Shamir's Secret Sharing over the secp256k1 scalar field) added THIS
   session, entirely unaudited beyond the unit tests written alongside it and
   the `securityAudit`-violation fix in `7b6fbc9`. Specific things the Phase 5
   audit must check independently (not just "tests pass"):
   - Correctness of the Lagrange interpolation and the integrity-tag scheme
     against an independent re-derivation, not just this session's own tests.
   - Whether the "one share revealed at a time, no bundled export/QR" UI
     actually prevents all shares from co-existing in memory/screen state
     longer than necessary (the ViewModel's `shares` field does hold the full
     generated set between reveals — documented as intentional, but worth an
     adversarial second look at exactly how long it lives and whether it's
     cleared reliably on process death / backgrounding).
   - Whether `Secp256k1.N`-bound rejection (secret must be `< N`) is the right
     boundary check, and whether the "regenerate and retry" failure path
     (vanishingly rare, ~2^-128) is even reachable/testable in practice.
5. **`7b6fbc9` itself** — the fix for the `SecureRandom`-in-entropy-core
   violation caught during this Phase 0 pass (see
   `docs/architecture/current-state.md` §2 and §10, and
   `docs/project/agent-worklog.md`). Worth an explicit audit note: this was
   caught by running the full `check` task, not `test` — confirm CI (if any)
   and any pre-commit/pre-release process actually runs `check`, not just
   `test`, so a similar violation can't ship silently again.

## Not yet touched (still v0.1.9-era, unaffected by the above)
Entropy generation, BIP32/BIP39 derivation, session storage/encryption, PIN
handling, ordinary (non-structuring) PSBT scan-and-sign for P2WPKH/bare
P2WSH-multisig, and the saved multisig vault flow are all structurally
unchanged since the v0.1.9 audit's own hardening commits landed — no new
regression risk there beyond re-confirming those fixes still hold (see the
first commit list above).
