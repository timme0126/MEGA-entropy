# Codex Independent Derivation Report

## Scope Check

I searched the working tree before implementing. The reachable files were only
the documentation and wordlist files shown below; I did not find MEGA app source
code in this directory.

```text
$ pwd && rg --files -uu
/tmp/isolated-mega-independent-derivation
docs/BIP39-DERIVATION.md
docs/TEST-VECTORS.md
docs/NO-RNG-PROOF.md
docs/ENTROPY-MATH.md
wordlist/english.txt.sha256
wordlist/english.txt
```

## Implementation

The independent Python 3 implementation is saved as `independent_derivation.py`.
It implements:

- physical roll `1..6` to base-6 digit `0..5` mapping with `digit = roll - 1`
- most-significant-digit-first base-6 integer accumulation
- rejection threshold `T = floor(6^rollCount / 2^ENT) * 2^ENT`
- accept/reject by `X < T`
- entropy extraction as `X mod 2^ENT`, encoded as exact-length unsigned
  big-endian bytes
- BIP39 checksum as the first `ENT/32` bits of `SHA-256(entropy)`, MSB-first
- 11-bit group splitting and direct zero-based BIP39 wordlist lookup
- both 128-bit/12-word and 256-bit/24-word BIP39 cases

The script verifies `wordlist/english.txt` against
`wordlist/english.txt.sha256` before using it.

## Pass/Fail Summary

| Vector | Description | Result |
|---|---|---|
| 1 | 5-roll worked base-6/chunk example | PASS |
| 2 | 100 rolls of `1`, accepted all-zero entropy mnemonic | PASS |
| 3 | 100 rolls of `6`, maximum value rejected | PASS |
| 4 | Rejection boundary `T - 1`, `T`, `T + 1` | PASS |
| 5 | Three official 256-bit BIP39 entropy-to-words vectors | PASS |

No vector failed, so there are no discrepancies to analyze.

## Actual Command Output

```text
$ python3 independent_derivation.py --self-test
WORDLIST
expected sha256: 2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda
actual sha256:   2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda
line count:      2048
status:          PASS

VECTOR 1 - 5-roll worked example
expected base6 digits: 1 3 2 5 3
actual base6 digits:   1 3 2 5 3
expected chunk value:  2049
actual chunk value:    2049
status:                PASS

VECTOR 2 - minimum entropy, 100 rolls of 1
expected decision:     accepted
actual decision:       accepted
expected X:            0
actual X:              0
expected entropy hex:  0000000000000000000000000000000000000000000000000000000000000000
actual entropy hex:    0000000000000000000000000000000000000000000000000000000000000000
expected sha256(E):    66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925
actual sha256(E):      66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925
expected mnemonic:     abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art
actual mnemonic:       abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art
status:                PASS

VECTOR 3 - maximum value, 100 rolls of 6
expected decision:     rejected
actual decision:       rejected
expected X:            6^100 - 1 = 653318623500070906096690267158057820537143710472954871543071966369497141477375
actual X:              653318623500070906096690267158057820537143710472954871543071966369497141477375
expected threshold T:  578960446186580977117854925043439539266349923328202820197287920039565648199680
actual threshold T:    578960446186580977117854925043439539266349923328202820197287920039565648199680
actual mnemonic:       None
status:                PASS

VECTOR 4 - rejection boundary
threshold T:           578960446186580977117854925043439539266349923328202820197287920039565648199680
T - 1 expected: accepted actual: accepted status: PASS
T     expected: rejected actual: rejected status: PASS
T + 1 expected: rejected actual: rejected status: PASS
status:                PASS

VECTOR 5 - official BIP39 256-bit entropy-to-words vectors
entropy:               7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f
expected mnemonic:     legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth title
actual mnemonic:       legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth title
status:                PASS
entropy:               8080808080808080808080808080808080808080808080808080808080808080
expected mnemonic:     letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic bless
actual mnemonic:       letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic bless
status:                PASS
entropy:               ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
expected mnemonic:     zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote
actual mnemonic:       zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote
status:                PASS
vector 5 aggregate:    PASS

EXTRA WORKED EXAMPLES
extra-1 256-bit alternating 1/6
roll count:            100
entropy bits:          256
decision:              accepted
X:                     93331231928581558013812895308293974362449101496136410220438852338499591639625
threshold T:           578960446186580977117854925043439539266349923328202820197287920039565648199680
entropy hex:           ce579af33510ee7c3e4da3567cc11e7afe3708b9249249249249249249249249
checksum bits:         01100011
indices:               1650 1510 1510 849 119 496 1993 1443 691 1840 572 1967 1819 1058 1828 1170 585 292 1170 585 292 1170 585 355
mnemonic:              soft rubber rubber health auction dignity weird refuse filter toward elegant vote toddler lounge tonight myself empty cause myself empty cause myself empty cluster

extra-2 256-bit repeating 1..6
roll count:            100
entropy bits:          256
decision:              accepted
X:                     26115941117300015858328739647407091100670303719474029266484390039205062026691
threshold T:           578960446186580977117854925043439539266349923328202820197287920039565648199680
entropy hex:           39bd194e3b989d612e6ed5bf485bae130d53f5f532f29585e98ecd298282a5c3
checksum bits:         11111001
indices:               461 1862 668 953 1102 1412 1485 1749 1530 534 1884 304 1705 2007 1702 754 1196 378 797 1234 1217 522 1208 1017
mnemonic:              defy trip fatal jaguar mean rack rifle survey satisfy drift twist champion steel wife state furnace night consider glove olympic oblige donor novel left

extra-3 128-bit repeating 2,4,6,1,3,5
roll count:            50
entropy bits:          128
decision:              accepted
X:                     221062460624807403575318000253464861289
threshold T:           680564733841876926926749214863536422912
entropy hex:           a64f14ccc82bbfc332f9234128bdda69
checksum bits:         0100
indices:               1330 965 409 1154 1503 1804 1631 291 521 559 948 1684
mnemonic:              play judge creek motion room thunder slogan caught donkey echo isolate spoon

OVERALL STATUS:        PASS
```
