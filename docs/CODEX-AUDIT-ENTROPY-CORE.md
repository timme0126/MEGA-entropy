> **Remediation status (2026-08-08, same day):** All four findings below were
> fixed the same day this report was produced:
> - MEDIUM (rejection bypass via public low-level APIs): `deriveEntropyBits`,
>   `deriveEntropy256`, `bigIntegerToUnsignedBytes`, `calculateChecksum`,
>   `sha256`, `buildBitStream`, `splitInto11BitGroups`, and `deriveWords` are
>   now `internal`, reachable only from within `:entropy-core` itself
>   (verified no caller outside the module used them).
> - LOW (custom word lists bypass verification): the public `deriveMnemonic`
>   no longer accepts a `wordList` parameter at all — it always uses the
>   hash-verified official list (verified no caller ever passed a custom
>   one).
> - LOW (`securityAudit` substring-scan bypass): added the suggested
>   reflection/dynamic-loading patterns to the forbidden list in
>   `entropy-core/build.gradle.kts`.
> - LOW (`accumulateAllBatches` hardcoded to 20 chunks): now accepts 10 or
>   20, matching both supported mnemonic lengths.
>
> Full `:entropy-core` test suite (78 tests) and `securityAudit` pass after
> all four fixes.

1. VERDICT: APPROVE WITH COMMENTS

No critical or high severity issue was found in the default `deriveMnemonic(...)`
pipeline. For valid dice input using the default vendored word list, the code
derives an unbiased BIP39 mnemonic for both supported sizes.

I did find API-hardening issues where low-level public functions can be composed
incorrectly by a caller, plus a bypassable static audit guard. These do not
change the default pipeline result, but they are worth fixing because this
module is the security boundary.

Isolation check: I listed repository files with `rg --files` and searched for
`audit|report|finding|verdict|CODEX|security|review`. I found docs, tests, build
reports, and BIP39 word-list words containing those terms, but no pre-written
audit report in this directory or its subdirectories.

2. CRITICAL / HIGH FINDINGS

None.

3. MEDIUM / LOW FINDINGS

MEDIUM: Rejection can be bypassed by manual composition of public low-level APIs.

- `entropy-core/src/main/kotlin/org/mega/entropycore/MnemonicPipeline.kt:51-54`
  correctly returns `MnemonicResult.Rejected` before entropy extraction when
  `checkAcceptance(...)` rejects.
- `entropy-core/src/main/kotlin/org/mega/entropycore/Entropy256.kt:24-27`
  exposes `deriveEntropyBits(x, entropyBits)` as a public top-level function
  that accepts any non-negative `x` and reduces it modulo `2^entropyBits`.
- `entropy-core/src/main/kotlin/org/mega/entropycore/Sha256Checksum.kt:34-52`,
  `entropy-core/src/main/kotlin/org/mega/entropycore/Bip39BitStream.kt:12-67`,
  and `entropy-core/src/main/kotlin/org/mega/entropycore/WordList.kt:59-63`
  are also public and can be called after bypassing rejection.

Property required: rejected dice sequences must be discarded as a whole. No
derived entropy or mnemonic may be produced from a rejected `X`, because using
`X mod 2^ENT` over the rejected tail reintroduces modulo bias.

What the final pipeline guarantees: `deriveMnemonic(...)` guarantees all-or-
nothing rejection because line 52 checks for `Rejected` and line 53 returns
immediately before line 57 derives entropy.

What the module API does not guarantee: public low-level functions do not carry
an "accepted X" type, so a caller inside or outside this module can manually
derive from rejected input.

Concrete failure scenario:

- Input: 100 rolls all equal to `6`.
- Correct pipeline result: rejected, because the direct base-6 value is
  `6^100 - 1`, which is `>= T`.
- Incorrect public-API composition:
  `deriveEntropyBits(calculateXDirect(mapRollsToBase6(List(100) { 6 })), 256)`,
  then checksum and word derivation.
- Wrong entropy produced by that bypass:
  `a4653ca673768565b41f775d6947d55cf3813d0fffffffffffffffffffffffff`
- Wrong mnemonic produced by that bypass:
  `piece clarify civil tragic hair ready spare upon frost enforce vocal rigid day ozone divide zoo zoo zoo zoo zoo zoo zoo zoo valve`

