package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expected values cross-checked against an independent from-scratch
 * Python implementation of the full mnemonic-to-address pipeline (BIP39
 * seed via stdlib pbkdf2_hmac, BIP32 via the `ecdsa` package for point
 * math only, address/xpub encoding hand-written from the BIP44/49/84 and
 * SLIP-132 specs) — not trusted from memory, per the same reasoning as
 * Bip32Test.kt and Bech32Test.kt. The mnemonic itself (the well-known
 * all-"abandon" 12-word test phrase) and its resulting addresses also
 * happen to match widely published reference values (e.g. iancoleman.io's
 * default BIP39 tool example), which is a useful sanity check but not
 * what these assertions rely on.
 */
class WalletDerivationTest {

    private val testMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        .split(" ")

    @Test
    fun `legacy BIP44 account 0 mainnet`() {
        val result = deriveWalletAccountKeys(testMnemonic, "", WalletScriptType.LEGACY, WalletNetwork.MAINNET, 0)
        assertEquals("m/44'/0'/0'", result.derivationPath)
        assertEquals(
            "xpub6BosfCnifzxcFwrSzQiqu2DBVTshkCXacvNsWGYJVVhhawA7d4R5WSWGFNbi8Aw6ZRc1brxMyWMzG3DSSSSoekkudhUd9yLb6qx39T9nMdj",
            result.extendedPublicKey,
        )
        assertEquals("1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA", result.firstReceiveAddress)
    }

    @Test
    fun `nested segwit BIP49 account 0 mainnet`() {
        val result = deriveWalletAccountKeys(testMnemonic, "", WalletScriptType.NESTED_SEGWIT, WalletNetwork.MAINNET, 0)
        assertEquals("m/49'/0'/0'", result.derivationPath)
        assertEquals(
            "ypub6Ww3ibxVfGzLrAH1PNcjyAWenMTbbAosGNB6VvmSEgytSER9azLDWCxoJwW7Ke7icmizBMXrzBx9979FfaHxHcrArf3zbeJJJUZPf663zsP",
            result.extendedPublicKey,
        )
        assertEquals("37VucYSaXLCAsxYyAPfbSi9eh4iEcbShgf", result.firstReceiveAddress)
    }

    @Test
    fun `native segwit BIP84 account 0 mainnet`() {
        val result = deriveWalletAccountKeys(testMnemonic, "", WalletScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0)
        assertEquals("m/84'/0'/0'", result.derivationPath)
        assertEquals(
            "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs",
            result.extendedPublicKey,
        )
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", result.firstReceiveAddress)
    }

    @Test
    fun `testnet uses a different coin type and address prefixes`() {
        val result = deriveWalletAccountKeys(testMnemonic, "", WalletScriptType.NATIVE_SEGWIT, WalletNetwork.TESTNET, 0)
        assertEquals("m/84'/1'/0'", result.derivationPath)
        assertEquals("v", result.extendedPublicKey.take(1))
        assertEquals("tb1", result.firstReceiveAddress.take(3))
    }

    @Test
    fun `a passphrase changes every derived value`() {
        val withoutPassphrase = deriveWalletAccountKeys(testMnemonic, "", WalletScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0)
        val withPassphrase = deriveWalletAccountKeys(testMnemonic, "correct horse battery staple", WalletScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0)
        assertTrue(withoutPassphrase.firstReceiveAddress != withPassphrase.firstReceiveAddress)
        assertTrue(withoutPassphrase.extendedPublicKey != withPassphrase.extendedPublicKey)
    }

    @Test
    fun `account index changes the derived xpub and address`() {
        val account0 = deriveWalletAccountKeys(testMnemonic, "", WalletScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0)
        val account1 = deriveWalletAccountKeys(testMnemonic, "", WalletScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 1)
        assertEquals("m/84'/0'/1'", account1.derivationPath)
        assertTrue(account0.firstReceiveAddress != account1.firstReceiveAddress)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a negative account index`() {
        deriveWalletAccountKeys(testMnemonic, "", WalletScriptType.LEGACY, WalletNetwork.MAINNET, -1)
    }
}
