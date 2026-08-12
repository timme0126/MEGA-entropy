package org.mega.entropycore

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Secp256k1Test {

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun `private key 1 produces the compressed generator point itself`() {
        val privateKey = ByteArray(32).also { it[31] = 1 }
        assertEquals(
            "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            Secp256k1.publicKeyFromPrivateKey(privateKey).toHex(),
        )
    }

    @Test
    fun `private key 2 matches the independently known doubled-generator point`() {
        // 2G is a standard, widely published secp256k1 reference point
        // (e.g. used across libsecp256k1's own test vectors).
        val privateKey = ByteArray(32).also { it[31] = 2 }
        assertEquals(
            "02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5",
            Secp256k1.publicKeyFromPrivateKey(privateKey).toHex(),
        )
    }

    @Test
    fun `scalar multiplication by the curve order wraps to the point at infinity`() {
        val result = Secp256k1.scalarMultiply(Secp256k1.N, Secp256k1.G)
        assertEquals(Secp256k1.INFINITY, result)
    }

    @Test
    fun `point addition matches scalar multiplication - 3G equals G plus 2G`() {
        val twoG = Secp256k1.scalarMultiply(BigInteger.TWO, Secp256k1.G)
        val threeGViaAdd = Secp256k1.pointAdd(Secp256k1.G, twoG)
        val threeGViaMultiply = Secp256k1.scalarMultiply(BigInteger.valueOf(3), Secp256k1.G)
        assertEquals(threeGViaMultiply, threeGViaAdd)
    }

    @Test
    fun `decompressPoint recovers G itself from its known compressed form`() {
        // Same reference value asserted the other direction in "private key
        // 1 produces the compressed generator point itself" above.
        val compressed = hexToBytes("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
        assertEquals(Secp256k1.G, Secp256k1.decompressPoint(compressed))
    }

    @Test
    fun `decompressPoint recovers 2G itself from its known compressed form`() {
        val compressed = hexToBytes("02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5")
        val twoG = Secp256k1.scalarMultiply(BigInteger.TWO, Secp256k1.G)
        assertEquals(twoG, Secp256k1.decompressPoint(compressed))
    }

    @Test
    fun `compressPoint and decompressPoint round-trip for an arbitrary point`() {
        val privateKey = ByteArray(32).also { it[30] = 0x12; it[31] = 0x34 }
        val point = Secp256k1.scalarMultiply(privateKey.toPositiveBigInteger(), Secp256k1.G)
        val compressed = Secp256k1.compressPoint(point)
        assertEquals(point, Secp256k1.decompressPoint(compressed))
    }

    @Test
    fun `decompressPoint selects the odd-y root when the prefix is 0x03`() {
        // G's own y is even (prefix 0x02, asserted above) — flipping just
        // the prefix byte to 0x03 must select P - y instead, and the two
        // parities must actually differ (guards against a decompressor
        // that ignores the prefix and always returns the same root).
        val evenYCompressed = hexToBytes("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
        val oddYCompressed = hexToBytes("0379be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
        val evenYPoint = Secp256k1.decompressPoint(evenYCompressed)
        val oddYPoint = Secp256k1.decompressPoint(oddYCompressed)
        assertEquals(Secp256k1.G, evenYPoint)
        assertEquals(evenYPoint.x, oddYPoint.x)
        assertEquals(Secp256k1.P.subtract(evenYPoint.y), oddYPoint.y)
        assertEquals(false, evenYPoint.y!!.testBit(0))
        assertEquals(true, oddYPoint.y!!.testBit(0))
    }

    @Test
    fun `decompressPoint rejects a wrong length input`() {
        assertThrows(IllegalArgumentException::class.java) {
            Secp256k1.decompressPoint(ByteArray(32))
        }
    }

    @Test
    fun `decompressPoint rejects an invalid prefix byte`() {
        val malformed = hexToBytes("0479be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
        assertThrows(IllegalArgumentException::class.java) {
            Secp256k1.decompressPoint(malformed)
        }
    }

    @Test
    fun `decompressPoint rejects an x coordinate with no point on the curve`() {
        // x = 5 is not on secp256k1 (5^3 + 7 = 132 is a quadratic
        // non-residue mod p, independently confirmed via Euler's
        // criterion) — the on-curve check must catch this rather than
        // silently returning some point whose square doesn't match x^3 + 7.
        val fakePoint = ByteArray(33).also { it[0] = 0x02; it[32] = 5 }
        assertThrows(IllegalArgumentException::class.java) {
            Secp256k1.decompressPoint(fakePoint)
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((Character.digit(hex[it * 2], 16) shl 4) + Character.digit(hex[it * 2 + 1], 16)).toByte() }
}