LOW: Custom word lists bypass vendored SHA-256 verification and uniqueness checks.

- `entropy-core/src/main/kotlin/org/mega/entropycore/MnemonicPipeline.kt:16-17`
  and `:33-37` default to `loadOfficialEnglishWordList()`, which is verified.
- `entropy-core/src/main/kotlin/org/mega/entropycore/WordList.kt:17-51`
  verifies the vendored list hash, line count, and uniqueness.
- `entropy-core/src/main/kotlin/org/mega/entropycore/WordList.kt:59-63`
  only requires a caller-supplied `wordList` to have size 2048; it does not
  require the official hash or uniqueness.

Property required: the BIP39 word index must map into the official 2048-word
list in official order. Otherwise the entropy and checksum can be correct while
the displayed mnemonic is not the standard BIP39 mnemonic for that entropy.

What the default pipeline guarantees: with the default argument, the vendored
word list is hash-verified at load time before derivation.

What is not guaranteed: callers can pass a malformed 2048-entry list.

Concrete failure scenario:

- Input: 100 rolls all equal to `1`, with a caller-supplied `List(2048) { "abandon" }`.
- Correct official output for all-zero entropy is 23 times `abandon`, then
  `art`.
- Wrong output permitted by `deriveWords(...)`: 24 times `abandon`, because
  line 60 checks only list size and line 63 directly indexes the supplied list.

LOW: `securityAudit` is a substring source scan and can be bypassed by reflection
or split strings.

- `entropy-core/build.gradle.kts:21-31` defines forbidden substrings.
- `entropy-core/build.gradle.kts:41-48` scans only `.kt` source lines and uses
  `line.substringBefore("//").contains(pattern)`.

Property required: no device randomness, clock, UUID, Android API, or indirect
equivalent may influence entropy derivation.

What current source guarantees: I found no current RNG, clock, UUID, Android,
reflection, `HashMap`/`HashSet`, `System.identityHashCode`, or unordered
iteration use in `entropy-core/src/main/kotlin`.

What the audit task does not guarantee: the task is easy to evade by avoiding a
contiguous forbidden substring or by using reflection. For example, a malicious
or accidental future change such as
`Class.forName("java.security." + "Secure" + "Random")` would not contain the
literal `SecureRandom` substring on one source line. The current code does not
do this, but the Gradle task would not prove it cannot happen.

LOW: `accumulateAllBatches(...)` is hard-coded to 20 chunks and is not a full
helper for the 50-roll / 12-word mode.

- `entropy-core/src/main/kotlin/org/mega/entropycore/BatchAccumulator.kt:45-47`
  requires exactly 20 chunks.
- `entropy-core/src/main/kotlin/org/mega/entropycore/Models.kt:165-167`
  supports both 50-roll and 100-roll mnemonic lengths.
- `entropy-core/src/main/kotlin/org/mega/entropycore/MnemonicPipeline.kt:47-48`
  uses the direct path for final derivation, not the batch helper.

Property required: if the UI displays an incremental accumulated `X`, that `X`
must be algebraically identical to the direct `X` used for final derivation for
the same digit sequence.

What is guaranteed for 100 rolls: each 5-digit chunk is computed as
`a*6^4 + b*6^3 + c*6^2 + d*6 + e` at `BatchAccumulator.kt:15-22`, and each
accumulation step multiplies the previous value by `6^5` and adds the next
chunk at `BatchAccumulator.kt:31-35`. This is identical to the direct Horner
calculation at `DirectBase6.kt:21-24` when chunks are supplied in order.

What is not guaranteed by this helper: for a 50-roll sequence split into 10
chunks, `accumulateAllBatches(...)` throws instead of computing the final `X`.
That is not a wrong mnemonic in `entropy-core`, because `deriveMnemonic(...)`
does not use this function, but it is an API inconsistency for the second
supported mode.

4. REMEDIATION

For rejected-value API misuse:

- Introduce an `AcceptedBase6Value` value class or data class produced only by
  `checkAcceptance(...)`.
- Change entropy extraction to accept only `AcceptedBase6Value`, not raw
  `BigInteger`.
- Make low-level derivation functions `internal` unless there is a strong reason
  to expose them as public library API.

For custom word lists:

