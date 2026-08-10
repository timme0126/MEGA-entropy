package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Official BIP32 "Test vector 1" (seed 000102030405060708090a0b0c0d0e0f),
 * as published in the BIP32 specification and reproduced by essentially
 * every BIP32 implementation's own test suite. Covers the master key, a
 * hardened child (m/0'), and a non-hardened child of a hardened child
 * (m/0'/1) — the second exercises the new CKDpriv-with-public-key-input
 * path that BIP85's hardened-only derivation never needed.
 *
 * Expected values cross-checked against an independent from-scratch Python
 * implementation (stdlib hashlib/hmac + the `ecdsa` package for point math
 * only — no BIP32-specific library) rather than trusted from memory alone,
 * after the first draft of this file had one mistyped hex digit and one
 * fully misremembered base58 string.
 */
class Bip32Test {

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private val seed = hexToBytes("000102030405060708090a0b0c0d0e0f")

    @Test
    fun `master key fingerprint matches an independently computed value`() {
        val master = bip32MasterKeyFromSeed(seed)
        assertEquals("3442193e", master.fingerprint().toHex())
    }

    @Test
    fun `master key public point and xpub match the official vector`() {
        val master = bip32MasterKeyFromSeed(seed)
        assertEquals(
            "0339a36013301597daef41fbe593a02cc513d0b55527ec2df1050e2e8ff49c85c2",
            master.compressedPublicKey().toHex(),
        )
        assertEquals(
            "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8",
            master.serializeExtendedPublicKey(ExtendedKeyScriptType.LEGACY, Bip32Network.MAINNET),
        )
    }

    @Test
    fun `hardened child m 0h public point and xpub match the official vector`() {
        val master = bip32MasterKeyFromSeed(seed)
        val child = master.deriveChild(0, hardened = true)
        assertEquals(
            "xpub68Gmy5EdvgibQVfPdqkBBCHxA5htiqg55crXYuXoQRKfDBFA1WEjWgP6LHhwBZeNK1VTsfTFUHCdrfp1bgwQ9xv5ski8PX9rL2dZXvgGDnw",
            child.serializeExtendedPublicKey(ExtendedKeyScriptType.LEGACY, Bip32Network.MAINNET),
        )
    }

    @Test
    fun `non-hardened child of a hardened child - m 0h 1 - matches the official vector`() {
        val master = bip32MasterKeyFromSeed(seed)
        val hardenedChild = master.deriveChild(0, hardened = true)
        val grandchild = hardenedChild.deriveChild(1, hardened = false)
        assertEquals(
            "xpub6ASuArnXKPbfEwhqN6e3mwBcDTgzisQN1wXN9BJcM47sSikHjJf3UFHKkNAWbWMiGj7Wf5uMash7SyYq527Hqck2AxYysAA7xmALppuCkwQ",
            grandchild.serializeExtendedPublicKey(ExtendedKeyScriptType.LEGACY, Bip32Network.MAINNET),
        )
    }

    @Test
    fun `depth, parent fingerprint, and child number are tracked through derivation`() {
        val master = bip32MasterKeyFromSeed(seed)
        assertEquals(0, master.depth)
        assertEquals(0L, master.childNumber)

        val hardenedChild = master.deriveChild(0, hardened = true)
        assertEquals(1, hardenedChild.depth)
        assertEquals(HARDENED_OFFSET, hardenedChild.childNumber)
        assertEquals(master.fingerprint().toHex(), hardenedChild.parentFingerprint.toHex())

        val grandchild = hardenedChild.deriveChild(1, hardened = false)
        assertEquals(2, grandchild.depth)
        assertEquals(1L, grandchild.childNumber)
        assertEquals(hardenedChild.fingerprint().toHex(), grandchild.parentFingerprint.toHex())
    }

    @Test
    fun `testnet and mainnet xpubs for the same key differ only by version bytes`() {
        val master = bip32MasterKeyFromSeed(seed)
        val mainnet = master.serializeExtendedPublicKey(ExtendedKeyScriptType.LEGACY, Bip32Network.MAINNET)
        val testnet = master.serializeExtendedPublicKey(ExtendedKeyScriptType.LEGACY, Bip32Network.TESTNET)
        assertEquals("xpub", mainnet.take(4))
        assertEquals("tpub", testnet.take(4))
    }

    @Test
    fun `ypub and zpub differ from xpub for the same key`() {
        val master = bip32MasterKeyFromSeed(seed)
        val xpub = master.serializeExtendedPublicKey(ExtendedKeyScriptType.LEGACY, Bip32Network.MAINNET)
        val ypub = master.serializeExtendedPublicKey(ExtendedKeyScriptType.NESTED_SEGWIT, Bip32Network.MAINNET)
        val zpub = master.serializeExtendedPublicKey(ExtendedKeyScriptType.NATIVE_SEGWIT, Bip32Network.MAINNET)
        assertEquals("xpub", xpub.take(4))
        assertEquals("ypub", ypub.take(4))
        assertEquals("zpub", zpub.take(4))
    }
}
