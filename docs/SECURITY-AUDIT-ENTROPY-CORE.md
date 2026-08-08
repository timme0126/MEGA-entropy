# MEGA Entropy-Core Security Audit Report

**Date:** 2026-08-08
**Auditor:** Senior Application Security Engineer
**Scope:** `entropy-core/src/main/kotlin/org/mega/entropycore/` (11 source files, 10 test files)
**Method:** Manual code review, independent mathematical verification, independent BIP39 derivation, Gradle security audit execution, full test suite execution

---

## 1. VERDICT: APPROVE WITH COMMENTS

The entropy-derivation pipeline is **mathematically correct and unbiased**. All critical cryptographic properties are properly implemented and verified. No vulnerabilities that reduce the effective entropy space or introduce bias were found. Two medium/low findings and one informational observation are documented below.

---

## 2. CRITICAL / HIGH FINDINGS

**None found.**

---

## 3. MEDIUM / LOW FINDINGS

### FINDING 1 (MEDIUM): Back-Navigation Allows Partial Reuse of Rejected Sequences

**File:** `app/src/main/kotlin/org/mega/entropy/ui/biascheck/BiasCheckScreen.kt` (no `BackHandler`)
**File:** `app/src/main/kotlin/org/mega/entropy/ui/diceentry/DiceSessionViewModel.kt`, lines 141–179 (`undoLastRoll`, `reopenBatch`)

**Description:** When a 100-roll (or 50-roll) sequence is rejected by the bias check, the only forward action presented is "Start New Sequence," which calls `resetSession()` and wipes all state. However, `BiasCheckScreen` does **not** override the system back button. A user can press the Android back button to return to `DiceEntryScreen` with the rejected rolls still intact in `completedBatches`. From there, `undoLastRoll()` or `reopenBatch()` allow modifying a few rolls and re-completing the session — effectively reusing most of a rejected sequence with small edits.

