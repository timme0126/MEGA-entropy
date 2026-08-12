package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-checks ExtendedPublicKey.kt (the public-key-only parser and CKDpub)
 * against the same official BIP32 "Test vector 1" already used in
 * Bip32Test.kt for the private-key side, including its own published xpub
 * strings for master, m/0', and m/0'/1 — real, independently-known-correct
 * data, not just this module's own output round-tripped through itself.
 */
class ExtendedPublicKeyTest {

    private val seed = hexToBytes("000102030405060708090a0b0c0d0e0f")

    private val masterXpub =
        "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8"
    private val hardenedChildXpub =
        "xpub68Gmy5EdvgibQVfPdqkBBCHxA5htiqg55crXYuXoQRKfDBFA1WEjWgP6LHhwBZeNK1VTsfTFUHCdrfp1bgwQ9xv5ski8PX9rL2dZXvgGDnw"
    private val grandchildXpub =
        "xpub6ASuArnXKPbfEwhqN6e3mwBcDTgzisQN1wXN9BJcM47sSikHjJf3UFHKkNAWbWMiGj7Wf5uMash7SyYq527Hqck2AxYysAA7xmALppuCkwQ"

    @Test
    fun `parseExtendedPublicKey recovers the master key's own known fields`() {
        val parsed = parseExtendedPublicKey(masterXpub)
        assertEquals(0, parsed.depth)
        assertEquals(0L, parsed.childNumber)
        assertEquals(Bip32Network.MAINNET, parsed.network)
        assertEquals(bip32MasterKeyFromSeed(seed).compressedPublicKey().toHex(), parsed.publicKey.toHex())
    }

    @Test
    fun `parseExtendedPublicKey reads a hardened child number without sign corruption`() {
        // m/0' — exactly the "top bit set" case (childNumber ==
        // HARDENED_OFFSET, 0x80000000) that assembling the four bytes as
        // an Int before converting to Long would sign-extend negative.
        val parsed = parseExtendedPublicKey(hardenedChildXpub)
        assertEquals(1, parsed.depth)
        assertEquals(HARDENED_OFFSET, parsed.childNumber)
        assertTrue("childNumber must be positive, was ${parsed.childNumber}", parsed.childNumber > 0)
    }

    @Test
    fun `deriveChild (CKDpub) on the parsed hardened child matches the official grandchild vector`() {
        val hardenedChild = parseExtendedPublicKey(hardenedChildXpub)
        val grandchildViaCkdPub = hardenedChild.deriveChild(1)
        val grandchildExpected = parseExtendedPublicKey(grandchildXpub)
        assertEquals(grandchildExpected.publicKey.toHex(), grandchildViaCkdPub.publicKey.toHex())
        assertEquals(grandchildExpected.chainCode.toHex(), grandchildViaCkdPub.chainCode.toHex())
        assertEquals(grandchildExpected.depth, grandchildViaCkdPub.depth)
        assertEquals(grandchildExpected.childNumber, grandchildViaCkdPub.childNumber)
        assertEquals(grandchildExpected.parentFingerprint.toHex(), grandchildViaCkdPub.parentFingerprint.toHex())
    }

    @Test
    fun `deriveChild via CKDpub matches the same child derived via CKDpriv`() {
        // Independent cross-check within this codebase itself: deriving
        // m/0'/1's public key by (a) full private-key CKDpriv then taking
        // its public key, versus (b) parsing m/0' as a public-only key and
        // using CKDpub alone, must agree exactly — this is the entire
        // point of BIP32 having a public derivation path at all.
        val master = bip32MasterKeyFromSeed(seed)
        val hardenedChildPriv = master.deriveChild(0, hardened = true)
        val grandchildPriv = hardenedChildPriv.deriveChild(1, hardened = false)

        val hardenedChildPub = parseExtendedPublicKey(hardenedChildXpub)
        val grandchildPub = hardenedChildPub.deriveChild(1)

        assertEquals(grandchildPriv.compressedPublicKey().toHex(), grandchildPub.publicKey.toHex())
    }

    @Test
    fun `parseExtendedPublicKey rejects an xprv (private key) with a clear message`() {
        val master = bip32MasterKeyFromSeed(seed)
        // Hand-build a real, correctly-checksummed xprv for this master
        // key from already-verified primitives (not a memorized base58
        // string — Bip32Test.kt's own docstring notes a past mistake from
        // exactly that) so this test exercises the private-key-prefix
        // rejection path specifically, not an unrelated checksum failure.
        val payload = ByteArray(78)
        byteArrayOf(0x04, 0x88.toByte(), 0xAD.toByte(), 0xE4.toByte()).copyInto(payload, 0)
        payload[4] = master.depth.toByte()
        master.parentFingerprint.copyInto(payload, 5)
        writeUInt32BigEndian(master.childNumber, payload, 9)
        master.chainCode.copyInto(payload, 13)
        payload[45] = 0
        master.privateKey.copyInto(payload, 46)
        val xprv = encodeBase58Check(payload)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            parseExtendedPublicKey(xprv)
        }
        assertTrue(exception.message.orEmpty().contains("private key", ignoreCase = true))
    }

    @Test
    fun `parseExtendedPublicKey rejects a malformed base58 string`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseExtendedPublicKey("not a valid extended key at all")
        }
    }

    @Test
    fun `deriveChild rejects a hardened index`() {
        val parsed = parseExtendedPublicKey(masterXpub)
        assertThrows(IllegalArgumentException::class.java) {
            parsed.deriveChild(HARDENED_OFFSET)
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
