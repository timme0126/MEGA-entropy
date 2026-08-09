package org.mega.entropycore

import java.math.BigInteger

/**
 * Minimal secp256k1 elliptic curve point arithmetic — just enough to turn
 * a BIP32 private key into its public key and to add two public points
 * together (BIP32's non-hardened CKDpub step). Affine coordinates with
 * BigInteger modular arithmetic: simple to audit line-by-line against the
 * textbook formulas, and fast enough for deriving a handful of keys (this
 * is not a signing/verification hot path).
 *
 * Curve parameters are the standard, publicly published secp256k1 domain
 * parameters (SEC 2). Not secret, not derived from anything — same
 * category as the BIP39 word list: known-good reference constants.
 */
internal object Secp256k1 {
    val P: BigInteger = BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F",
        16,
    )
    val N: BigInteger = BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141",
        16,
    )
    private val GX = BigInteger(
        "79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798",
        16,
    )
    private val GY = BigInteger(
        "483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8",
        16,
    )

    data class Point(val x: BigInteger?, val y: BigInteger?)

    val G = Point(GX, GY)
    val INFINITY = Point(null, null)

    private fun mod(value: BigInteger): BigInteger = value.mod(P)

    fun pointAdd(p1: Point, p2: Point): Point {
        if (p1 == INFINITY) return p2
        if (p2 == INFINITY) return p1
        val x1 = p1.x!!; val y1 = p1.y!!
        val x2 = p2.x!!; val y2 = p2.y!!
        if (x1 == x2) {
            return if (y1 != y2 || y1 == BigInteger.ZERO) INFINITY else pointDouble(p1)
        }
        val lambda = mod((y2 - y1) * (x2 - x1).modInverse(P))
        val x3 = mod(lambda * lambda - x1 - x2)
        val y3 = mod(lambda * (x1 - x3) - y1)
        return Point(x3, y3)
    }

    fun pointDouble(point: Point): Point {
        if (point == INFINITY) return INFINITY
        val x = point.x!!; val y = point.y!!
        if (y == BigInteger.ZERO) return INFINITY
        // secp256k1's curve coefficient a = 0, so the tangent slope is
        // simply 3x^2 / 2y (no "+ a" term).
        val lambda = mod(BigInteger.valueOf(3) * x * x * (BigInteger.TWO * y).modInverse(P))
        val x3 = mod(lambda * lambda - BigInteger.TWO * x)
        val y3 = mod(lambda * (x - x3) - y)
        return Point(x3, y3)
    }

    /** Double-and-add scalar multiplication. [scalar] must be in (0, N). */
    fun scalarMultiply(scalar: BigInteger, point: Point): Point {
        require(scalar.signum() > 0) { "Scalar must be positive" }
        var result = INFINITY
        var addend = point
        var remaining = scalar
        while (remaining.signum() > 0) {
            if (remaining.testBit(0)) {
                result = pointAdd(result, addend)
            }
            addend = pointDouble(addend)
            remaining = remaining.shiftRight(1)
        }
        return result
    }

    /** 33-byte SEC1 compressed point: 0x02/0x03 prefix (parity of y) + 32-byte X. */
    fun compressPoint(point: Point): ByteArray {
        require(point != INFINITY) { "Cannot compress the point at infinity" }
        val prefix: Byte = if (point.y!!.testBit(0)) 0x03 else 0x02
        return byteArrayOf(prefix) + point.x!!.toFixed32Bytes()
    }

    fun publicKeyFromPrivateKey(privateKey: ByteArray): ByteArray {
        val scalar = privateKey.toPositiveBigInteger()
        require(scalar.signum() > 0 && scalar < N) { "Private key out of secp256k1 range" }
        return compressPoint(scalarMultiply(scalar, G))
    }
}
