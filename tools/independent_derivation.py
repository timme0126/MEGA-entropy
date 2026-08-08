#!/usr/bin/env python3
"""Independent MEGA dice-to-BIP39 derivation test-vector checker.

This script was written only from the local docs and the public BIP39
derivation rules. It does not import any MEGA app code.
"""

from __future__ import annotations

import argparse
import hashlib
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parent.parent
WORDLIST_PATH = ROOT / "entropy-core" / "src" / "main" / "resources" / "bip39" / "english.txt"
WORDLIST_SHA256_PATH = ROOT / "entropy-core" / "src" / "main" / "resources" / "bip39" / "english.txt.sha256"


EXPECTED_ZERO_256 = (
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon abandon abandon abandon abandon art"
)

EXPECTED_BIP39_256 = {
    "7f" * 32: (
        "legal winner thank year wave sausage worth useful "
        "legal winner thank year wave sausage worth useful "
        "legal winner thank year wave sausage worth title"
    ),
    "80" * 32: (
        "letter advice cage absurd amount doctor acoustic avoid "
        "letter advice cage absurd amount doctor acoustic avoid "
        "letter advice cage absurd amount doctor acoustic bless"
    ),
    "ff" * 32: (
        "zoo zoo zoo zoo zoo zoo zoo zoo "
        "zoo zoo zoo zoo zoo zoo zoo zoo "
        "zoo zoo zoo zoo zoo zoo zoo vote"
    ),
}


@dataclass(frozen=True)
class DiceDerivation:
    rolls: tuple[int, ...]
    ent_bits: int
    digits: tuple[int, ...]
    x: int
    threshold: int
    accepted: bool
    entropy_hex: str | None
    checksum_bits: str | None
    indices: tuple[int, ...] | None
    mnemonic: str | None


def load_wordlist() -> list[str]:
    data = WORDLIST_PATH.read_bytes()
    actual = hashlib.sha256(data).hexdigest()
    expected = WORDLIST_SHA256_PATH.read_text(encoding="utf-8").strip().split()[0]
    if actual != expected:
        raise ValueError(f"wordlist SHA-256 mismatch: expected {expected}, actual {actual}")

    words = WORDLIST_PATH.read_text(encoding="utf-8").splitlines()
    if len(words) != 2048:
        raise ValueError(f"wordlist must contain 2048 lines, found {len(words)}")
    if any(not word for word in words):
        raise ValueError("wordlist contains a blank line")
    if len(set(words)) != len(words):
        raise ValueError("wordlist contains duplicate entries")
    return words


def rolls_to_base6_digits(rolls: Iterable[int]) -> tuple[int, ...]:
    values = tuple(rolls)
    invalid = [roll for roll in values if roll < 1 or roll > 6]
    if invalid:
        raise ValueError(f"rolls must all be in 1..6, found {invalid}")
    return tuple(roll - 1 for roll in values)


def base6_digits_to_int(digits: Iterable[int]) -> int:
    x = 0
    for digit in digits:
        if digit < 0 or digit > 5:
            raise ValueError(f"base-6 digits must be in 0..5, found {digit}")
        x = x * 6 + digit
    return x