- Prefer removing the `wordList` parameter from public `deriveMnemonic(...)`.
- If dependency injection is needed for tests, expose a separate internal/test
  API or validate any supplied list against the expected official hash and
  uniqueness constraints before deriving words.
- Add `require(wordList.toSet().size == 2048)` to `deriveWords(...)` at minimum,
  though uniqueness alone is weaker than official-order hash verification.

For `securityAudit`:

- Add forbidden patterns for reflection and dynamic loading APIs:
  `Class.forName`, `getMethod`, `getDeclaredMethod`, `java.lang.reflect`,
  `MethodHandles`, and `ServiceLoader`.
- Add a compiled-bytecode/constant-pool scan in addition to source scanning.
- Consider using a Kotlin compiler/static-analysis rule instead of substring
  matching.

For batch accumulation:

- Replace `accumulateAllBatches(chunksInOrder: List<Long>)` with a generalized
  helper that accepts expected chunk count or `MnemonicLength`, requiring 10
  chunks for 50 rolls and 20 chunks for 100 rolls.
- Add tests proving direct and incremental equality for both supported lengths.

5. POSITIVE VERIFICATIONS

Dice to base-6 mapping:

- Required property: physical rolls `1..6` must map bijectively to base-6
  digits `0..5`, with no bucketing, rounding, hashing, or randomization.
- Verified code: `DiceMapping.kt:8-10` rejects values outside `1..6` and returns
  exactly `roll - 1`.
- Result: property holds, assuming physical die fairness is out of scope.

Direct base-6 accumulation:

- Required property: first roll must be the most significant base-6 digit, and
  all digit positions must be weighted exactly once.
- Verified code: `DirectBase6.kt:17-25` uses Horner's method:
  `acc = acc * 6 + digit`, matching
  `d1*6^(n-1) + ... + dn*6^0`.
- Result: property holds.

Batch vs direct accumulation:

- Required property: the incremental display path must compute the same `X` as
  the direct path for the same ordered digits.
- Verified code: `BatchAccumulator.kt:15-22` computes a 5-digit chunk with the
  first digit as most significant; `BatchAccumulator.kt:31-35` shifts prior
  chunks by `6^5` and adds the next chunk.
- Result: property holds algebraically for 20 in-order chunks. The final
  mnemonic path uses only the direct value at `MnemonicPipeline.kt:47-48`.

Rejection sampling:

- Required property: accepted range `T` must be an exact multiple of
  `2^entropyBits`, and acceptance must be `x < T`.
- Verified code: `RejectionSampling.kt:16-20` computes
  `floor(6^rollCount / 2^entropyBits) * 2^entropyBits` by integer division;
  `RejectionSampling.kt:62-75` accepts exactly when `x < threshold`.
- Independent computation:
  - 100 rolls / 256 bits:
    `floor(6^100 / 2^256) = 5`
    `T = 578960446186580977117854925043439539266349923328202820197287920039565648199680`
    acceptance probability =
    `578960446186580977117854925043439539266349923328202820197287920039565648199680 / 653318623500070906096690267158057820537143710472954871543071966369497141477376`
    = `0.886183900720408757`.
  - 50 rolls / 128 bits:
    `floor(6^50 / 2^128) = 2`
    `T = 680564733841876926926749214863536422912`
    acceptance probability =
    `680564733841876926926749214863536422912 / 808281277464764060643139600456536293376`
    = `0.841989976529606388`.
- Result: property holds in the final pipeline.

Entropy extraction:

- Required property: for accepted `X`, `E = X mod 2^ENT` must be unbiased
  because `T` is an exact multiple of `2^ENT`.
- Verified code: `RejectionSampling.kt:16-20` constructs exact multiples;
  `Entropy256.kt:24-27` extracts `x mod 2^entropyBits`.
- Required byte property: bytes must be unsigned, big-endian, fixed length,
  preserving leading zeros.
- Verified code: `Entropy256.kt:46-55` rejects negative and oversized values;
  `Entropy256.kt:58-63` strips only one leading sign byte when present;
  `Entropy256.kt:66-70` left-pads shorter values to the requested length.
- Result: property holds for accepted pipeline values.

BIP39 checksum and word derivation:

