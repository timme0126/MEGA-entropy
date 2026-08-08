package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Expected seed hex values below were computed independently with Python's
 * standard-library hashlib.pbkdf2_hmac('sha512', ...) — not copied from
 * memory or from this codebase — against the exact BIP39-spec algorithm
 * (NFKD-normalize mnemonic and passphrase, salt = "mnemonic" + passphrase,
 * 2048 iterations, 64-byte output), using the mnemonics this project's own
 * vendored word list produces for the given entropy. See Bip39VectorsTest
 * for the equivalent independently-sourced mnemonic-derivation vectors.
 */
class SeedDerivationTest {

    private val allZero24Words = ("abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon art").split(" ")

    private val allZero12Words = ("abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
        "abandon abandon about").split(" ")

    private val allFf24Words = ("zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo " +
        "zoo zoo zoo zoo zoo vote").split(" ")

    @Test
    fun `24-word all-zero-entropy mnemonic with TREZOR passphrase`() {
        val seed = deriveSeed(allZero24Words, "TREZOR")
        assertEquals(
            "bda85446c68413707090a52022edd26a1c9462295029f2e60cd7c4f2bbd3097170af7a" +
                "4d73245cafa9c3cca8d561a7c3de6f5d4a10be8ed2a5e608d68f92fcc8",
            seed.hex,
        )
    }

    @Test
    fun `24-word all-zero-entropy mnemonic with no passphrase`() {
        val seed = deriveSeed(allZero24Words)
        assertEquals(
            "408b285c123836004f4b8842c89324c1f01382450c0d439af345ba7fc49acf705489c6" +
                "fc77dbd4e3dc1dd8cc6bc9f043db8ada1e243c4a0eafb290d399480840",
            seed.hex,
        )
    }

    @Test
    fun `12-word all-zero-entropy mnemonic with TREZOR passphrase`() {
        val seed = deriveSeed(allZero12Words, "TREZOR")
        assertEquals(
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6" +
                "987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
            seed.hex,
        )
    }

    @Test
    fun `24-word all-0xff-entropy mnemonic with TREZOR passphrase`() {
        val seed = deriveSeed(allFf24Words, "TREZOR")
        assertEquals(
            "dd48c104698c30cfe2b6142103248622fb7bb0ff692eebb00089b32d22484e1613912f" +
                "0a5b694407be899ffd31ed3992c456cdf60f5d4564b8ba3f05a69890ad",
            seed.hex,
        )
    }

    @Test
    fun `seed is always exactly 64 bytes`() {
        assertEquals(64, deriveSeed(allZero12Words, "any passphrase").bytes.size)
        assertEquals(64, deriveSeed(allZero12Words).bytes.size)
    }

    @Test
    fun `different passphrases produce different seeds for the same words`() {
        val seedA = deriveSeed(allZero12Words, "correct horse")
        val seedB = deriveSeed(allZero12Words, "battery staple")
        assertEquals(false, seedA.hex == seedB.hex)
    }
}
