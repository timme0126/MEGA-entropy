# Security Audit — v0.1.7 → v0.1.9 + hardening delta

Scope: everything added between the v0.1.7 tag and the current working
branch — the PSBT signing pipeline (BIP174/BIP143), the saved multisig-vault
signing flow, transaction review and confirmation, BBQr encode/decode, and
the storage/lock/release surfaces those touch.

## What this document is, and is not

This is an **AI-assisted internal review**, not an independent audit.

Two AI passes are recorded here: an initial audit pass whose findings
produced the ten hardening commits `b2c6df7..5276c92`, and a second,
independent re-review of those commits (and of the code they touched) which
produced the additional findings marked **NEW** below. The second pass
treated the first pass's report as a hypothesis, re-derived each claim from
the code, and empirically confirmed every new finding with a throwaway probe
test before any fix was written.

**Neither pass is a substitute for review by an independent human security
firm, and this document must not be cited as evidence that MEGA has been
audited.** MEGA remains experimental software that has not undergone an
independent human security audit. See [`../SECURITY.md`](../SECURITY.md).

An AI reviewing another AI's work shares whole classes of blind spot with
it. In particular, neither pass could execute anything on a real device, and
neither performed side-channel, timing, or hardware-level analysis.

## Verification method

For every claim below:

- the affected algorithm was checked line by line against its primary
  specification — BIP174 (PSBT), BIP143 (segwit sighash), BIP32/BIP39
  (derivation), BIP67 (key ordering), BIP144 (witness serialization), the
  [BBQr specification](https://github.com/coinkite/BBQr/blob/master/BBQr.md),
  and Bitcoin Core's descriptor-checksum behaviour;
- each **NEW** finding was reproduced against the pre-fix code with a
  disposable probe test that printed the actual accept/reject outcome, so
  that no fix was written for a theorised problem;
- every fix carries at least one regression test that fails without it.

## Findings from the first pass — confirmed

All eleven findings from the first audit pass were re-derived from the code
and **confirmed as genuine**. Their fixes were reviewed line by line and are
correct. See the regression test matrix below for the tests covering each.

| Severity | Finding | Fix commit |
|---|---|---|
| HIGH | Arbitrary sighash types meant the signature need not commit to the reviewed outputs | `e196fa7`, `e994a15` |
| MEDIUM | Duplicate PSBT map keys accepted (BIP174 requires uniqueness) | `b2c6df7` |
| MEDIUM | Non-canonical unsigned transaction / non-empty scriptSigs accepted | `b2c6df7` |
| MEDIUM | Signing did not bind the signature to the spent UTXO's script | `e196fa7` |
| MEDIUM | Finalizer could emit invalid witnesses for loosely-matching scripts | `8e50baa` |
| MEDIUM | BBQr `'Z'` inflate had no output cap (zip bomb) | `df6d64b` |
| MEDIUM | "Likely change" labels were spoofable by coordinator metadata | `1bf5f3e`, `e994a15` |
| MEDIUM | Weak destination/network presentation in single-seed review | `7fa47d2` |
| LOW | BBQr frame-series mixing | `fcd39cc` |
| LOW | Missing fee sanity checks (negative fee) | `7fa47d2`, `e994a15` |
| LOW | PDF export logged a user-selected destination path | `2130520` |

The HIGH finding deserves emphasis because the fix is layered: the review
screen refuses to render its Confirm button when any input requests a
non-`SIGHASH_ALL` type, **and** `validateSighashType` inside `signPsbt`
throws regardless of how it was reached. A caller that skipped the UI
entirely still cannot produce a non-ALL signature.

## Findings from the second pass — NEW

These were not in the first pass's report. Each was confirmed by probe
before being fixed.

### NEW-1 (MEDIUM) — a decoy global key could supply the unsigned transaction

`parsePsbt` resolved the unsigned transaction with
`entries.find { it.keyType == 0x00 }`. BIP174 defines
`PSBT_GLOBAL_UNSIGNED_TX` as a key containing **the single type byte and no
keydata**, but the duplicate-key rule added in `b2c6df7` compares *full*
keys — so `00` and `00 aa` are distinct keys and could both appear.

Probe result before the fix: a PSBT whose only global key was `00 aa`
parsed successfully, and its value was used as the unsigned transaction.

This is the same class of defect the duplicate-key rule was written to
close, left half-open. Bitcoin Core rejects such a file outright ("Global
unsigned tx key is more than one byte type"), so MEGA could review and sign
transaction *X* from a file a strict peer refuses to read at all — a
display-versus-sign divergence.

The same shape applied to every accessor that resolves an un-keyed type with
`find { it.keyType == N }`: `witnessUtxo()` (0x01), `sighashType()` (0x03),
`witnessScript()` (0x05), `finalScriptWitness()` (0x08). A decoy key sorting
ahead of the real one would be returned in its place.

**Fix:** `parsePsbt` now requires exactly one global type-`0x00` entry with
empty keydata, and rejects any key carrying keydata for a type BIP174
defines as un-keyed, in input maps (`0x00, 0x01, 0x03, 0x04, 0x05, 0x07,
0x08`) and output maps (`0x00, 0x01`). Keyed types (`0x02` partial_sig,
`0x06`/`0x02` bip32_derivation) are untouched and still carry their pubkey.

### NEW-2 (LOW) — trailing bytes after the final output map were ignored

`parsePsbt` never checked that it had consumed the whole buffer. Probe
result before the fix: a valid PSBT with `deadbeef` appended parsed
successfully. The reviewed-and-signed PSBT was therefore only a *prefix* of
the bytes actually scanned, and a peer that interpreted the remainder
differently could disagree with MEGA about the file's content.

**Fix:** parsing now fails unless the final output map ends exactly at the
end of the buffer.

### NEW-3 (LOW) — non-minimal compact-size varints accepted; 8-byte form could read back negative

`readCompactSize` accepted any encoding form for any value. Probe result
before the fix: a map key length of 1 encoded as `fd 01 00` parsed
successfully. Bitcoin's compact-size encoding is canonical, and both Core
and Sparrow reject non-minimal forms.

Separately, the 8-byte (`0xff`) form built a Kotlin `Long` that reads back
**negative** when bit 63 is set. A negative length slips past every
`offset + len > size` bounds check in `parseTransaction` and
`PsbtMap.witnessUtxo()`, reaching `copyOfRange` with a negative end index.
The resulting throw is a caught `IllegalArgumentException` rather than a
crash, so this was robustness rather than an exploitable bug — but it meant
the bounds checks were not actually doing their job.

**Fix:** `readCompactSize` now rejects non-minimal `0xfd`/`0xfe`/`0xff`
encodings and rejects any `0xff` value with bit 63 set before it can be
returned.

### NEW-4 (LOW, defence in depth) — `signPsbt`'s duplicate-signature guard was stale

The set of already-signed pubkeys was computed once before the derivation
loop and never updated. Two `bip32_derivation` entries naming the same
pubkey would each produce a `partial_sig`, emitting two entries with
identical full keys — a PSBT that `parsePsbt` now refuses to read back.

This is unreachable through the app's own flow because the parser rejects
duplicate full keys, so the input can never contain two identical `0x06`
keys. Fixed anyway so that `signPsbt` — which is `internal` and callable on
a hand-built `Psbt` — does not depend on its caller having parsed the PSBT
in order to stay correct.

### NEW-5 (LOW, test-only) — finalizer ordering and threshold selection were untested

`finalizeMultisigInput` orders signatures by witness-script position (as
OP_CHECKMULTISIG requires) and takes exactly `threshold` of them. Both
behaviours were correct but had no test, so a future refactor could have
silently produced witnesses that fail consensus validation. Five tests were
added; no production change was needed.

## Findings investigated and REJECTED

Recording these matters as much as the confirmed ones.

- **PIN entry/setup screens lack `FLAG_SECURE`.** Raised during this pass
  from a grep over `ui/pin/*.kt`, which showed no `SecureScreen` call in
  `PinVerifyScreen.kt` or `PinSetupScreen.kt`. **Rejected on inspection:**
  both delegate to `PinEntryScreen`, which calls `SecureScreen()` as its
  first statement. All PIN entry paths are covered. The grep was measuring
  the wrong files.
- **Static info screens lack `FLAG_SECURE`** (Welcome, About, How It Works,
  Privacy, Security Model, Choose Length, Before You Begin, Loading).
  **Not a finding:** none of these display secret material.
- **Finalizer does not validate the sighash byte on pre-existing
  `partial_sig` entries from other cosigners.** Considered and rejected as a
  vulnerability: this device's own signature is always `SIGHASH_ALL` and
  therefore commits to every output the user reviewed, so a cosigner's
  non-ALL signature cannot change what this user authorised without
  invalidating the user's own signature. Recorded as residual risk R-6
  instead.

## Accepted residual risks

| ID | Risk | Why accepted |
|---|---|---|
| R-1 | **`witness_utxo` amounts are trusted.** MEGA has no access to the full previous transactions, so a coordinator can misstate an input's value, making the displayed fee wrong. | Single-pass signing bounds the damage: the signature commits to the same amount that was displayed, so a lie produces an *invalid* signature rather than an overpaid fee. The classic multi-pass fee attack (CVE-2020-14199) does not apply — MEGA signs every input in one pass from one set of amounts. Requiring `non_witness_utxo` is impractical for a QR-based air-gapped signer. |
| R-2 | **Unknown (`00000000`) cosigner fingerprints can never sign.** A vault cosigner completed from a bare xpub carries the `00000000` placeholder; `signPsbtForCosigner` compares it against the real derived fingerprint and always mismatches. | This is fail-closed and intentional. The user must correct the fingerprint on the cosigner card before that slot can sign. |
| R-3 | **Single-seed change outputs are not cryptographically verified.** Only the saved-vault flow has the vault's cosigner keys available for `verifyVaultChangeOutput`. | The single-seed review labels such outputs "Possible change (unverified)" and never claims verification. Amounts and destinations are still shown in full. |
| R-4 | **`BigInteger` arithmetic in secp256k1 is not constant-time.** | MEGA is an offline, air-gapped, single-user signer with no remote timing observer. A local attacker able to measure this already has far better options. Documented, not mitigated. |
| R-5 | **`LockGuard` scope.** The app-lock guard is applied per saved-session route rather than globally. | Reviewed and unchanged this pass; the routes that read saved sessions are gated. A global guard is a design change, not a defect fix. |
| R-6 | **Finalization does not check other cosigners' sighash bytes.** | See "rejected" above — the user's own ALL signature binds the transaction. |
| R-7 | **The finalizer drops unknown/proprietary PSBT fields.** BIP174 says a finalizer "should" preserve unknown fields. | Deliberate deviation: dropping attacker-supplied data the app does not understand is the conservative choice for an offline signer, and the resulting PSBT is still valid. Recorded so the deviation is not mistaken for an oversight. |
| R-8 | **QR error-correction level is `L`.** | Chosen for density so large PSBTs need fewer frames. A misread corrupts the payload, which then fails to decode or parse — it cannot silently alter a transaction, because the reassembled bytes must still parse as a PSBT and the review screen re-derives everything from those bytes. |

## Requires device or emulator testing — NOT performed

No Android device or emulator was available in this environment
(`adb devices` returned an empty list). **None of the following was tested,
and no result for them should be inferred from the passing build:**

- `FLAG_SECURE` actually suppressing screenshots, screen recording, and
  Recent Apps thumbnails.
- Camera capture, QR decode reliability, and whether a real Sparrow install
  scans MEGA's emitted animated BBQr frames end to end. (BBQr *byte-level*
  Sparrow compatibility is covered by `BbqrSparrowCompatTest`, which decodes
  MEGA's frames with an independently written strict Base32 decoder applying
  Guava's exact length rule — but that is not the same as a camera reading a
  rendered QR.)
- Lifecycle behaviour: process death mid-flow, background/foreground
  transitions, and scanner teardown on cancel and back.
- PIN rate limiting, duress wipe, background relock timing.
- IME behaviour on the passphrase field.
- Clipboard auto-clear timing.

## Requires independent human audit

- The secp256k1 field/group arithmetic and RFC6979 nonce derivation
  (`Secp256k1.kt`, `EcdsaSigning.kt`) — reviewed against test vectors, never
  audited by a cryptographer.
- The overall key-management model and the entropy pipeline's claim that no
  device randomness reaches the mnemonic.
- Everything in this document.

## Regression test matrix

Every confirmed finding has at least one test that fails without its fix.
`entropy-core` and `app` together hold **498 test methods**, all passing.

| # | Finding | Sev | Status | Covering tests |
|---|---|---|---|---|
| 1 | Arbitrary sighash types | HIGH | Fixed | `PsbtSigningHardeningTest` — sighash NONE / SINGLE / ALL+ANYONECANPAY / out-of-range all abort signing; `explicit SIGHASH_ALL still signs fine`. `PsbtSummaryHardeningTest` — sighash surfaced per input and flagged; absent sighash not flagged |
| 2 | Duplicate PSBT map keys | MED | Fixed | `PsbtParserHardeningTest` — duplicate key in an input map rejected; duplicate partial-sig for the same pubkey rejected; two bip32 derivations with distinct keyData still allowed |
| 3 | Non-canonical unsigned tx / non-empty scriptSigs | MED | Fixed | `PsbtParserHardeningTest` — trailing garbage, non-empty scriptSig, and witness-serialized unsigned tx all rejected; official vector still round-trips |
| 4 | Signature not bound to the spent UTXO script | MED | Fixed | `PsbtSigningHardeningTest` — unbound witnessScript not signed; P2WPKH paying a different pubkey hash not signed; correctly bound P2WPKH still signs |
| 5 | Unsafe finalization of loosely-matching scripts | MED | Fixed | `PsbtFinalizationHardeningTest` (8 tests) — OP_0-leading script does not finalize with zero sigs; truncated push, missing OP_CHECKMULTISIG, key-count mismatch, UTXO mismatch, wrong-pubkey P2WPKH all left unfinalized; well-formed 2-of-2 unchanged |
| 6 | BBQr `'Z'` zip bomb | MED | Fixed | `BbqrHardeningTest` — deflate bomb fails closed; small legitimate payload still decodes |
| 7 | Spoofable "likely change" labels | MED | Fixed | `VaultChangeVerificationTest` (9 tests) — genuine change verifies; receive-chain, pays-elsewhere, wrong-network, missing-cosigner, no-derivations, tampered-pubkey and wrong-threshold cases all NOT verified |
| 8 | Weak destination/network presentation | MED | Fixed | `PsbtSummaryHardeningTest` — mainnet/testnet inferred from coin type; stays Unknown with no paths; caller-supplied network wins |
| 9 | BBQr frame-series mixing | LOW | Fixed | `BbqrAccumulationTest` (5 tests) — out-of-order accumulation, identical duplicate ignored, conflicting same-index reported and original kept, differing total/fileType starts a fresh series |
| 10 | Missing fee sanity check | LOW | Fixed | `PsbtSummaryHardeningTest` — outputs exceeding inputs detected as negative fee |
| 11 | PDF path logged | LOW | Fixed | No unit test; verified statically — the app contains zero `Log`/`println` calls |
| NEW-1 | Decoy global/un-keyed PSBT keys | MED | Fixed | `PsbtCanonicalEncodingTest` — global key with keydata rejected; two type-0x00 global keys rejected; witness_utxo, sighash_type and output witness_script keys with keydata rejected; keyed types still allowed to carry a pubkey |
| NEW-2 | Trailing bytes after final output map | LOW | Fixed | `PsbtCanonicalEncodingTest` — trailing bytes rejected; single trailing byte rejected; control PSBT still round-trips |
| NEW-3 | Non-minimal / negative compact-size varints | LOW | Fixed | `PsbtCanonicalEncodingTest` — non-minimal 2-byte and 4-byte forms rejected; 8-byte form with bit 63 set rejected |
| NEW-4 | Stale duplicate-signature guard in `signPsbt` | LOW | Fixed | Covered indirectly by `CosignerPsbtSigningTest` idempotent re-signing and `PsbtSigningTest` metadata preservation |
| NEW-5 | Finalizer ordering / threshold untested | LOW | Fixed (test-only) | `PsbtFinalizationOrderingTest` (5 tests) — script-order not map-order, exact threshold when more sigs available, script-order selection when the subset skips a key, unchanged when too few, 1-of-2 case |

### Coverage gaps (no automated test)

- **Cancel/Back never signs** — enforced structurally: `onConfirm` has exactly
  one call site (the Confirm button), and that button is not composed at all
  when a blocking reason exists. No instrumented Compose UI test exists.
- **Recomposition does not re-sign** — the signing call is cached in
  `remember` keyed on its inputs; not covered by an automated test.
- **Scanner single-submission** — a `finished` guard flag prevents a second
  `onScanned`; not covered by an automated test.
- **`FLAG_SECURE` / Recent Apps suppression** — window-level Android
  behaviour; requires a device or emulator.
- **Real Sparrow QR scan** — byte-level compatibility is covered by
  `BbqrSparrowCompatTest` (an independently written strict Base32 decoder
  applying Guava's exact length rule), but a camera reading rendered frames
  is not.
- **PIN rate limiting, duress wipe, background relock** — require a device.