- Required property: BIP39 checksum is the first `ENT/32` bits of
  `SHA-256(entropy)`, MSB-first. BIP39 word indices are consecutive 11-bit
  groups over entropy bits followed by checksum bits.
- Verified code: `Sha256Checksum.kt:34-50` accepts only 16 or 32 entropy bytes,
  computes SHA-256, sets checksum length to `bytes / 4`, and extracts from the
  high bits of digest byte 0.
- Verified code: `Bip39BitStream.kt:12-38` appends entropy bits MSB-first per
  byte followed by checksum bits; `Bip39BitStream.kt:48-67` requires a multiple
  of 11 and interprets each group MSB-first.
- Arithmetic checked:
  - 128-bit entropy: `CS = 4`, `ENT + CS = 132`, `132 / 11 = 12`.
  - 256-bit entropy: `CS = 8`, `ENT + CS = 264`, `264 / 11 = 24`.
- Word-list checks: `WordList.kt:17-51` verifies the vendored resource SHA-256,
  count, and uniqueness before returning the default list; `WordList.kt:59-63`
  bounds-checks every index to `0..2047` before lookup.
- Vendored word-list hash verified with:
  `sha256sum entropy-core/src/main/resources/bip39/english.txt`
  returning
  `2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda`.

No device randomness / nondeterminism:

- Ran `./gradlew :entropy-core:securityAudit`: passed.
- Ran `rg` for RNG, clock, UUID, Android, reflection, identity, and unordered
  collection patterns under `entropy-core/src/main/kotlin`: no current source
  path uses them.
- `entropy-core/build.gradle.kts:13-15` declares only JUnit as a test
  dependency; main code has no Android or third-party runtime dependency.
- Deterministic JDK/JCA uses found:
  `MessageDigest.getInstance("SHA-256")` at `Sha256Checksum.kt:18` and
  `WordList.kt:29`, and `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")`
  at `SeedDerivation.kt:29`. These are deterministic algorithms for fixed
  inputs and are not entropy sources.

Independent vectors not present in `docs/TEST-VECTORS.md`:

I used an independent Python script using only integer arithmetic,
`hashlib.sha256`, and the vendored word list as BIP39 data. I separately called
the compiled Kotlin API from a temporary Java driver against
`entropy-core/build/libs/entropy-core.jar`. The outputs matched exactly.

- `case12_a`: rolls `[1,2,3,4,5,6] * 8 + [1,2]`
  - Entropy:
    `184ec4bed56eb86aacaaa224b5672f45`
  - Mnemonic:
    `blue involve cook print twist crystal razor february caution private slim medal`
- `case12_b`: rolls `[2,1,6,5,3,4] * 8 + [2,1]`
  - Entropy:
    `757f7ebed2d6aee78066c9c299794ac2`
  - Mnemonic:
    `install winner quick pizza helmet inherit account summer secret slim famous luggage`
- `case24_a`: rolls `[1,2,3,4,5,6] * 16 + [1,2,3,4]`
  - Entropy:
    `39bd194e3b989d612e6ed5bf485bae130d53f5f532f29585e98ecd298282a5c3`
  - Mnemonic:
    `defy trip fatal jaguar mean rack rifle survey satisfy drift twist champion steel wife state furnace night consider glove olympic oblige donor novel left`

Commands run:

- `./gradlew :entropy-core:securityAudit`
- `./gradlew :entropy-core:test`
- `sha256sum entropy-core/src/main/resources/bip39/english.txt entropy-core/src/main/resources/bip39/english.txt.sha256`
- `rg -n "Random|SecureRandom|UUID|currentTimeMillis|nanoTime|java\\.time|java\\.util\\.Date|android\\.|Class\\.forName|getMethod|invoke|MethodHandles|ServiceLoader|IdentityHashMap|identityHashCode|hashCode\\(|HashMap|HashSet|mutableMapOf|mutableSetOf|setOf\\(|mapOf\\(|associate|sorted|shuffled|MessageDigest|SecretKeyFactory|Normalizer|readBytes|getResourceAsStream" entropy-core/src/main/kotlin entropy-core/build.gradle.kts gradle/libs.versions.toml`
- Independent Python script for rejection constants and BIP39 derivation.
- Temporary Java driver calling `MnemonicPipelineKt.deriveMnemonic(...)` from
  the compiled jar for comparison.
