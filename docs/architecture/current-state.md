# MEGA — Current-State Architecture (Phase 0 baseline)

Compiled 2026-08-23 against `feature/taproot-inheritance` @ `7b6fbc9` (branched from
`structure-tx-onto-origin`, which tracked `origin/main` at tag `v0.1.11`). Verified
directly against the repository at `/home/timme/.openclaw/workspace/projects/MEGA/mega-clean`
on OpenClaw, plus two independent delegated reviews (Agent A: Secp256k1/Bip32/Bech32;
Agent B: PSBT parsing/construction/finalization) whose specific claims were spot-checked
against the actual source before being included here. See `docs/project/agent-worklog.md`
for the delegation record.

## 1. Build system / module layout

- Gradle (Kotlin DSL), two modules:
  - `:entropy-core` — pure Kotlin/JVM, **zero Android dependency, zero third-party
    dependency**, JVM toolchain 17. Contains all crypto/Bitcoin logic.
  - `:app` — Android app (compileSdk 36, minSdk 29, targetSdk 36), Jetpack Compose +
    Navigation Compose, depends on `:entropy-core`.
- Current release: `versionCode 12` / `versionName "0.1.11"` (tag `v0.1.11`).
- Both modules have a `securityAudit` Gradle task wired into `check`:
  - `:entropy-core:securityAudit` — greps every `src/main/kotlin/**/*.kt` line
    (comment-stripped on `//` only, **not** `/** */` block comments — a literal
    mention of a forbidden term even in a KDoc comment fails the build) for a
    fixed list of forbidden substrings: `SecureRandom`, `kotlin.random.Random`,
    `java.util.Random`, `java.util.UUID`, `System.currentTimeMillis`,
    `System.nanoTime`, `java.time.`, `android.`, plus reflection/dynamic-loading
    patterns (`Class.forName`, `getDeclaredMethod`, `MethodHandles`,
    `ServiceLoader`, `shuffled`, `.random(`). **This is the actual enforcement
    mechanism behind "wallet entropy is provably a pure function of dice rolls"**
    — see `docs/NO-RNG-PROOF.md`. Any future crypto code added to `:entropy-core`
    (including Taproot/Schnorr work) must not reference any of these, even in
    a doc comment. Test sources (`src/test/kotlin`) are NOT scanned, so RNG use
    in tests is unrestricted.
  - `:app:securityAudit` — regex-checks the raw `AndroidManifest.xml` (comments
    stripped) for a forbidden-permissions list (`INTERNET`, `ACCESS_NETWORK_STATE`,
    `READ/WRITE/MANAGE_EXTERNAL_STORAGE`) unless annotated `tools:node="remove"`,
    and requires `allowBackup="false"` + `dataExtractionRules` +
    `fullBackupContent`. A second task, `verifyMergedManifestPermissions`,
    re-checks the actual **merged** manifest output (post manifest-merger, both
    debug and release variants) for the same forbidden permissions — catching a
    permission introduced transitively by a dependency, not just the source
    manifest. `:app` also has a `dependencyAudit` task that fails the build if
    any resolved release runtime artifact matches known networking/telemetry/ads
    SDK name patterns (`okhttp`, `retrofit`, `ktor-client`, `firebase`,
    `crashlytics`, `sentry`, `analytics`, `advertising`, `ads`,
    `play-services`, `webview`).
  - **This means "MEGA enforces no-INTERNET at the build level, not just
    manually reviewed" is already true today** — the project brief's stated
    Phase-adjacent goal here is already met; it needs verifying/documenting as
    part of the audit, not building.
- Release signing: local, self-signed beta keystore (`docs/RELEASE-SIGNING.md`),
  `verifyReleaseArtifact` task checks the release APK is non-debuggable, signed by
  a hardcoded expected fingerprint, and carries no forbidden permissions.

## 2. Cryptography — entropy-core (hand-rolled, no external crypto library)

