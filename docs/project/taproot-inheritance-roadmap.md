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

## Gate 0.5 — PSBT field allowlisting (NEW, inserted ahead of Gate 1)
Not in the original gate list, but the Phase 0 finding (current-state.md §3)
means this should land BEFORE Taproot PSBT work, not after: BIP371 adds new
field types on top of a parser that currently has no concept of "known vs.
unknown" beyond individual accessor functions ignoring what they don't look
for. Building the allowlist mechanism once, generically, means BIP371 fields
get added to an allowlist rather than the allowlist being retrofitted around
them later.
- Exit criteria: `serializePsbt` (or a new explicit "export" step) strips or
  rejects (project decision — likely reject, fail closed, per the "signer
  verifies" principle) any key-value entry whose keyType is not in an
  explicit known-fields set for that map (global/input/output), with a test
  proving a synthetic unknown field never survives round-trip through
  MEGA's signing pipeline.

## Gate 1 — Taproot key-path signing complete
- BIP340 Schnorr sign/verify, tagged hashes, x-only pubkey handling, `lift_x`,
  BIP341 output-key tweaking, Bech32m / segwit-v1 addresses, BIP86 derivation.
- Exit criteria: official BIP340 test vectors pass; TapTweak reference vectors
  (if published alongside BIP341) pass; address round-trip (encode/decode) for
  known testnet/mainnet P2TR addresses; Agent A implements, Agent B reviews,
  Agent C attempts to break (wrong tweak, wrong parity, invalid x-only key),
  Claude approves before merge.

## Gate 2 — Taproot PSBT complete
- BIP371 fields (§ project charter's list) added to Psbt.kt's allowlist
  (Gate 0.5) and accessors; PsbtFinalization.kt gains a Taproot key-path
  finalization branch (single-signature witness).
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
