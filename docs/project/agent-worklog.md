# Agent Worklog

Every delegated task, in order. Format per task: task, assigned agent, result,
review agent (if any), Claude decision, code affected, tests added.

---

## Task 1 — Endpoint verification
- **Task:** Confirm all three local agent endpoints are reachable and identify
  their served model.
- **Method:** `curl http://<ip>:8000/v1/models` from OpenClaw (192.168.12.26),
  the only host on the same subnet as the three agent boxes.
- **Result:** All three reachable.
  - Agent A `192.168.12.5:8000` → `qwen3.6-35b` (sglang backend)
  - Agent B `192.168.12.6:8000` → `qwen3.6-35b` (sglang backend)
  - Agent C `192.168.12.7:8000` → `qwen3.8-27b` (vllm backend,
    `unsloth/Qwen3.8-27B-NVFP4`)
- **Claude decision:** Proceed with delegation via raw `/v1/chat/completions`
  calls (no agentic/tool-use framework available on these endpoints — every
  prompt must carry its own file context, pasted in by Claude; agents have no
  filesystem access of their own).
- Delegation mechanism written: `/tmp/query_agent.py` on OpenClaw (posts one
  large user-message prompt, writes the response to a file). Not committed to
  the repo (it's an orchestration script for this session, not project code) —
  reconsider committing it under `scripts/` if delegation continues across
  sessions.

## Task 2 — Secp256k1/Bip32/Bech32 Taproot-readiness review
- **Task:** Given the full source of `Secp256k1.kt`, `Bip32.kt`, `Bech32.kt`,
  assess what's reusable vs. new for BIP340/341 and segwit-v1 addressing.
  Five specific numbered questions (full prompt: see below).
- **Assigned agent:** Agent A (`192.168.12.5:8000`).
- **Result:** See full response — summarized into
  `docs/architecture/current-state.md` §2. Key claims:
  - `pointAdd`/`pointDouble`/`scalarMultiply` reusable as-is for the group
    arithmetic BIP340 verification needs.
  - `compressPoint`/`decompressPoint`/`publicKeyFromPrivateKey` are NOT
    reusable as-is (33-byte SEC1 shape, not 32-byte x-only) — need x-only
    variants / a `lift_x` implementation.
  - Bech32→Bech32m is additive (shared polymod, different XOR constant) —
    **not yet independently verified against BIP350 test vectors, flagged as
    such**.
  - BIP86 (Taproot) reuses standard xpub/tpub version bytes per SLIP-132, no
    dedicated prefix — agent flagged its own uncertainty here and recommended
    checking the current SLIP-132 registry; **not yet independently verified**.
- **Review:** Not yet independently re-reviewed by Agent B or C. Claude
  spot-checked the described `decompressPoint` square-root logic against the
  actual `Secp256k1.kt` source (confirmed accurate) but did **not** verify the
  BIP340 nonce-derivation description (agent's phrasing was imprecise/
  hand-wavy compared to the actual spec algorithm) or the even-y(R) check in
  Schnorr verification (agent's answer omitted it). **Flagged: before Gate 1
  implementation, re-derive the exact BIP340 sign/verify algorithm from the
  primary spec text and official test vectors — do not implement from this
  summary alone.**
- **Claude decision:** Useful first-pass scoping, accepted as a starting point
  for Gate 1 planning, NOT accepted as an implementation-ready spec. Primary
  BIP340/341 text + test vectors are the authority for actual Gate 1 code.
- **Code affected:** none yet (Phase 0 research only).

## Task 3 — PSBT parser/construction/finalization review
- **Task:** Given the full source of `Psbt.kt`, `PsbtConstruction.kt`,
  `PsbtFinalization.kt`, assess current BIP174 field coverage, how unknown
  fields are handled, what BIP371 requires, where the P2WPKH/P2WSH-only gate
  lives, and whether witness serialization assumes a fixed stack shape. Five
  specific numbered questions (full prompt: see below).
- **Assigned agent:** Agent B (`192.168.12.6:8000`).
- **Result:** See full response — summarized into
  `docs/architecture/current-state.md` §3. Key claims:
  - Internal PSBT representation is untyped key-value (`PsbtKeyValue`/
    `PsbtMap`), not per-field data classes — BIP371 support is purely
    additive, no restructuring needed.
  - **Unknown/unrecognized PSBT fields are silently read into memory AND
    silently re-serialized verbatim on export — no allowlist, no rejection,
    anywhere in the pipeline.**
  - P2WPKH/P2WSH-only restriction enforced in `finalizableInputTemplate()`
    (gate) and repeated in `finalizeSingleSigInput()`/`finalizeMultisigInput()`
    (enforcement) via explicit `scriptPubKey` byte-shape checks.
  - `serializeWitnessStack()` is generic over `List<ByteArray>`, no
    fixed-shape assumption — a Taproot key-path witness would serialize fine
    once a finalizer branch exists to build one.
- **Review — Claude independently verified the highest-stakes claim** (the
  unknown-field pass-through) directly against the source via `grep` on
  `readMap`/`serializePsbt` in `Psbt.kt`: **confirmed accurate.**
  `entries.add(PsbtKeyValue(keyType, keyData, value))` at parse time has no
  type gate; `serializePsbt` iterates `psbt.global.entries` /
  input/output entries and writes every one back regardless of keyType. This
  is a genuine, currently-live gap, not a hypothetical.
- **Claude decision:** Accepted and escalated — this is significant enough to
  become its own roadmap item (Gate 0.5, inserted ahead of Gate 1 Taproot
  work) rather than being folded silently into BIP371 field additions later.
  Recorded in `docs/security/changes-since-v0.1.09.md` as new audit scope.
- **Code affected:** none yet (Phase 0 research only — Gate 0.5 implementation
  is next).

## Task 4 (self-directed, not delegated) — securityAudit violation found and fixed
- **Not a delegated task** — found by Claude while reading `entropy-core/
  build.gradle.kts` during Phase 0 reconnaissance (checking how the "no
  INTERNET" enforcement worked led to reading the sibling `:entropy-core`
  audit task, which turned out to forbid `SecureRandom` entirely in that
  module).
- **Finding:** `ShamirSecretSharing.kt` (committed earlier this session, before
  this Taproot project began) directly instantiated `java.security.SecureRandom`
  inside `:entropy-core`, violating that module's own `securityAudit` Gradle
  task (a real, enforced invariant behind "wallet entropy = f(dice)",
  `docs/NO-RNG-PROOF.md`). Confirmed by actually running
  `./gradlew :entropy-core:securityAudit` (had never been run before — the
  earlier session's `./gradlew test` runs don't depend on it, only `check`
  does).
- **Fix:** `splitSecret`/`entropyToBackupShares` now take `randomBytes: () ->
  ByteArray` as an injected parameter; the real `SecureRandom` instance moved
  to the app-module call site (`BackupSharesViewModel`), matching the existing
  `PinCrypto.generateSalt()` pattern for where app-level (non-wallet-entropy)
  randomness is allowed to live. A second, more subtle version of the same bug
  (the literal string "SecureRandom" appearing in a KDoc *comment* explaining
  the fix — the audit task's comment-stripping only handles `//`, not
  `/** */`) was also caught and reworded.
- **Verification:** Full `./gradlew check` (both modules' securityAudit,
  `:app:dependencyAudit`, `:app:lint`, all 612+ tests) passes clean after the
  fix.
- **Commit:** `7b6fbc9` on `feature/taproot-inheritance`.
- **Lesson recorded for the roadmap:** confirm whatever CI/release process
  exists actually runs `check`, not just `test`, so this class of bug can't
  ship silently again (noted in changes-since-v0.1.09.md).

---

## Delegation prompts on file (for reproducibility)
Full prompt text for Tasks 2 and 3 preserved on OpenClaw at
`/tmp/agentA_prompt.txt` and `/tmp/agentB_prompt.txt` (not copied into the repo
— they embed full source file contents, redundant with the repo itself; only
the prompt *header* text — the numbered questions — is worth preserving
long-term if this workflow continues. Consider moving header templates into
`docs/project/` if delegation becomes a recurring per-gate pattern.)