def rejection_threshold(num_rolls: int, ent_bits: int) -> int:
    target_size = 1 << ent_bits
    return (pow(6, num_rolls) // target_size) * target_size


def entropy_bytes_from_x(x: int, ent_bits: int) -> bytes:
    if ent_bits not in (128, 256):
        raise ValueError("this checker supports BIP39 ENT sizes 128 and 256")
    return (x % (1 << ent_bits)).to_bytes(ent_bits // 8, byteorder="big", signed=False)


def checksum_bits(entropy: bytes) -> str:
    ent_bits = len(entropy) * 8
    cs_bits = ent_bits // 32
    digest_int = int.from_bytes(hashlib.sha256(entropy).digest(), byteorder="big")
    return f"{digest_int:0256b}"[:cs_bits]


def entropy_to_indices(entropy: bytes) -> tuple[int, ...]:
    ent_bits = len(entropy) * 8
    if ent_bits not in (128, 256):
        raise ValueError("this checker supports BIP39 ENT sizes 128 and 256")
    bits = "".join(f"{byte:08b}" for byte in entropy) + checksum_bits(entropy)
    if len(bits) % 11 != 0:
        raise ValueError(f"BIP39 bitstream length is not divisible by 11: {len(bits)}")
    return tuple(int(bits[i : i + 11], 2) for i in range(0, len(bits), 11))


def entropy_to_mnemonic(entropy: bytes, words: list[str]) -> str:
    return " ".join(words[index] for index in entropy_to_indices(entropy))


def derive_from_rolls(rolls: Iterable[int], ent_bits: int, words: list[str]) -> DiceDerivation:
    roll_tuple = tuple(rolls)
    digits = rolls_to_base6_digits(roll_tuple)
    x = base6_digits_to_int(digits)
    threshold = rejection_threshold(len(digits), ent_bits)
    accepted = x < threshold
    entropy_hex = None
    checksum = None
    indices = None
    mnemonic = None
    if accepted:
        entropy = entropy_bytes_from_x(x, ent_bits)
        entropy_hex = entropy.hex()
        checksum = checksum_bits(entropy)
        indices = entropy_to_indices(entropy)
        mnemonic = entropy_to_mnemonic(entropy, words)
    return DiceDerivation(
        rolls=roll_tuple,
        ent_bits=ent_bits,
        digits=digits,
        x=x,
        threshold=threshold,
        accepted=accepted,
        entropy_hex=entropy_hex,
        checksum_bits=checksum,
        indices=indices,
        mnemonic=mnemonic,
    )


def status(ok: bool) -> str:
    return "PASS" if ok else "FAIL"


def print_vector_results(words: list[str]) -> bool:
    overall = True

    print("WORDLIST")
    actual_hash = hashlib.sha256(WORDLIST_PATH.read_bytes()).hexdigest()
    expected_hash = WORDLIST_SHA256_PATH.read_text(encoding="utf-8").strip().split()[0]
    print(f"expected sha256: {expected_hash}")
    print(f"actual sha256:   {actual_hash}")
    print(f"line count:      {len(words)}")
    print(f"status:          {status(actual_hash == expected_hash and len(words) == 2048)}")
    print()

    print("VECTOR 1 - 5-roll worked example")
    rolls = (2, 4, 3, 6, 4)
    digits = rolls_to_base6_digits(rolls)
    chunk = base6_digits_to_int(digits)
    ok = digits == (1, 3, 2, 5, 3) and chunk == 2049
    overall = overall and ok
    print("expected base6 digits: 1 3 2 5 3")
    print(f"actual base6 digits:   {' '.join(map(str, digits))}")
    print("expected chunk value:  2049")
    print(f"actual chunk value:    {chunk}")
    print(f"status:                {status(ok)}")
    print()

    print("VECTOR 2 - minimum entropy, 100 rolls of 1")
    v2 = derive_from_rolls([1] * 100, 256, words)
    ok = (
        v2.accepted
        and v2.x == 0
        and v2.entropy_hex == "00" * 32
        and hashlib.sha256(bytes(32)).hexdigest()
        == "66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925"
        and v2.mnemonic == EXPECTED_ZERO_256
    )
    overall = overall and ok
    print("expected decision:     accepted")
    print(f"actual decision:       {'accepted' if v2.accepted else 'rejected'}")
    print(f"expected X:            0")
    print(f"actual X:              {v2.x}")
    print(f"expected entropy hex:  {'00' * 32}")
    print(f"actual entropy hex:    {v2.entropy_hex}")
    print("expected sha256(E):    66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925")
    print(f"actual sha256(E):      {hashlib.sha256(bytes.fromhex(v2.entropy_hex or '')).hexdigest() if v2.entropy_hex else None}")
    print(f"expected mnemonic:     {EXPECTED_ZERO_256}")
    print(f"actual mnemonic:       {v2.mnemonic}")
    print(f"status:                {status(ok)}")
    print()

    print("VECTOR 3 - maximum value, 100 rolls of 6")
    v3 = derive_from_rolls([6] * 100, 256, words)
    expected_x = pow(6, 100) - 1
    ok = (not v3.accepted) and v3.x == expected_x and v3.threshold == 5 * (1 << 256)
    overall = overall and ok
    print("expected decision:     rejected")
    print(f"actual decision:       {'accepted' if v3.accepted else 'rejected'}")
    print(f"expected X:            6^100 - 1 = {expected_x}")
    print(f"actual X:              {v3.x}")
    print(f"expected threshold T:  {5 * (1 << 256)}")
    print(f"actual threshold T:    {v3.threshold}")
    print(f"actual mnemonic:       {v3.mnemonic}")
    print(f"status:                {status(ok)}")
    print()

    print("VECTOR 4 - rejection boundary")
    threshold = rejection_threshold(100, 256)
    checks = [
        ("T - 1", threshold - 1, True),
        ("T", threshold, False),
        ("T + 1", threshold + 1, False),
    ]
    ok = True
    print(f"threshold T:           {threshold}")
    for label, x, expected_accept in checks:
        actual_accept = x < threshold
        line_ok = actual_accept == expected_accept
        ok = ok and line_ok
        print(
            f"{label:5} expected: {'accepted' if expected_accept else 'rejected':8} "
            f"actual: {'accepted' if actual_accept else 'rejected':8} status: {status(line_ok)}"
        )
    overall = overall and ok
    print(f"status:                {status(ok)}")
    print()

    print("VECTOR 5 - official BIP39 256-bit entropy-to-words vectors")
    ok = True
    for entropy_hex, expected_mnemonic in EXPECTED_BIP39_256.items():
        actual_mnemonic = entropy_to_mnemonic(bytes.fromhex(entropy_hex), words)
        line_ok = actual_mnemonic == expected_mnemonic
        ok = ok and line_ok
        print(f"entropy:               {entropy_hex}")
        print(f"expected mnemonic:     {expected_mnemonic}")
        print(f"actual mnemonic:       {actual_mnemonic}")
        print(f"status:                {status(line_ok)}")
    overall = overall and ok
    print(f"vector 5 aggregate:    {status(ok)}")
    print()

    print("EXTRA WORKED EXAMPLES")
    examples = [
        ("extra-1 256-bit alternating 1/6", [1, 6] * 50, 256),
        ("extra-2 256-bit repeating 1..6", ([1, 2, 3, 4, 5, 6] * 17)[:100], 256),
        ("extra-3 128-bit repeating 2,4,6,1,3,5", ([2, 4, 6, 1, 3, 5] * 9)[:50], 128),
    ]
    for name, example_rolls, ent_bits in examples:
        result = derive_from_rolls(example_rolls, ent_bits, words)
        print(name)
        print(f"roll count:            {len(example_rolls)}")
        print(f"entropy bits:          {ent_bits}")
        print(f"decision:              {'accepted' if result.accepted else 'rejected'}")
        print(f"X:                     {result.x}")
        print(f"threshold T:           {result.threshold}")
        print(f"entropy hex:           {result.entropy_hex}")
        print(f"checksum bits:         {result.checksum_bits}")
        print(f"indices:               {' '.join(map(str, result.indices)) if result.indices else None}")
        print(f"mnemonic:              {result.mnemonic}")
        print()

    print(f"OVERALL STATUS:        {status(overall)}")
    return overall


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="run the documented vectors and extra examples",
    )
    args = parser.parse_args()

    words = load_wordlist()
    ok = print_vector_results(words)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
