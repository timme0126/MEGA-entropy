package org.mega.entropycore

import org.junit.Test
import org.junit.Assert.*

class EcdsaSigningTest {
    private fun String.hexToByteArray() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun `signs privkey=1 deterministically and matches the reference vector`() {
        val pk = "0000000000000000000000000000000000000000000000000000000000000001".hexToByteArray()
        val hash = "bc62d4b80d9e36da29c16c5d4d9f11731f36052c72401a76c23c0fb5a9b74423".hexToByteArray()
        val expectedPubkey = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        val expectedR = "e931439752288126a940a742a55c8c77559316c0b0db638727c499e454141119"
        val expectedS = "5befeb909690a656b41dd960ec5d624b4c11c7327b9b67bc2b649b2f238817b3"
        val expectedDer = "3045022100e931439752288126a940a742a55c8c77559316c0b0db638727c499e45414111902205befeb909690a656b41dd960ec5d624b4c11c7327b9b67bc2b649b2f238817b3"

        val sig = signEcdsaRaw(pk, hash)
        assertEquals(expectedR, sig.r.joinToString("") { "%02x".format(it) })
        assertEquals(expectedS, sig.s.joinToString("") { "%02x".format(it) })

        val derSig = signEcdsaDer(pk, hash)
        assertEquals(expectedDer, derSig.joinToString("") { "%02x".format(it) })

        assertTrue(verifyEcdsa(expectedPubkey.hexToByteArray(), hash, derSig))
    }

    @Test fun `signs privkey=2 deterministically and matches the reference vector`() {
        val pk = "0000000000000000000000000000000000000000000000000000000000000002".hexToByteArray()
        val hash = "1ab3b6827ceeea24155245b11418dd6021d6f2d4e7193172f3f8dc03c650ef6f".hexToByteArray()
        val expectedPubkey = "02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"
        val expectedR = "1a110a043b3afa8e3030838d5196341479f43a1d5b569afcfeb8aa188a6dd605"
        val expectedS = "5c3c52ec06df743896563a57c9a676b0d7bb6666c3c640da7582c2c9a86a7d11"
        val expectedDer = "304402201a110a043b3afa8e3030838d5196341479f43a1d5b569afcfeb8aa188a6dd60502205c3c52ec06df743896563a57c9a676b0d7bb6666c3c640da7582c2c9a86a7d11"

        val sig = signEcdsaRaw(pk, hash)
        assertEquals(expectedR, sig.r.joinToString("") { "%02x".format(it) })
        assertEquals(expectedS, sig.s.joinToString("") { "%02x".format(it) })

        val derSig = signEcdsaDer(pk, hash)
        assertEquals(expectedDer, derSig.joinToString("") { "%02x".format(it) })

        assertTrue(verifyEcdsa(expectedPubkey.hexToByteArray(), hash, derSig))
    }

    @Test fun `signs privkey=0x18e1 deterministically and matches the reference vector`() {
        val pk = "18e14a7b6a307f426a94f8114701e7c8e774e7f9a47e2c2035db29a206321725".hexToByteArray()
        val hash = "64099befba39fb80cb66d7a707a1c12f1b56b25bba7bfe1525b6191e0fe63b4f".hexToByteArray()
        val expectedPubkey = "0250863ad64a87ae8a2fe83c1af1a8403cb53f53e486d8511dad8a04887e5b2352"
        val expectedR = "61501d8640dcba30c4737fe3dedffa0887951d363dd3c5407d9ee904a2cd99e5"
        val expectedS = "381304864213b57d8a337390735728bfefae2b4224e58945ebc0d09eb03503d1"
        val expectedDer = "3044022061501d8640dcba30c4737fe3dedffa0887951d363dd3c5407d9ee904a2cd99e50220381304864213b57d8a337390735728bfefae2b4224e58945ebc0d09eb03503d1"

        val sig = signEcdsaRaw(pk, hash)
        assertEquals(expectedR, sig.r.joinToString("") { "%02x".format(it) })
        assertEquals(expectedS, sig.s.joinToString("") { "%02x".format(it) })

        val derSig = signEcdsaDer(pk, hash)
        assertEquals(expectedDer, derSig.joinToString("") { "%02x".format(it) })

        assertTrue(verifyEcdsa(expectedPubkey.hexToByteArray(), hash, derSig))
    }

