# Entropy Math

The reasoning behind every step of MEGA's dice-to-entropy pipeline, in more
depth than fits on the in-app "How It Works" screen
(`app/src/main/kotlin/org/mega/entropy/ui/howitworks/HowItWorksScreen.kt`,
which this document expands on).

## Why 100 rolls?

A fair six-sided die has 6 possible outcomes per roll, or `log2(6) ≈
2.585` bits of information per roll. 100 rolls therefore carry:

```
log2(6^100) ≈ 258.5 bits
```

BIP39 needs exactly 256 bits of entropy for a 24-word mnemonic. 258.5 bits
of raw material is enough to extract a uniform 256-bit value via rejection
sampling (below) — but 99 rolls would not be:

```
log2(6^99) ≈ 256.0 bits
```

That's too close to 256 to guarantee an unbiased extraction with any
margin — with only ~0 bits of slack, rejection sampling could reject an
impractically large fraction of sequences, or in the worst case leave no
usable range at all. 100 rolls gives about 2.5 bits of margin, translating
to the ~1-in-8 rejection rate described below.

## Why map physical rolls (1–6) to base-6 digits (0–5)?

Positional number systems need digits starting at 0. A die shows 1 through
6; base-6 digits run 0 through 5. `d_i = r_i - 1` is the only
transformation MEGA applies to a raw roll, and it's a fixed relabeling —
not a randomizing or lossy step. Roll 1 always becomes digit 0, roll 6
always becomes digit 5, for every roll, every time.

## Why rejection sampling? A worked toy example

Suppose you had a fair 3-sided die (outcomes 0, 1, 2) and wanted a fair
coin flip from it by computing `outcome mod 2`:

```
0 mod 2 = 0
1 mod 2 = 1
2 mod 2 = 0
```

`0` comes up twice as often as `1` — that's modulo bias. The fix is to
reject the outcome that breaks the symmetry (here, reject `2` and re-roll)
so only `0` and `1` remain, each equally likely.

100 dice rolls give `6^100` possible sequences, and `6^100` is **not** an
exact multiple of `2^256`. Taking `X mod 2^256` directly would make some
256-bit outputs slightly more likely than others — exactly the same bias as
the toy example, just at a much larger scale.

MEGA instead computes the largest multiple of `2^256` that fits inside
`6^100`:

```
T = floor(6^100 / 2^256) × 2^256
```

Working this division out, `floor(6^100 / 2^256) = 5`, so:

```
T = 5 × 2^256
```

Every value of `X` from `0` up to `T - 1` falls into exactly one of five
complete, equal-sized blocks of `2^256` consecutive values. Any `X ≥ T`
falls into the leftover partial block — the source of the bias — and is
rejected outright, requiring a completely new 100-roll sequence (never a
partial retry, a hash-based workaround, or a modified `X`; see
`entropy-core/src/main/kotlin/org/mega/entropycore/RejectionSampling.kt`).

Because `6^100 / T ≈ 1.1284`, `(6^100 - T) / 6^100 ≈ 11.38%` of
mathematically valid 100-roll sequences get rejected — the "about 1 in 8"
(11–12%) figure shown throughout the app.

For an accepted `X`, `E = X mod 2^256` is then exactly uniform over all
2²⁵⁶ possible 256-bit values, because it's uniform over each of the five
equal-sized blocks and each block maps onto the same output range.

## Why SHA-256?

BIP-0039 specifies that a mnemonic's checksum is the first `ENT/32` bits of
`SHA-256(entropy)`, where `ENT` is the entropy length in bits. For 256-bit
entropy, that's an 8-bit checksum. SHA-256 here is a **fixed, deterministic
function** of the entropy bytes — the same entropy always produces the same
checksum, on any correct implementation, forever. It adds no randomness;
see [`docs/NO-RNG-PROOF.md`](NO-RNG-PROOF.md) for why this distinction
matters.

## Why 24 words?

```
ENT = 256                  (entropy bits)
CS  = ENT / 32 = 8         (checksum bits, per BIP-0039)
ENT + CS = 256 + 8 = 264   (total bitstream length)
264 / 11 = 24              (11-bit groups, since BIP39 words each encode 11 bits)
```

264 divides evenly by 11, giving exactly 24 words.

## Why 2048 words?

Each BIP39 word encodes one 11-bit value, and 11 bits can represent exactly
`2^11 = 2048` distinct values — so the official BIP39 English word list has
exactly 2048 entries, one per possible value, indexed 0 through 2047. See
[`docs/BIP39-DERIVATION.md`](BIP39-DERIVATION.md) for how that list is
vendored and verified.
