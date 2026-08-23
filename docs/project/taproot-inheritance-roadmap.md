# Taproot / Inheritance Roadmap

Tracks the gates defined in the project charter. A gate does not advance with
failing tests. Each gate's "Exit criteria" must be independently verified
(agent + Claude review, per the delegation rules), not just "compiles."

## Gate 0 — Current-state architecture understood ✅ (this session)
- `docs/architecture/current-state.md`, `docs/security/changes-since-v0.1.09.md`
  written and verified against the actual repository (not assumed from memory).
- Baseline `./gradlew check` green at `feature/taproot-inheritance` @ `7b6fbc9`.
- One real security-invariant violation found and fixed during this pass
  (SecureRandom leaking into `:entropy-core`) — see agent-worklog.md.
- One real, live threat-model gap found (PSBT unknown-field pass-through,
  pre-dates Taproot) — tracked as an early hardening task, see below.
- **Exit criteria met.**

## Gate 0.5 — PSBT field allowlisting — SKIPPED by explicit user decision
User reviewed this finding and decided the current pass-through behavior is
fine as-is; not doing this. Left in the roadmap only as a record of the
decision, not as future work.

## Gate 1 — Taproot key-path signing complete ✅ (commits `be5f500`, `84195b5`)
- BIP340 Schnorr (`Schnorr.kt`): tagged_hash, lift_x, has_even_y, x-only
  PubKey/sign/verify. **All 19 official BIP340 test vectors pass**
  (`Bip340OfficialVectorsTest.kt`, generated programmatically from the CSV
  fetched directly from bitcoin/bips — never hand-transcribed).
- BIP341 TapTweak (`TapTweak.kt`): output-key tweaking (`tweakPubKey`) and its
  private-key counterpart (`tweakPrivateKey`, needed to actually sign a
  key-path spend). **All 7 key-path-spending cases in BIP341's own
  `wallet-test-vectors.json` pass**, end-to-end through Schnorr sign+verify
  under the tweaked key, plus a full internal-key→bech32m-address check
  against BIP341's worked example (`TapTweakVectorsTest.kt`,
  `TaprootAddressEndToEndTest.kt`).
- BIP350 Bech32m / segwit v1+ addresses (`Bech32.kt` extended):
  `encodeSegwitAddress`/`decodeSegwitAddress`/`encodeTaprootAddress`,
  general witness version 0–16 with the Bech32-vs-Bech32m matching rule.
  **All 8 valid + 15 invalid vectors from BIP350's own address test-vector
  list pass** (`Bip350SegwitAddressVectorsTest.kt`) — every documented
  failure mode (wrong checksum variant, mixed case, bad program length,
  malformed padding, invalid witness version) is covered.