Every primitive below is hand-rolled Kotlin, deliberately (per this module's own
"no dependency" boundary — see §1). This matters directly for Taproot: **there is
no existing secp256k1 library to lean on**; any BIP340 Schnorr support is new code
in this same hand-rolled style, reviewed the same way this module's ECDSA code was.

- **`Secp256k1.kt`** (122 lines) — affine-coordinate `BigInteger` point arithmetic:
  `pointAdd`, `pointDouble`, `scalarMultiply` (double-and-add), `compressPoint`
  (33-byte SEC1), `decompressPoint` (33-byte SEC1 → point, via `modPow((P+1)/4, P)`
  square root since `p ≡ 3 mod 4`), `publicKeyFromPrivateKey`. **No Schnorr, no
  x-only serialization, no tagged-hash construction exist today.**
- **`Bip32.kt`** (226 lines) — HD derivation, base58/base58check encode/decode
  (`encodeBase58`/`decodeBase58`, reused by the new backup-shares work), extended
  key serialization. `ExtendedKeyScriptType` enum explicitly excludes Taproot/BIP86
  today (own doc comment: "intentionally not included yet").
- **`Bech32.kt`** (85 lines) — **segwit v0 only** (P2WPKH/P2WSH). Uses the bech32
  (not bech32m) checksum constant hardcoded. Per Agent A's review (independently
  plausible, not yet verified line-by-line against BIP350): the core
  polymod/HRP-expand logic is shared between bech32 and bech32m — only the XOR
  constant differs — so segwit v1 (Taproot, `bc1p...`) support is additive, not a
  rewrite, but this claim needs verifying against BIP173/BIP350 test vectors
  before being trusted for Phase 1.