    @Test fun `signs privkey=N-1 deterministically and matches the reference vector`() {
        val pk = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364140".hexToByteArray()
        val hash = "b9c23895fd3c6f3ae3a122f1b79748251873dabcbb3c8c22c8cac2486880cb1a".hexToByteArray()
        val expectedPubkey = "0379be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        val expectedR = "7d0af7255ea06f572fad912aee7336559bdd0be9d30b1f0bce46b1039e825437"
        val expectedS = "4f40d56531f5fc66fca1cb5a60cd0cc3ff76fad1dd2f126c64ee8d0825b5acaa"
        val expectedDer = "304402207d0af7255ea06f572fad912aee7336559bdd0be9d30b1f0bce46b1039e82543702204f40d56531f5fc66fca1cb5a60cd0cc3ff76fad1dd2f126c64ee8d0825b5acaa"

        val sig = signEcdsaRaw(pk, hash)
        assertEquals(expectedR, sig.r.joinToString("") { "%02x".format(it) })
        assertEquals(expectedS, sig.s.joinToString("") { "%02x".format(it) })

        val derSig = signEcdsaDer(pk, hash)
        assertEquals(expectedDer, derSig.joinToString("") { "%02x".format(it) })

        assertTrue(verifyEcdsa(expectedPubkey.hexToByteArray(), hash, derSig))
    }

    @Test fun `RFC6979 bits2octets reduces a hash at or above the curve order`() {
        val nPlusOne = Secp256k1.N.add(java.math.BigInteger.ONE).toFixed32Bytes()
        assertArrayEquals(ByteArray(31) + byteArrayOf(1), rfc6979Bits2Octets(nPlusOne))
    }

    @Test fun `signEcdsaRaw is deterministic — same inputs produce the same output every time`() {
        val pk = "0000000000000000000000000000000000000000000000000000000000000001".hexToByteArray()
        val hash = "bc62d4b80d9e36da29c16c5d4d9f11731f36052c72401a76c23c0fb5a9b74423".hexToByteArray()
        val sig1 = signEcdsaRaw(pk, hash)
        val sig2 = signEcdsaRaw(pk, hash)
        assertArrayEquals(sig1.r, sig2.r)
        assertArrayEquals(sig1.s, sig2.s)
    }

    @Test fun `signEcdsaRaw rejects a private key that is zero`() {
        val zeroKey = ByteArray(32)
        val hash = "bc62d4b80d9e36da29c16c5d4d9f11731f36052c72401a76c23c0fb5a9b74423".hexToByteArray()
        assertThrows(IllegalArgumentException::class.java) { signEcdsaRaw(zeroKey, hash) }
    }

    @Test fun `signEcdsaRaw rejects a private key equal to or greater than the curve order N`() {
        val pkN = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141".hexToByteArray()
        val pkNPlus1 = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364142".hexToByteArray()
        val hash = "bc62d4b80d9e36da29c16c5d4d9f11731f36052c72401a76c23c0fb5a9b74423".hexToByteArray()
        assertThrows(IllegalArgumentException::class.java) { signEcdsaRaw(pkN, hash) }
        assertThrows(IllegalArgumentException::class.java) { signEcdsaRaw(pkNPlus1, hash) }
    }

    @Test fun `signEcdsaRaw rejects a private key that is not exactly 32 bytes`() {
        val pk31 = ByteArray(31)
        val pk33 = ByteArray(33)
        val hash = "bc62d4b80d9e36da29c16c5d4d9f11731f36052c72401a76c23c0fb5a9b74423".hexToByteArray()
        assertThrows(IllegalArgumentException::class.java) { signEcdsaRaw(pk31, hash) }
        assertThrows(IllegalArgumentException::class.java) { signEcdsaRaw(pk33, hash) }
    }