- BIP86 wallet derivation (`Bip32.kt`/`WalletDerivation.kt`/
  `WalletAddresses.kt`): `TAPROOT` script type, standard xpub/tpub version
  bytes (verified directly against SLIP-132 — no dedicated Taproot prefix
  exists — and BIP86's own worked example), key-path-only P2TR address.
  `AdvancedModeWalletScreen.kt` needed **zero code changes** (already
  iterates `WalletScriptType.entries` generically) — just a stale doc
  comment fix. **Both official BIP86 test vectors pass end-to-end**
  (mnemonic → xpub/address, and the exported WIF verified to be the
  *tweaked* signing key, not the internal one).
- Exit criteria met: every claim above is checked against an official,
  independently-fetched (not delegated-summary) test vector, not just
  "compiles." `./gradlew check` green throughout.
- **Delegation note**: Agent A's implementation attempt for Schnorr.kt
  truncated after one paragraph (endpoint/response issue, not investigated
  further) — Claude implemented directly from the verified spec text instead
  rather than debugging the delegation pipeline mid-flight, given the
  correctness stakes. Agent B/C adversarial review of Gate 1 as a whole is
  still outstanding — recommended before Gate 2 sign-off, see agent-worklog.md.

## Gate 2 — Taproot PSBT complete
- BIP371 fields (§ project charter's list) added to Psbt.kt's accessors
  (no allowlist gate — Gate 0.5 was skipped by user decision, unknown
  fields continue to pass through as they already do for every other PSBT
  field type); PsbtFinalization.kt gains a Taproot key-path finalization
  branch (single-signature witness).
- **Explicit user requirement (2026-08-23), applies here same as ordinary
  signing**: an unrecorded/placeholder master fingerprint (`00000000`) in
  PSBT_IN_TAP_BIP32_DERIVATION must remain acceptable for single-seed
  Taproot signing, the same as the existing PSBT_BIP32_DERIVATION handling
  (`a7e78a0`, `bfd3d1c`) already does for non-Taproot inputs — most
  watch-only wallets don't record it. Do NOT require a verified fingerprint
  to sign; do surface "Unverified Master Fingerprint" the same way
  PsbtSignResultScreen already does for the non-Taproot case.
- Exit criteria: a hand-constructed (or vector-sourced) Taproot PSBT
  round-trips through parse → sign (key-path) → finalize → matches expected
  witness bytes.

## Gate 3 — Wallet Policy abstraction complete
- `OwnerPolicy`/`RecoveryPolicy`/`Participants`/`Threshold`/`RelativeDelay`/
  `OptionalRecoverySecret`/`PrivacyGroup`/`PolicyVersion`/`ScriptEngine`
  modeled, engine-agnostic (portable across P2WSH and P2TR engines).
- **Architecture decision required before this gate starts** (Claude decision,
  not delegated): does MEGA build a real (even minimal-subset) Miniscript
  compiler/satisfier, or hand-generate the specific fixed script shapes the
  beginner policy (`OWNER OR (TIME + PEOPLE + SECRET)`) actually needs? See
  current-state.md §4 — there is zero existing Miniscript infrastructure
  either way.

## Gate 4 — P2WSH inheritance prototype complete
- `wsh(...)` engine: owner branch (single sig) OR recovery branch
  (`older(delay)` + threshold sigs + optional sha256(secret) preimage).
- Exit criteria: owner spend works; recovery spend fails before delay
  (mempool/policy rejection, testable via regtest-style relative-locktime
  simulation since MEGA itself never touches a live chain); recovery spend
  succeeds after delay; wrong threshold/secret fails.

## Gate 5 — Taproot inheritance prototype complete
- `tr(OWNER_KEY, {RECOVERY_LEAVES})`, owner uses key-path, recovery uses
  script-path. Same functional exit criteria as Gate 4, plus: normal
  (key-path) spending reveals nothing about the recovery tree (privacy
  assertion — needs an explicit test that a key-path-spent transaction's
  witness contains no leaf/control-block data).

## Gate 6 — Online coordinator / watch-only specification complete
- A written spec (not necessarily an implementation this project must ship) —
  descriptor/watch-only data the coordinator holds, PSBT construction
  responsibilities, what it must never be able to request (owner/participant
  private keys, seed words).

## Gate 7 — Recovery workflow complete
- Heir-facing MEGA Recovery flow (Phase 4): import package → discover
  protected UTXOs → recovery status → participant coordination → construct →
  sign → (external) broadcast.

## Gate 8 — Security audit complete
- `docs/security/audit-post-v0.1.09.md` + the four threat-model docs (threat
  model, taproot, inheritance, airgap-exfiltration). No unresolved
  CRITICAL/HIGH findings. All three agents participate independently per the
  delegation rules; Claude does final review and sign-off.

## Gate 9 — Release candidate
- Full Phase 6 checklist (build/Taproot/inheritance/offline-workflow/recovery/
  security) all green.

---

## Sequencing note
Gates 1–2 (Taproot foundation) can proceed largely independent of Gates 3–5
(policy/inheritance) at the crypto-primitive level, but Gate 3's architecture
decision (Miniscript vs. hand-generated scripts) determines HOW Gates 4–5 are
built, so it should be resolved (Claude decision, informed by delegated
research) before Gate 4 implementation starts in earnest — research can start
in parallel with Gate 1/2 implementation.
