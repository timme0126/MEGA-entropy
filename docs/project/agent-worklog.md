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

## Task 5 — User direction: skip Gate 0.5, preserve unrecorded-fingerprint signing
- User reviewed the Phase 0 report, decided the PSBT unknown-field
  pass-through finding is acceptable as-is (Gate 0.5 skipped), and clarified
  a hard requirement: PSBTs with an unrecorded (`00000000`) master
  fingerprint MUST remain signable, since most watch-only-wallet-created
  PSBTs never record one. Recorded in taproot-inheritance-roadmap.md's Gate
  2 section as an explicit requirement for the upcoming BIP371 work (the
  existing non-Taproot handling of this, `a7e78a0`/`bfd3d1c`, is the
  precedent to match, not tighten).
- **Not a delegated task** — direct user instruction, no agent involved.

## Task 6 — Gate 1: BIP340 Schnorr implementation
- **Task:** Implement BIP340 Schnorr signatures matching the exact spec
  algorithm (tagged_hash, lift_x, has_even_y, PubKey, Sign, Verify).
- **Assigned agent:** Agent A (`192.168.12.5:8000`), given the full exact
  spec pseudocode (fetched directly from bitcoin/bips, not paraphrased) plus
  the existing Secp256k1.kt source, and told explicitly to flag any
  uncertainty rather than guess.
- **Result:** Response truncated after ~660 characters, one paragraph into
  the answer (only the e=0 edge-case discussion for Verify's `s*G - e*P`
  came through). Root cause not investigated (endpoint/timeout/streaming
  issue, not a content problem visible in what did arrive).
- **Claude decision:** Given the truncation and the correctness stakes,
  implemented `Schnorr.kt` directly from the verified primary spec text
  rather than retry/debug the delegation pipeline mid-task. This is a
  deviation from the "Agent A implements" default the project charter
  describes — recorded here rather than silently done, per the
  transparency the worklog is for.
- **Review:** All 19 official BIP340 test vectors pass on first attempt
  (`Bip340OfficialVectorsTest.kt`, vectors parsed directly from the CSV via
  Python, never hand-transcribed or LLM-summarized — a raw-fetch discipline
  adopted after WebFetch's summarizing model was caught silently corrupting
  hex-string lengths earlier in this task, see Task 7). **Independent
  adversarial review (Agent B/C) of Schnorr.kt has NOT yet happened** —
  flagged as outstanding before this is considered fully reviewed per the
  charter's "Implementer → Independent Reviewer → Adversarial Reviewer →
  Claude approval" sequence for security-critical code.
- **Code affected:** `entropy-core/.../Schnorr.kt` (new),
  `Bip340OfficialVectorsTest.kt` (new). Commit `be5f500`.

## Task 7 — WebFetch summarization caught corrupting hex data
- **Not a delegated task** — a tooling reliability finding, recorded because
  it changed methodology for the rest of Gate 1.
- While researching BIP341 test vectors, `WebFetch` (which processes fetched
  content through a smaller summarizing model before returning it) rendered
  a JSON test-vector file's hex strings with wrong lengths (33-byte values
  rendered where the source data is 32 bytes) — caught by simply counting
  characters in the tool's own output before using any of it.
- **Decision:** From that point on, every test vector (BIP340 CSV, BIP341
  JSON, BIP350/BIP173 mediawiki text) was fetched with raw `curl` on OpenClaw
  and parsed with a small Python script, never through WebFetch's
  summarization, for anything where exact byte values matter. Recorded here
  as a standing practice for the rest of this project, not just this one
  file.

## Task 8 — Gate 1: BIP341 TapTweak, BIP350 Bech32m, BIP86 derivation
- **Task:** Implement Taproot output-key tweaking, general segwit v0-v16
  address encode/decode, and BIP86 wallet derivation.
- **Assigned agent:** None — implemented directly by Claude, each piece
  immediately checked against official test vectors fetched raw (see Task
  7) before being trusted: BIP341's `wallet-test-vectors.json` (7 key-path
  cases, all pass), BIP350's own address test-vector list (8 valid + 15
  invalid, all pass), BIP86's worked example (both vectors pass, including
  an end-to-end check that the exported WIF is the tweaked key and not the
  internal one).
- **Review:** Same as Task 6 — independent adversarial review by Agent B/C
  has not yet happened for this code either. Both are queued together as
  one review pass before Gate 2 begins, rather than reviewing Gate 1's
  pieces one at a time.
- **Code affected:** `TapTweak.kt` (new), `Bech32.kt` (extended),
  `Bip32.kt`/`WalletDerivation.kt`/`WalletAddresses.kt` (Taproot script
  type added), `AdvancedModeWalletScreen.kt` (doc comment only — the screen
  needed no functional change). Commit `84195b5`.

## Task 9 — Delegated Gate 1 adversarial review: found and fixed a tooling bug
- **Task:** Adversarial/security review of Schnorr.kt/TapTweak.kt/Bech32.kt
  (outstanding from Task 6/8), delegated to Agent B (adversarial - edge
  cases, the TapTweak parity-only-check concern, Bech32 decode
  completeness) and Agent C (security/threat-model - RNG-boundary audit,
  Schnorr nonce exfiltration surface, fail-closed audit, address-decode
  error granularity) in parallel.
- **Result (first attempt):** Both queries returned an EMPTY response —
  `/tmp/query_agent.py` crashed writing `None` to the output file.
- **Root cause found:** these endpoints (sglang/vllm-served Qwen3.6/3.8)
  emit an extended `reasoning_content` field before the final `content`
  field. With `max_tokens: 8000`, both requests hit `finish_reason: length`
  entirely inside the reasoning phase — `content` stayed `null`, `content`
  was never reached at all. Confirmed by a minimal manual repro (a "reply
  with just OK" prompt at `max_tokens: 50` reproduced the exact same
  null-content/length-truncation shape).
- **This also retroactively explains Task 6's finding** (Agent A's Schnorr
  implementation attempt truncating after one paragraph) — almost
  certainly the same root cause, not a separate issue.
- **Fix:** raised `max_tokens` to 24000 in `/tmp/query_agent.py` and
  re-launched both queries. Standing practice going forward: any
  non-trivial delegated task to these endpoints needs a generous
  `max_tokens` budget (reasoning + answer, not just answer) — 8000 is only
  safe for short/simple tasks.
- **Claude decision:** not worth retrying Task 6's Schnorr delegation with
  the fix now that Schnorr.kt is already implemented and vector-verified
  directly — the fix matters for THIS review task and any future
  delegation, not for redoing already-completed work.

---

## Delegation prompts on file (for reproducibility)
Full prompt text for Tasks 2 and 3 preserved on OpenClaw at
`/tmp/agentA_prompt.txt` and `/tmp/agentB_prompt.txt` (not copied into the repo
— they embed full source file contents, redundant with the repo itself; only
the prompt *header* text — the numbered questions — is worth preserving
long-term if this workflow continues. Consider moving header templates into
`docs/project/` if delegation becomes a recurring per-gate pattern.)
