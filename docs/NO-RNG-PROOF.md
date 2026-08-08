# No-RNG Proof

This document is the single place a reviewer should go to answer one question:

> **Can any source of randomness — device-generated, network-supplied, or
> otherwise — influence the 24-word mnemonic MEGA produces?**

The answer is **no**, and this document shows exactly why, by tracing the
complete code path from the 100 numbers a user types in to the 24 words
displayed at the end, and then separately accounting for every place in the
rest of the app that *does* use randomness and showing why none of it can
reach that path.

## 1. The only input to the entropy pipeline

The entire wallet-entropy derivation is one function call:

```kotlin
// entropy-core/src/main/kotlin/org/mega/entropycore/MnemonicPipeline.kt
fun deriveMnemonic(
    rolls: List<Int>,
    wordList: List<String> = loadOfficialEnglishWordList(),
): MnemonicResult
```

`rolls` is a `List<Int>` of exactly 100 values, each 1–6 — the physical die
results a human typed into the dice-entry UI, one tap per value, over 20
batches of 5 (see `app/src/main/kotlin/org/mega/entropy/ui/diceentry/`). There
is no other parameter, no default that pulls from a device source, and no
hidden global state `deriveMnemonic` reads from. `wordList` is the vendored,
hash-verified BIP39 English word list (see §4) — it selects *which word*
maps to a given index, not any part of the numeric derivation.

## 2. The deterministic pipeline, function by function

Every step below lives in module `:entropy-core`, a pure Kotlin/JVM module
with **zero Android dependency and zero third-party dependency**. Given the
same 100 rolls, every step below produces bit-for-bit identical output every
time, on any machine.

| Step | Function | File | Input | Output |
|---|---|---|---|---|
| 1 | `mapRollsToBase6` | `DiceMapping.kt` | 100 rolls (1–6) | 100 base-6 digits (0–5) |
| 2 | `calculateXDirect` | `DirectBase6.kt` | 100 base-6 digits | `X` (a `BigInteger`, positional base-6 interpretation) |
| 3 | `checkAcceptance` | `RejectionSampling.kt` | `X` | `Accepted(X)` or `Rejected(X)` — compared against the fixed constant `REJECTION_THRESHOLD_T = 5 × 2^256` |
| 4 | `deriveEntropy256` | `Entropy256.kt` | accepted `X` | `E = X mod 2^256`, encoded as 32 unsigned big-endian bytes |
| 5 | `sha256` / `calculateChecksum` | `Sha256Checksum.kt` | `E` (32 bytes) | SHA-256 digest of `E`, and its first 8 bits |
| 6 | `buildBitStream` | `Bip39BitStream.kt` | `E` + 8 checksum bits | 264-bit stream (256 entropy bits, MSB-first, then the 8 checksum bits) |
| 7 | `splitInto11BitGroups` | `Bip39BitStream.kt` | 264-bit stream | 24 integers, each 0–2047 |
| 8 | `deriveWords` | `WordList.kt` | 24 indices + word list | 24 words, by direct list lookup |

Steps 3 and 4 also depend on the UI-facing batch-accumulation path
(`calculateChunk` / `accumulate` / `accumulateAllBatches` in
`BatchAccumulator.kt`), which lets the dice-entry screen show a running
total after every 5-roll batch instead of only at roll 100. `MnemonicPipeline
.deriveMnemonic` itself always uses the direct 100-digit path
(`calculateXDirect`); the batch-accumulation path exists only for
incremental UI display, and `BatchAccumulatorTest.kt` /
`DirectBase6Test.kt` assert both paths always agree. Neither path
introduces any value that didn't come from a physical die roll — they are
two ways of computing the same positional integer.

**Rejection is a dead end, by construction.** If step 3 produces `Rejected`,
`deriveMnemonic` returns immediately (`MnemonicResult.Rejected`) without
calling steps 4–8 at all. There is no code path from a rejected sequence to
a mnemonic.

**SHA-256 (step 5) is a hash, not an entropy source.** It is a fixed,
public, deterministic function: the same 32 input bytes always produce the
same 32-byte digest, on any implementation, forever. It is used here exactly
as BIP-0039 specifies — to derive the checksum — and contributes no bits
that didn't already exist in `E`. See `docs/ENTROPY-MATH.md` for why this
doesn't add uncertainty.

## 3. What `:entropy-core` cannot do, mechanically

This isn't just a claim about how the code happens to be written today — it's
enforced by two independent things:

1. **No Android or third-party dependency.** `entropy-core/build.gradle.kts`
   only applies the Kotlin/JVM plugin. It cannot import `android.*` — that
   package doesn't exist on its compile classpath — which rules out
   `android.security.keystore.*`, sensors, `ContentResolver`, and anything
   else Android-specific.