**Risk Profile:** This does **not** introduce mathematical bias into the entropy — each edit triggers a full re-derivation through `deriveMnemonic`, which re-runs rejection sampling on the modified sequence. A user who deliberately edits a rejected sequence is only hurting themselves (they could just as easily enter any arbitrary sequence). However, it violates the documented design principle that rejection is "all-or-nothing" and could confuse users who believe rejection means their physical dice are biased (they're not — rejection is a mathematical necessity for uniformity).

**Severity Justification:** Rated MEDIUM rather than HIGH because: (a) the app is a local, single-user, offline tool; (b) the user IS the attacker in the worst case, and they can already enter arbitrary rolls; (c) no entropy bias results — the modified sequence goes through full rejection sampling again.

**Concrete Exploit Scenario:** A user rolls 100 dice, gets rejected, presses back, changes one roll, and gets accepted. The resulting mnemonic is still cryptographically valid and unbiased — but the user may incorrectly believe their original dice were "almost good enough" and that they "fixed" the bias by changing one roll, undermining the educational message about rejection sampling.

---

### FINDING 2 (LOW): `WordDerivation.groupIndex` Validation Overly Permissive for 12-Word Path

**File:** `entropy-core/src/main/kotlin/org/mega/entropycore/Models.kt`, line 134

```kotlin
require(groupIndex in 0..23) { "WordDerivation groupIndex must be 0..23, got: $groupIndex" }
```

**Description:** The `WordDerivation` data class validates `groupIndex` against the range `0..23`, which is correct for 24-word mnemonics but overly permissive for 12-word mnemonics (where only indices `0..11` are valid). This is a validation-only issue — the actual indices produced by `splitInto11BitGroups` are always correct because the bit stream length determines the group count (132 bits → 12 groups, 264 bits → 24 groups).

**Risk Profile:** No security impact. This is a code clarity/consistency issue. The overly permissive validation cannot be triggered by any code path in the pipeline because `MnemonicPipeline.deriveMnemonic` (line 73–82) generates `WordDerivation` objects from the indices produced by `splitInto11BitGroups`, which are always in the correct range.

---

### FINDING 3 (INFORMATIONAL): Security Audit Source Scan Has Known Bypass Vectors

**File:** `entropy-core/build.gradle.kts`, lines 33–62

**Description:** The `securityAudit` Gradle task performs a substring scan of `.kt` source files for forbidden API patterns. This is explicitly documented as "a static, defense-in-depth check, not a formal proof" in `docs/NO-RNG-PROOF.md` §3. The following bypass vectors exist but are **not present** in the current codebase:

1. **Reflection:** `Class.forName("java.security.Secure" + "Random")` would not match any pattern.
2. **String concatenation:** `val cls = "Secure" + "Random"` splits the pattern across literals.
3. **Kotlin stdlib internal randomness:** `List.shuffled()`, `List.random()` use `kotlin.random.Random` internally without an explicit import.
4. **Indirect dependency:** A future dependency that wraps RNG calls would not be detected by source scanning.

**Current Status:** No bypass is present. The only imports in `entropy-core` are `java.math.BigInteger`, `java.security.MessageDigest`, `java.io.InputStream`, `javax.crypto.SecretKeyFactory`, `javax.crypto.spec.PBEKeySpec`, and `java.text.Normalizer` — none of which provide randomness. No `shuffled()`, `random()`, or hash-based collection iteration-order dependencies exist in the derivation path.

**Risk Profile:** Acceptable as-is for an open-source project with code review. The module boundary (no Android dependency) provides a stronger guarantee than the source scan.

---

## 4. STAGE-BY-STAGE PROPERTY VERIFICATION

### Stage 1: Dice → Base-6 Mapping (`DiceMapping.kt`)

**(a) Required Property:** The mapping must be a pure bijection from {1,2,3,4,5,6} to {0,1,2,3,4,5} with no rounding, bucketing, or information loss.

**(b) Does the code guarantee this?** **YES.**

`mapRollToBase6` (line 8–11) computes `roll - 1` after validating `roll in 1..6`. This is a trivially verifiable bijection:
- 1→0, 2→1, 3→2, 4→3, 5→4, 6→5
- Every input maps to exactly one output; every output has exactly one preimage.
- No rounding, no bucketing, no conditional logic that could introduce non-uniformity.

**(c) Failure scenario:** None identified. The `require` on line 9 throws for any input outside 1..6, preventing invalid data from entering the pipeline.

---

### Stage 2: Base-6 Accumulation — Direct vs. Incremental (`DirectBase6.kt` vs. `BatchAccumulator.kt`)

**(a) Required Property:** Both paths must compute the identical BigInteger X for the same digit sequence: X = Σ d_i × 6^(n-1-i), where d_0 is the first roll (most significant digit).

**(b) Does the code guarantee this?** **YES.**

**Algebraic proof:**

The direct path uses Horner's method: X = ((...((d_0 × 6 + d_1) × 6 + d_2) × 6 + ...) × 6 + d_{n-1})

The incremental path splits digits into chunks of 5: chunk_i = d_{5i}×6^4 + d_{5i+1}×6^3 + d_{5i+2}×6^2 + d_{5i+3}×6 + d_{5i+4}

Then accumulates: X_{i+1} = X_i × 7776 + chunk_i (where 7776 = 6^5)

After 20 batches: X_20 = chunk_0 × 6^95 + chunk_1 × 6^90 + ... + chunk_19 × 6^0

Expanding each chunk: X_20 = (d_0×6^4 + ... + d_4×6^0) × 6^95 + ... + (d_95×6^4 + ... + d_99×6^0) × 6^0
= d_0×6^99 + d_1×6^98 + ... + d_99×6^0

This is identical to the direct path. **QED.**

**Numerical verification:** Independently verified with a seeded random 100-digit sequence — both paths produced identical results. Also verified for the 50-digit (12-word) case.

**First roll = most significant digit:** Confirmed consistent everywhere. `calculateXDirect` folds left-to-right with Horner's method (first digit gets highest exponent). `calculateChunk` assigns `a = digits[0]` the coefficient 6^4=1296 (highest within the chunk). `accumulate` multiplies previous X by 6^5 before adding the new chunk (earlier chunks get higher exponents). All consistent.

**(c) Failure scenario:** None identified. The existing tests (`DirectBase6Test.kt` cross-check vectors 1–5) verify equivalence across multiple patterns.

**UI discrepancy check:** `MnemonicPipeline.deriveMnemonic` (line 48) always uses `calculateXDirect` for the final derivation. The incremental path (`accumulate`/`accumulateAllBatches`) is only used by the UI for running display. Since both compute the identical X (proven above), there is no scenario where the user sees one value but the app derives from a different one.

---

### Stage 3: Rejection Sampling (`RejectionSampling.kt`)

**(a) Required Property:** T must be an exact multiple of 2^entropyBits, and acceptance must be all-or-nothing with no partial reuse.

**(b) Does the code guarantee this?** **YES** (with the UI caveat in Finding 1).

**Threshold computation verification:**

`rejectionThreshold(rollCount, entropyBits)` (line 16–20):
```kotlin
val sixN = sixPow(rollCount)        // 6^rollCount
val twoBits = twoPow(entropyBits)   // 2^entropyBits
return sixN.divide(twoBits).multiply(twoBits)
```

`BigInteger.divide()` performs integer division (floor for positive operands). The result is `floor(6^n / 2^bits) × 2^bits`, which is by construction an exact multiple of 2^bits.

**Independent recomputation:**

| Parameter | Value |
|-----------|-------|
| 6^100 | 653,318,623,500,070,906,096,690,267,158,057,820,537,143,710,472,954,871,543,071,966,369,497,141,477,376 |
| 2^256 | 115,792,089,237,316,195,423,570,985,008,687,907,853,269,984,665,640,564,039,457,584,007,913,129,639,936 |
| floor(6^100 / 2^256) | **5** ✓ |
| T (100 rolls) | 5 × 2^256 |
| Acceptance rate | T / 6^100 ≈ 0.88618 (≈ 88.6%, ~1-in-8 rejection) ✓ |
| floor(6^50 / 2^128) | **2** ✓ |
| T (50 rolls) | 2 × 2^128 |
| Acceptance rate | T / 6^50 ≈ 0.84199 (≈ 84.2%, ~1-in-6.3 rejection) ✓ |

The 100-roll multiplier is exactly 5, matching the documentation.

**All-or-nothing rejection:** `MnemonicPipeline.deriveMnemonic` (lines 52–54) returns `MnemonicResult.Rejected` immediately without computing entropy, checksum, or words. The `Rejected` data class carries only the `RejectionResult` — no entropy or mnemonic artifacts. The UI's only forward action is "Start New Sequence" → `resetSession()` → full state wipe. See Finding 1 for the back-navigation caveat.

**Timing side channel:** The accept/reject branch (line 71–75) involves a single `BigInteger.compareTo()` — constant-time relative to the value of X (BigInteger comparison time depends on bit length, not value). No information about X leaks to anything outside the app (the app is offline with no network permission).

**(c) Failure scenario:** None identified in the core module.

---

### Stage 4: Entropy Extraction (`Entropy256.kt`)

**(a) Required Property:** E = X mod 2^entropyBits must be uniform over [0, 2^entropyBits), and the byte encoding must be exactly entropyBits/8 bytes, unsigned, big-endian, with leading zeros preserved.

**(b) Does the code guarantee this?** **YES.**

**Uniformity proof:** Since T is an exact multiple of 2^entropyBits (verified in Stage 3), the accepted range [0, T) consists of exactly `floor(6^n / 2^bits)` complete, non-overlapping blocks of 2^entropyBits consecutive integers. X is uniform over [0, T) (since the dice are assumed fair — out of scope). Each block maps bijectively onto [0, 2^entropyBits) via mod 2^entropyBits. Therefore E = X mod 2^entropyBits is uniform over [0, 2^entropyBits). **QED.**

**`bigIntegerToUnsignedBytes` audit (lines 46–72):**

The function handles all edge cases correctly:
- **value = 0:** `toByteArray()` returns `[0]`; size > 1 is false, no strip; padded to 32 zeros. ✓
- **value with high bit set (e.g., 2^255):** `toByteArray()` returns 33 bytes with leading 0x00; stripped to 32 bytes. ✓
- **value = 2^256 - 1:** `toByteArray()` returns 33 bytes with leading 0x00; stripped to 32 bytes of 0xFF. ✓
- **value too large:** Caught by bounds check on line 53. ✓
- **Negative value:** Caught by signum check on line 47. ✓

The sign-byte stripping condition `rawBytes.size > 1 && rawBytes[0] == 0.toByte()` (line 61) is correct: `BigInteger.toByteArray()` adds at most one leading zero byte (for positive numbers with the high bit set), and this code strips at most one leading zero byte. No off-by-one.

**(c) Failure scenario:** None identified.

---

### Stage 5: BIP39 Checksum + Word Derivation (`Sha256Checksum.kt`, `Bip39BitStream.kt`)

**(a) Required Property:** Checksum = first ENT/32 bits of SHA-256(entropy), extracted MSB-first. Total bitstream = ENT + CS bits, split into exact 11-bit groups with no remainder.

**(b) Does the code guarantee this?** **YES.**

**Checksum bit extraction** (`Sha256Checksum.kt`, lines 46–50):
```kotlin
val firstByte = digest[0].toUByte().toInt()
for (i in 0 until checksumBitCount) {
    checksumBits[i] = (firstByte and (1 shl (7 - i))) != 0
}
```
For 256-bit entropy: checksumBitCount = 8. Bit 0 = MSB of digest[0] (bit 7), bit 7 = LSB of digest[0] (bit 0). This is MSB-first, matching BIP-39. ✓

For 128-bit entropy: checksumBitCount = 4. Only the high 4 bits of digest[0] are used. ✓

**Bitstream construction** (`Bip39BitStream.kt`, lines 12–38):
Entropy bits are extracted MSB-first per byte (line 28: `for (bitPos in 7 downTo 0)`), byte 0 first. Checksum bits appended after. Total: 256+8=264 bits (24 groups) or 128+4=132 bits (12 groups). ✓

**Group splitting** (`Bip39BitStream.kt`, lines 48–67):
264 / 11 = 24 exactly (no remainder). 132 / 11 = 12 exactly (no remainder). The `require` on line 49 enforces this. Each group: bit at offset j within the group maps to 2^(10-j), i.e., first bit is MSB. ✓

**Word list integrity** (`WordList.kt`):
- SHA-256 hash verified at runtime on every call to `loadOfficialEnglishWordList()` (line 34). ✓
- Line count verified (exactly 2048). ✓
- Duplicate check via `.toSet().size != 2048` (line 47). ✓
- Index bounds check in `deriveWords` (line 61: `indices.all { it in 0..2047 }`). ✓
- The vendored wordlist SHA-256 matches the documented hash: `2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda`. ✓

**Official BIP39 test vector verification:** All vectors from `trezor/python-mnemonic`'s `vectors.json` produce the correct mnemonics when run through the pipeline (verified by `Bip39VectorsTest.kt` and independently re-derived in Python during this audit). ✓

**(c) Failure scenario:** None identified.

---

### Stage 6: "No Device Randomness" Enforcement

**(a) Required Property:** No RNG, clock, or nondeterministic API can influence the derivation output.

**(b) Does the code guarantee this?** **YES** (with the documented limitations of the source-scan approach — see Finding 3).

**Source scan:** The `securityAudit` Gradle task passes. No forbidden patterns found.

**Import audit:** The only imports across all 11 source files are:
- `java.math.BigInteger` (deterministic arbitrary-precision arithmetic)
- `java.security.MessageDigest` (deterministic hash — SHA-256)
- `java.io.InputStream` (deterministic resource loading)
- `javax.crypto.SecretKeyFactory` (deterministic KDF — PBKDF2)
- `javax.crypto.spec.PBEKeySpec` (deterministic key spec)
- `java.text.Normalizer` (deterministic Unicode normalization)

None of these provide randomness or clock access.

**Accidental nondeterminism audit:**
- No `HashMap`/`HashSet` iteration in the derivation path. The only `.toSet()` call (WordList.kt line 47) is used for duplicate detection (size comparison), not for iteration order. The returned value is `lines` (a `List<String>`), not the `Set`.
- No `System.identityHashCode` usage.
- No `shuffled()`, `random()`, or any Kotlin stdlib function that internally uses randomness.
- All collections in the derivation path are `List` (ordered) or `BooleanArray`/`ByteArray` (indexed).

**(c) Failure scenario:** None identified in the current codebase. The source-scan approach has known bypass vectors (Finding 3), but none are present.

---

### Stage 7: Independent Verification

Three dice-roll sequences **not** from `docs/TEST-VECTORS.md` were independently derived in Python (from the raw BIP-39 specification) and compared against the Kotlin implementation's output:

| Test | Rolls | Python Result | Kotlin Result | Match |
|------|-------|---------------|---------------|-------|
| 1 | Alternating 1,2,3,4,5,6 (×100) | `defy trip fatal jaguar mean rack rifle survey satisfy drift twist champion steel wife state furnace night consider glove olympic oblige donor novel left` | Same | ✓ |
| 2 | All 3s (×100) | `dove awkward awful circle friend kingdom undo weird flat lottery silk engage tobacco suit book smoke grid creek office smoke grid creek office scrap` | Same | ✓ |
| 3 | 99×1 then 2 | `abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon diesel` | Same | ✓ |
| 4 | Alternating 1-6 (×50, 12-word) | `blue involve cook print twist crystal razor february caution private slim medal` | Same | ✓ |

All four independent derivations match the Kotlin implementation exactly.

---

## 5. REMEDIATION

### For Finding 1 (MEDIUM — Back-Navigation After Rejection):

Add a `BackHandler` to `BiasCheckScreen.kt` that forces the same behavior as "Start New Sequence" when the result is `Rejected`:

```kotlin
// In BiasCheckScreen.kt, inside the BiasCheckScreen composable:
if (rejectionResult is RejectionResult.Rejected) {
    BackHandler(onBack = onStartNewSequence)
}
```

This ensures that pressing the system back button from the rejection screen also wipes the session state, preventing any path where rejected rolls persist for editing.

### For Finding 2 (LOW — WordDerivation groupIndex validation):

Make the validation length-aware, or relax it to a non-negative check:

```kotlin
// Option A: Length-aware (requires passing maxGroups)
require(groupIndex in 0 until maxGroups) { "..." }

// Option B: Simple non-negative check (minimal change)
require(groupIndex >= 0) { "WordDerivation groupIndex must be non-negative, got: $groupIndex" }
```

### For Finding 3 (INFORMATIONAL — Security Audit Bypass):

No immediate action required. The existing documentation in `docs/NO-RNG-PROOF.md` §3 already correctly characterizes this as "a static, defense-in-depth check, not a formal proof." If the project wants to harden the audit, consider adding these patterns to the forbidden list:

```kotlin
// Additional patterns to consider:
"shuffled",
".random(",
"Class.forName",
"java.lang.reflect",
```

---

## 6. POSITIVE VERIFICATIONS SUMMARY

The following properties were **positively verified** (not merely "looks fine"):

| Property | How Verified |
|----------|-------------|
| Dice→base-6 is a pure bijection | Exhaustive mapping check (1→0, 2→1, ..., 6→5) |
| Direct and incremental paths compute identical X | Algebraic proof + numerical verification with seeded random input |
| First roll = most significant digit | Code inspection of Horner's method and chunk coefficients |
| floor(6^100 / 2^256) = 5 | Independent Python big-integer computation |
| floor(6^50 / 2^128) = 2 | Independent Python big-integer computation |
| T is exact multiple of 2^entropyBits | Code inspection: `.divide(twoBits).multiply(twoBits)` |
| Acceptance rate ≈ 88.6% (100 rolls) | Computed as T/6^100 ≈ 0.88618 |
| E = X mod 2^256 is uniform | Mathematical proof from T being exact multiple |
| bigIntegerToUnsignedBytes handles all edge cases | Manual analysis of 7 edge cases including sign-byte stripping |
| Checksum is MSB-first per BIP39 | Code inspection + test vector match |
| 264 bits = exactly 24 × 11 | Arithmetic verification |
| 132 bits = exactly 12 × 11 | Arithmetic verification |
| Word list SHA-256 verified at runtime | Code inspection + hash match confirmed |
| All derived indices bounds-checked 0..2047 | Code inspection of `deriveWords` require |
| No RNG/clock/nondeterminism in derivation path | Import audit + grep for hash-based collections + securityAudit pass |
| Official BIP39 vectors produce correct mnemonics | Independent Python derivation matched Kotlin output |
| Non-test-vector sequences produce correct mnemonics | 4 independent Python derivations matched Kotlin output |
| Full test suite passes | `./gradlew :entropy-core:test` — BUILD SUCCESSFUL |
| Security audit passes | `./gradlew :entropy-core:securityAudit` — PASSED |
