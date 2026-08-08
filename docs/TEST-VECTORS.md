# Test Vectors

Worked examples you can independently re-derive — by hand, with a
calculator, or with a completely separate implementation — to verify MEGA's
math without trusting this codebase.

## Vector 1: the spec's 5-roll worked example

Physical rolls: `2 4 3 6 4`

| Step | Value |
|---|---|
| Mapped to base-6 (`r - 1`) | `1 3 2 5 3` |
| As one base-6 number | `13253₆` |
| Expanded | `1×6⁴ + 3×6³ + 2×6² + 5×6¹ + 3×6⁰` |
| = | `1296 + 648 + 72 + 30 + 3` |
| Chunk value | `2049` |

Batch accumulator, starting from `X0 = 0`:

```
0 × 7776 + 2049 = 2049
```

Reproduced in code by `BatchAccumulatorTest.kt` (`calculateChunk works on
exact spec example listOf(1,3,2,5,3)` and `accumulate handles first batch
step from ZERO`).

## Vector 2: minimum entropy (100 rolls of `1`)

100 physical rolls, all `1` → 100 base-6 digits, all `0` → `X = 0`.

- `X = 0 < T`, so this sequence is **accepted**.
- `E = 0 mod 2^256 = 0` → 32 zero bytes →
  `0000000000000000000000000000000000000000000000000000000000000000`
  (64 hex chars — count them, it's exactly 64).
- `SHA-256(E)` (32 zero bytes) =
  `66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925`
- First byte of that digest is `0x66` = `01100110` — the 8 checksum bits.
- The resulting 24-word mnemonic:

  ```
  abandon abandon abandon abandon abandon abandon abandon abandon
  abandon abandon abandon abandon abandon abandon abandon abandon
  abandon abandon abandon abandon abandon abandon abandon art
  ```

  (23 × `abandon`, then `art`.) This is the well-known "all-zero entropy"
  BIP39 test vector used across the ecosystem (see e.g.
  `trezor/python-mnemonic`'s `vectors.json`) — MEGA's dice pipeline
  produces exactly this same mnemonic when it happens to derive zero
  entropy, which is a strong cross-check that the checksum/word-derivation
  half of the pipeline is standard-compliant. Reproduced in code by
  `SmokeTest.kt` (`all rolls of one is the minimum X and must be
  accepted`) and independently by `Bip39VectorsTest.kt`'s direct
  entropy→words test using this same all-zero vector.

## Vector 3: maximum value, must be rejected (100 rolls of `6`)

100 physical rolls, all `6` → 100 base-6 digits, all `5` → `X = 6^100 - 1`,
the largest possible value. Since `6^100 - 1 ≥ T`, this sequence is
**rejected** — MEGA never derives entropy, a checksum, or words from it.
Reproduced by `SmokeTest.kt` (`all rolls of six is the maximum X and must
be rejected`) and `RejectionSamplingTest.kt`.

## Vector 4: the rejection boundary itself

Using the threshold `T = 5 × 2^256` computed by
`entropy-core/.../RejectionSampling.kt`:

| X | Result |
|---|---|
| `T - 1` | **Accepted** |
| `T` | **Rejected** |
| `T + 1` | **Rejected** |

Reproduced by `RejectionSamplingTest.kt`.

## Vector 5: additional official BIP39 vectors (entropy → words only)

These bypass the dice/rejection-sampling layer and test only the
entropy→checksum→words half of the pipeline, against vectors published
independently of this implementation (source:
`trezor/python-mnemonic`'s `vectors.json`). Reproduced by
`Bip39VectorsTest.kt`.

| Entropy (hex) | Mnemonic |
|---|---|
| `7f7f7f...7f7f` (32× `0x7f`) | `legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth title` |
| `8080...8080` (32× `0x80`) | `letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic bless` |
| `ffff...ffff` (32× `0xff`) | `zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote` |

## Reproducing these yourself

You don't need MEGA or even Kotlin to check vectors 2/3/5 — any BIP39
library (or a short Python script using `hashlib.sha256`) that accepts raw
entropy bytes and emits a mnemonic will reproduce them, since that half of
the derivation is standard BIP39, not MEGA-specific. Vector 1 (batch
accumulator arithmetic) and vector 4 (the exact rejection threshold) are
straightforward integer arithmetic you can check with any big-integer
calculator — see [`docs/ENTROPY-MATH.md`](ENTROPY-MATH.md) for the full
derivation of `T`.