- **`Ripemd160.kt`** — hand-rolled RIPEMD-160 (Android's stock `MessageDigest`
  doesn't reliably expose it). `hash160()` = RIPEMD160(SHA256(x)).
- **`Bip39*.kt` / `WordList.kt` / `Entropy256.kt` / `DiceMapping.kt` /
  `RejectionSampling.kt` / `DirectBase6.kt`** — the dice-to-mnemonic pipeline;
  fully documented and cross-checked in `docs/NO-RNG-PROOF.md` and
  `docs/ENTROPY-MATH.md`. Out of scope for Taproot work, should not need to
  change.
- **`Bip85.kt`** — BIP85 deterministic child-mnemonic derivation.
- **`Wif.kt`** — WIF private-key export, gated behind an explicit opt-in setting.
- **`ShamirSecretSharing.kt` / `EntropyBackupShares.kt`** (new this session) —
  Shamir's Secret Sharing over the secp256k1 scalar field, for the "backup
  shares" feature. Randomness is **injected** from the app-module call site
  (a `randomBytes: () -> ByteArray` parameter), not instantiated inside
  `:entropy-core` — this pattern (RNG lives at the app boundary, entropy-core
  stays pure) is the template any future Taproot code needing randomness
  (Schnorr's `aux_rand`, if implemented per spec) must also follow.

## 3. PSBT (BIP174) — entropy-core

- **`Psbt.kt`** (345 lines) — parser/serializer. Internal representation is
  deliberately generic: `data class PsbtKeyValue(keyType: Int, keyData: ByteArray,
  value: ByteArray)`, grouped into `PsbtMap(entries: List<PsbtKeyValue>)` for
  global/each-input/each-output. **There is no per-field-type data class** (no
  `PsbtInput`/`PsbtOutput` struct with named fields) — every field, known or
  unknown, is stored as an untyped key-value entry and read back out via
  accessor functions (`nonWitnessUtxo()`, `witnessUtxo()`, `partialSigs()`,
  `bip32Derivations()`, etc.) that filter `entries` by `keyType`.
  - Currently recognized (BIP174) key types: `0x00` (unsigned tx / non-witness
    UTXO), `0x01` (witness UTXO / redeem script), `0x02` (partial sig / output
    BIP32 derivation), `0x03` (sighash type), `0x04` (redeem script), `0x05`
    (witness script), `0x06` (input BIP32 derivation), `0x07`/`0x08` (final
    scriptSig/witness).
  - **Verified finding (independently confirmed against the source, not just
    Agent B's claim):** `readMap()` unconditionally does
    `entries.add(PsbtKeyValue(keyType, keyData, value))` for **every** key-value
    pair it reads, known or unknown, and `serializePsbt()` writes back
    `psbt.global.entries` / each input's / each output's entries **verbatim, in
    stored order, regardless of keyType**. There is currently **no allowlist,
    no stripping, and no rejection of unknown/proprietary PSBT fields anywhere
    in the pipeline** — an unrecognized field present in a scanned PSBT survives
    unmodified into the PSBT MEGA exports after signing. Duplicate keys within
    one map ARE rejected at parse time (BIP174 uniqueness requirement,
    `seenKeys` check in `readMap`), but that's a structural check, not a
    field-allowlist.
  - **This is a real, currently-live gap against this project's own stated
    threat model** ("strip unknown PSBT fields before export; reject
    unsupported proprietary fields") — it predates Taproot entirely and applies
    to ordinary P2WPKH/P2WSH signing today. Recommend treating "PSBT field
    allowlisting/stripping" as its own early hardening task, not bundled into
    Taproot PSBT (BIP371) work, since it's foundational to "the signer
    independently verifies, never trusts the coordinator" and doesn't need
    Taproot to matter. See §9 risk list.
- **`PsbtConstruction.kt`** (468 lines) — builds unsigned PSBTs, including the
  "Structure a Transaction" harvest-and-restructure flow
  (`harvestOwnedInputsForStructuring`, `restructurePsbt`).
- **`PsbtFinalization.kt`** (293 lines) — turns a signed PSBT into a
  broadcast-ready transaction. The P2WPKH/P2WSH-only restriction (per
  `docs/PSBT-SECURITY.md`: "Only bare P2WPKH and bare P2WSH sortedmulti inputs
  are signed. No P2SH wrapping, no Taproot, no legacy.") is enforced by explicit
  `scriptPubKey` shape checks in `finalizableInputTemplate()` (gate) and again in
  `finalizeSingleSigInput()`/`finalizeMultisigInput()` (enforcement) — an input
  that doesn't match `0x00 0x14<20 bytes>` (P2WPKH) or `0x00
  0x20<sha256(witnessScript)>` (P2WSH) is left **unfinalized**, not
  crash/corrupt. `serializeWitnessStack()` itself is generic over
  `List<ByteArray>` with no hardcoded stack-size assumption, so a future
  single-element Taproot key-path witness (`[sig]`, 64 or 65 bytes) would
  serialize correctly once a Taproot-aware finalizer branch exists — the gate
  functions are what actually need a new Taproot case, not the serialization
  layer.
- **`PsbtSigning.kt` / `PsbtSigningDiagnostics.kt` / `PsbtSummary.kt` /
  `CosignerPsbtSigning.kt`** — signing orchestration, pre-sign diagnostics
  (fingerprint verification etc.), human-readable PSBT summaries for the review
  screen, and saved-multisig-vault cosigner signing.
- **`SegwitSighash.kt`** — BIP143 sighash. No BIP341 (Taproot) sighash exists yet
  — this is new, separate logic (different message structure entirely, not an
  extension of BIP143).

## 4. Script / multisig — entropy-core

- **`MultisigScript.kt`** (55 lines) — BIP67 canonical pubkey sorting
  (`sortPublicKeysBip67`, correct unsigned-byte comparator) + a **fixed bare
  multisig script template builder** (`buildMultisigWitnessScript`:
  `OP_<m> <pk1>...<pkN> OP_<n> OP_CHECKMULTISIG`). **This is not a Miniscript
  engine or general script compiler** — it hardcodes exactly one script shape.
  There is no descriptor-to-script compiler, no policy language, no Miniscript
  satisfier/analyzer anywhere in the codebase today.
- **`DescriptorChecksum.kt`** (116 lines) — BIP380 descriptor **checksum**
  computation only (ported character-for-character from Bitcoin Core's
  `PolyMod`), used to validate/display descriptor strings. Does not parse or
  interpret descriptor syntax beyond the checksum.
- **`MultisigDerivation.kt`** — cosigner xpub/fingerprint derivation for the
  saved-vault flow.
- **Implication for Phase 2/3/4 (Wallet Policy Engine, P2WSH/Taproot
  inheritance):** there is currently zero Miniscript infrastructure. "Use
  Miniscript where appropriate so policies remain analyzable/satisfiable" (per
  the project brief) means building a real (even if minimal-subset) Miniscript
  compiler and satisfier from scratch in this same hand-rolled style, or
  explicitly deciding to hand-generate the small fixed set of script shapes the
  inheritance policy actually needs (owner-key-OR-timelocked-threshold) without
  a general Miniscript layer. This is a first-order architecture decision for
  Phase 2, not a detail — flagged for Claude decision before Phase 2 begins,
  not delegated.

## 5. Address encoding

- Bech32 (segwit v0) only, as above (§2). No Base58Check P2PKH/P2SH encoder
  exists (consistent with "no legacy" scope per `docs/PSBT-SECURITY.md`).

## 6. Storage

- **`SessionRepository`/`SessionFileStore`/`SessionCrypto`** — mnemonic-bearing
  saved sessions, AES-GCM encrypted at rest, Android Keystore-backed key, PIN
  gated. Metadata (label, roll count, `hasMnemonic`, `hasPassphraseCheck`,
  `childSeedInfo`, and — new this session — `tags`) stored unencrypted
  alongside (format now `MEGA-META-V5`, backward-compatible with V3/V4 files).
- **`MultisigVaultRepository`/`MultisigVaultFileStore`/`MultisigVaultSerializer`**
  — saved multisig vaults, stored **unencrypted** (public key material only by
  construction — no private key, no mnemonic). Format `MEGA-MULTISIG-VAULT-V1`
  with an optional trailing `tags64` line (new this session, backward
  compatible).
- **`PinManager`/`PinCrypto`** (app module) — PIN hashing (PBKDF2), salt via
  `SecureRandom` — the one place in the app allowed to use it, isolated from
  wallet-entropy generation per its own doc comment.
- No cloud sync, no backup path (`allowBackup="false"` + data-extraction rules,
  enforced by `:app:securityAudit`, see §1).

## 7. UI / navigation

- Jetpack Compose + Navigation Compose, single `MegaNavGraph.kt` (~1500 lines)
  using plain string routes (`MegaDestinations.kt`) — not a custom state-machine
  abstraction. Screens are grouped by feature package under `app/src/main/kotlin/
  org/mega/entropy/ui/` (e.g. `advancedmode/`, `advancedmode/multisig/`,
  `advancedmode/structuretx/`, `advancedmode/backupshares/` (new this session),
  `savedsessions/`, `security/`).
- Established per-feature pattern for a "scan → build → review → sign" flow
  (Structure a Transaction is the most complete recent example): a disclaimer/
  acknowledgment screen (explicit checkbox gate) → a form screen backed by a
  nav-graph-scoped ViewModel (survives round-trips to a QR scanner sub-screen) →
  hands off to the SAME shared `PsbtReviewScreen`/`PsbtSignResultScreen` any
  other signing path uses. **This is the template Renew Protection / Recovery
  transaction review (Phase 3) should follow** — a distinct entry disclaimer +
  distinct built-PSBT construction, landing on a (probably extended)
  shared review screen rather than a bespoke one.
- QR: ZXing (encode + decode) for single-frame QR; `com.sparrowwallet:hummingbird`
  (BC-UR) + a hand-rolled BBQr implementation (`Bbqr.kt`) for animated
  multi-frame series (large PSBTs, xpubs). Camera via CameraX. No network
  dependency anywhere in this path.
- `PsbtReviewScreen` is the one existing "independently parse and display the
  transaction, never trust the coordinator's description" screen — the
  natural extension point for Renew-Protection-aware / policy-change-aware
  review (Phase 3's "offline signer as policy firewall").

## 8. Tests

- 612 tests total (both modules, debug variant) across 82 test files, as of
  this baseline. `entropy-core` tests are pure-JVM (fast, no emulator);
  `:app` tests are Robolectric/JVM unit tests (no instrumented/on-device test
  suite observed in this pass — worth confirming in the security audit phase).
  Full `./gradlew check` (both modules' `securityAudit`, `:app:dependencyAudit`,
  `:app:lint`, all tests) passes clean at this baseline commit.
- Existing test style, worth matching for Taproot work: BIP-vector-style exact
  hex assertions (`Secp256k1Test.kt` checks `1*G`/`2*G` against known reference
  points), plus a deliberate "hardening" test-file convention
  (`PsbtFinalizationHardeningTest.kt`, `PsbtSigningHardeningTest.kt`, etc.) for
  adversarial/malformed-input regression tests, one file per prior audit
  finding.

## 9. Existing documented security posture

- `docs/SECURITY-MODEL.md`, `docs/PSBT-SECURITY.md`, `docs/NO-RNG-PROOF.md`,
  `docs/STORAGE-DESIGN.md`, `docs/RELEASE-SIGNING.md`,
  `docs/SECURITY-AUDIT-V0.1.9.md` (the prior audit — scope v0.1.7→v0.1.9, AI-
  assisted, explicitly NOT an independent human audit, 11 confirmed findings
  from a first pass + additional findings from an adversarial second pass, all
  fixed with regression tests — see `docs/security/changes-since-v0.1.09.md`
  for what has changed since).
- Manifest: only `CAMERA` (QR scanning) as a dangerous permission;
  `ACCESS_NETWORK_STATE` explicitly present but marked `tools:node="remove"`
  (merged out, presumably from a transitive dependency manifest — worth
  confirming which dependency declares it during the audit phase).
- **Not yet addressed, flagged for the roadmap (§ risks below):**
  1. PSBT unknown-field pass-through (§3) — real, live, pre-dates Taproot.
  2. No Taproot/Schnorr/Bech32m code exists anywhere (confirmed via repo-wide
     grep) — Phase 1 starts from zero, not from a partial implementation.
  3. No Miniscript/policy-compiler infrastructure exists (§4) — Phase 2/3
     architecture decision needed before implementation.
  4. No on-device/instrumented test suite observed — worth confirming scope
     before claiming "full recovery simulations" (Phase 6) are automatable
     vs. requiring manual device testing.

## 10. Local agent team — verified operational

Three OpenAI-compatible endpoints confirmed reachable and responsive from
OpenClaw (192.168.12.26), matching the project brief's assignments:

| Agent | Endpoint | Model | Confirmed |
|---|---|---|---|
| A | `http://192.168.12.5:8000` | `qwen3.6-35b` (sglang) | responded, Secp256k1/Bip32/Bech32 review |
| B | `http://192.168.12.6:8000` | `qwen3.6-35b` (sglang) | responded, PSBT review, found the unknown-field gap |
| C | `http://192.168.12.7:8000` | `qwen3.8-27b` (vllm) | reachable, not yet exercised |

Delegation mechanism: a small Python script (`/tmp/query_agent.py` on
OpenClaw) posts a single large user-message prompt (source file content +
specific numbered questions) to each endpoint's `/v1/chat/completions` and
captures the response to a file. This is a raw completion call, not an
agentic loop — agents have no filesystem/tool access of their own; all context
must be pasted into the prompt by Claude (context budget: all three endpoints
report 200K+ token context windows, comfortably enough for multi-file
excerpts). See `docs/project/agent-worklog.md` for the exact prompts and
responses recorded so far, and the independent verification performed on
each claim before it was trusted.
