package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Bip85Test {
    private val vectorRootXprv = "xprv9s21ZrQH143K2LBWUUQRFXhucrQqBpKdRRxNVq2zBqsx8HVqFk2uYo8kmbaLLHRdqtQpUm98uKfu3vca1LqdGhUtyoFnCNkfmXRyPXLjbKb"

    @Test
    fun `official BIP85 vector derives 12 English words at index 0`() {
        val result = deriveBip85Bip39Mnemonic(vectorRootXprv, Bip85MnemonicWords.TWELVE, 0)

        assertEquals("m/83696968'/39'/0'/12'/0'", result.path)
        assertEquals("6250b68daf746d12a24d58b4787a714b", result.entropy.hex)
        assertEquals(
            "girl mad pet galaxy egg matter matrix prison refuse sense ordinary nose",
            result.mnemonicWords.joinToString(" "),
        )
    }

    @Test
    fun `official BIP85 vector derives 24 English words at index 0`() {
        val result = deriveBip85Bip39Mnemonic(vectorRootXprv, Bip85MnemonicWords.TWENTY_FOUR, 0)

        assertEquals("m/83696968'/39'/0'/24'/0'", result.path)
        assertEquals("ae131e2312cdc61331542efe0d1077bac5ea803adf24b313a4f0e48e9c51f37f", result.entropy.hex)
        assertEquals(
            "puppy ocean match cereal symbol another shed magic wrap hammer bulb intact gadget divorce twin tonight reason outdoor destroy simple truth cigar social volcano",
            result.mnemonicWords.joinToString(" "),
        )
    }

    @Test
    fun `index changes child mnemonic deterministically`() {
        val indexZero = deriveBip85Bip39Mnemonic(vectorRootXprv, Bip85MnemonicWords.TWELVE, 0)
        val indexOne = deriveBip85Bip39Mnemonic(vectorRootXprv, Bip85MnemonicWords.TWELVE, 1)

        assertEquals(12, indexOne.mnemonicWords.size)
        assertTrue(indexZero.entropy.hex != indexOne.entropy.hex)
        assertTrue(indexZero.mnemonicWords != indexOne.mnemonicWords)
    }


    @Test
    fun `24-word parent with BIP39 passphrase derives standard 24-word child at index 0`() {
        val parentWords = ("abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon art").split(" ")

        val result = deriveBip85Bip39Mnemonic(
            parentWords = parentWords,
            childWords = Bip85MnemonicWords.TWENTY_FOUR,
            index = 0,
            parentPassphrase = "TREZOR",
        )

        assertEquals("m/83696968'/39'/0'/24'/0'", result.path)
        assertEquals("a0ea8bf0460a0aa19dd7dcbc52feeafce71dbfb400316619830a80549df5816a", result.entropy.hex)
        assertEquals(
            "path february winter metal pass express jar wine rough obey rival what impact thank source alert gravity slot section absent endorse width aisle flag",
            result.mnemonicWords.joinToString(" "),
        )
    }


    @Test
    fun `prefix parent words resolve before BIP85 seed derivation`() {
        val fullParentWords = ("abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon art").split(" ")
        val prefixParentWords = List(23) { "aban" } + "art"

        val full = deriveBip85Bip39Mnemonic(
            parentWords = fullParentWords,
            childWords = Bip85MnemonicWords.TWENTY_FOUR,
            index = 0,
            parentPassphrase = "TREZOR",
        )
        val prefixed = deriveBip85Bip39Mnemonic(
            parentWords = prefixParentWords,
            childWords = Bip85MnemonicWords.TWENTY_FOUR,
            index = 0,
            parentPassphrase = "TREZOR",
        )

        assertEquals(full.entropy.hex, prefixed.entropy.hex)
        assertEquals(full.mnemonicWords, prefixed.mnemonicWords)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects parent words with invalid BIP39 checksum`() {
        deriveBip85Bip39Mnemonic(
            parentWords = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon".split(" "),
            childWords = Bip85MnemonicWords.TWELVE,
            index = 0,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects negative index`() {
        deriveBip85Bip39Mnemonic(vectorRootXprv, Bip85MnemonicWords.TWELVE, -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects index at hardened offset`() {
        deriveBip85Bip39Mnemonic(vectorRootXprv, Bip85MnemonicWords.TWELVE, 2_147_483_648L)
    }
}