    @Test fun `signEcdsaRaw rejects a message hash that is not exactly 32 bytes`() {
        val pk = "0000000000000000000000000000000000000000000000000000000000000001".hexToByteArray()
        val hash31 = ByteArray(31)
        val hash33 = ByteArray(33)
        assertThrows(IllegalArgumentException::class.java) { signEcdsaRaw(pk, hash31) }
        assertThrows(IllegalArgumentException::class.java) { signEcdsaRaw(pk, hash33) }
    }

    @Test fun `verifyEcdsa returns false for a signature verified against the wrong public key`() {
        val derSig = "3045022100e931439752288126a940a742a55c8c77559316c0b0db638727c499e45414111902205befeb909690a656b41dd960ec5d624b4c11c7327b9b67bc2b649b2f238817b3".hexToByteArray()
        val hash = "bc62d4b80d9e36da29c16c5d4d9f11731f36052c72401a76c23c0fb5a9b74423".hexToByteArray()
        val wrongPubkey = "02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5".hexToByteArray()
        assertFalse(verifyEcdsa(wrongPubkey, hash, derSig))
    }

    @Test fun `verifyEcdsa returns false for a signature verified against a tampered message hash`() {
        val derSig = "3045022100e931439752288126a940a742a55c8c77559316c0b0db638727c499e45414111902205befeb909690a656b41dd960ec5d624b4c11c7327b9b67bc2b649b2f238817b3".hexToByteArray()
        val pubkey = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798".hexToByteArray()
        val wrongHash = "1ab3b6827ceeea24155245b11418dd6021d6f2d4e7193172f3f8dc03c650ef6f".hexToByteArray()
        assertFalse(verifyEcdsa(pubkey, wrongHash, derSig))
    }

    @Test fun `verifyEcdsa returns false for a corrupted DER signature`() {
        val derSig = "3045022100e931439752288126a940a742a55c8c77559316c0b0db638727c499e45414111902205befeb909690a656b41dd960ec5d624b4c11c7327b9b67bc2b649b2f238817b3".hexToByteArray()
        val pubkey = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798".hexToByteArray()
        val hash = "bc62d4b80d9e36da29c16c5d4d9f11731f36052c72401a76c23c0fb5a9b74423".hexToByteArray()
        val corrupted = derSig.copyOf()
        corrupted[10] = (corrupted[10].toInt() xor 0xFF).toByte()
        assertFalse(verifyEcdsa(pubkey, hash, corrupted))
    }

    @Test fun `verifyEcdsa returns false for an empty signature`() {
        val derSig = ByteArray(0)
        val pubkey = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798".hexToByteArray()
        val hash = "bc62d4b80d9e36da29c16c5d4d9f11731f36052c72401a76c23c0fb5a9b74423".hexToByteArray()
        assertFalse(verifyEcdsa(pubkey, hash, derSig))
    }

    @Test fun `verifyEcdsa returns false for a high-S signature even though it satisfies the raw ECDSA equation`() {
        // (r, s) and (r, N-s) are BOTH mathematically valid ECDSA signatures for
        // the same message/key — that's exactly why BIP62/BIP146 mandate low-S:
        // without it, anyone could flip a valid signature into a second,
        // still-valid one (transaction malleability). verifyEcdsa must reject
        // the high-S form outright, not just happen to accept only the one a
        // signer produced.
        val pk = "0000000000000000000000000000000000000000000000000000000000000001".hexToByteArray()
        val hash = "bc62d4b80d9e36da29c16c5d4d9f11731f36052c72401a76c23c0fb5a9b74423".hexToByteArray()
        val pubkey = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798".hexToByteArray()

        val sig = signEcdsaRaw(pk, hash) // low-S by construction
        val n = java.math.BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
        val highS = n - java.math.BigInteger(1, sig.s)

        fun derInt(value: java.math.BigInteger): ByteArray {
            val bytes = value.toByteArray() // BigInteger's own encoding already matches DER INTEGER's minimal form
            return byteArrayOf(0x02, bytes.size.toByte()) + bytes
        }
        val content = derInt(java.math.BigInteger(1, sig.r)) + derInt(highS)
        val highSDer = byteArrayOf(0x30, content.size.toByte()) + content

        assertFalse(verifyEcdsa(pubkey, hash, highSDer))
    }
}