2. **A static source check that fails the build.** The `securityAudit`
   Gradle task in `entropy-core/build.gradle.kts` scans every `.kt` file
   under `entropy-core/src/main/kotlin` for these substrings and throws if
   any is found:
   `SecureRandom`, `kotlin.random.Random`, `java.util.Random`,
   `java.util.UUID`, `System.currentTimeMillis`, `System.nanoTime`,
   `java.time.`, `java.util.Date`, `android.`.
   Run it directly with `./gradlew :entropy-core:securityAudit`. It runs as
   part of `check` (and therefore `test`), so a normal CI run already
   verifies this on every change. This is a **static, defense-in-depth**
   check, not a formal proof that the compiled bytecode can't somehow reach
   these APIs by other means (e.g. reflection) — it is not intended to catch
   deliberately obfuscated malicious code, only to catch this exact class of
   accidental regression, which is the realistic risk for a project that
   evolves over time.

Between the two, no RNG, clock, UUID, or Android API is *reachable* from
`entropy-core`'s public surface, and no change can silently introduce one
without the build failing.

## 4. The word list is data, not randomness

`WordList.loadOfficialEnglishWordList()` reads a vendored, plain-text
resource (`entropy-core/src/main/resources/bip39/english.txt`), verifies it
has exactly 2048 lines with no duplicates, and verifies its SHA-256 against
a second vendored file (`english.txt.sha256`) before returning it — throwing
and refusing to proceed on any mismatch. See `docs/BIP39-DERIVATION.md` for
where that file came from and how it was verified. Loading it involves no
randomness; it is fixed data read from the app's own resources, embedded in
the APK at build time.

## 5. Every other use of randomness in this codebase — and why none of it reaches the pipeline above

The prohibition in §3 is scoped to `:entropy-core`. The rest of the app
(`:app`) legitimately uses randomness for things that have nothing to do
with wallet entropy: encrypting saved data, generating IVs, salting a PIN
hash, and naming files on disk. Every one of those is listed here, with the
API, the file, and — most importantly — why it cannot influence a mnemonic.

| Location | API used | Purpose | Can it reach `:entropy-core`? |
|---|---|---|---|
| `app/.../ui/pin/ScrambledKeypad.kt` | `java.security.SecureRandom` | Reshuffles which screen position shows which digit on the PIN keypad, every time the PIN screen opens or after a wrong attempt (spec §21) | No. The shuffled order is pure UI layout state (`List<Int>` of screen positions) and is never passed to any `:entropy-core` function. `:entropy-core` cannot even import `android.*` or draw UI. |
| `app/.../security/pin/PinCrypto.kt` | `java.security.SecureRandom` | Generates the 16-byte random salt for PBKDF2-hashing the user's MEGA PIN | No. This produces a `PinRecord` (salt + hash + iteration count) written to `mega_security/pin.record`. Nothing in the PIN feature reads from or writes to the dice/mnemonic pipeline; `PinManager`/`PinStore`/`PinCrypto` never import `org.mega.entropycore`. |
| `app/.../storage/SessionRepository.kt` | `java.util.UUID.randomUUID()` | Generates a session's storage identifier (used as a filename and Keystore alias suffix) | No. This ID never enters entropy derivation — it's assigned *after* `deriveMnemonic` has already produced its result, purely to name where the already-computed dice/mnemonic get encrypted and stored. |
| `app/.../storage/SessionCrypto.kt` | Android Keystore (`KeyGenParameterSpec`) for AES-256 key generation, and `Cipher`'s own IV generation | Encrypts a saved session's dice rolls (and optionally its mnemonic) at rest, with a fresh key per session and a fresh IV per encryption | No. This encrypts the *result* of `deriveMnemonic` for storage; it runs strictly after the pipeline in §2 has already finished and cannot feed anything back into it. |
| `app/.../storage/SessionRepository.kt`, `app/.../security/pin/PinManager.kt` | `System.currentTimeMillis()` | Timestamps: session `createdAt`, PIN lockout expiry | No — this is a clock read, not randomness, and it's recorded metadata about an already-derived result, not an input to derivation. Listed here because `:entropy-core` forbids clock reads too, and it's worth being explicit about where that's *not* the case. |

Every row above lives in `:app`, never in `:entropy-core`, and none of
these values are parameters to `deriveMnemonic` or any function in §2's
table. The module boundary in §3 makes this structurally true, not just
true by current code review.

## 6. The expected answer

**Can device-generated randomness influence the mnemonic MEGA produces?**

**No.** The mnemonic is a pure function of the 100 dice rolls a user enters,
computed by the eight deterministic steps in §2, inside a module that
cannot import a randomness or clock API without failing its own build.
