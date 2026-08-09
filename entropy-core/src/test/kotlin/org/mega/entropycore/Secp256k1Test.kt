package org.mega.entropycore

import java.math.BigInteger
import org.junit.Assert.assertEquals
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
}
