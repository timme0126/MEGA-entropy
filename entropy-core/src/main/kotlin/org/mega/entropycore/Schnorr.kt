package org.mega.entropycore

import java.math.BigInteger

/**
 * BIP340 Schnorr signatures over secp256k1 — x-only public keys, tagged
 * hashes, and the Sign/Verify algorithms exactly as specified in BIP340
 * (github.com/bitcoin/bips/blob/master/bip-0340.mediawiki, fetched and
 * verified directly against the primary spec text and its own official
 * test vectors — see Bip340OfficialVectorsTest.kt — not implemented from
 * a paraphrase). Every private helper below names the BIP340 step it
 * implements so this can be audited line-by-line against the spec.
 *
 * Deliberately a THIN layer over Secp256k1's existing affine BigInteger
 * point arithmetic (pointAdd/scalarMultiply/G/N/P) — no new curve math,
 * only the BIP340-specific serialization (x-only, not SEC1-compressed)
 * and hashing (tagged hashes, not bare SHA-256) on top of it.
 *
 * [sign] takes its 32-byte auxiliary randomness as a caller-supplied
 * parameter rather than generating it internally, the same pattern
 * ShamirSecretSharing.kt's splitSecret already uses: :entropy-core's own
 * securityAudit Gradle task forbids referencing any hidden RNG source in
 * this module's code, so the actual randomness source lives at the
 * app-module call site.
 */
internal object Schnorr {
    private val P = Secp256k1.P
    private val N = Secp256k1.N
    private val G = Secp256k1.G

    /** hash_name(x) = SHA256(SHA256(tag) || SHA256(tag) || x), where tag
     * is the UTF-8 encoding of name — BIP340's tagged_hash. */
    private fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val tagHash = sha256(tag.toByteArray(Charsets.UTF_8))
        return sha256(tagHash + tagHash + data)
    }

    /** has_even_y(P) — undefined (and never called) for the point at
     * infinity; callers are responsible for checking that first. */
    private fun hasEvenY(point: Secp256k1.Point): Boolean {
        require(point != Secp256k1.INFINITY) { "has_even_y is undefined for the point at infinity" }
        return !point.y!!.testBit(0)
    }

    /**
     * lift_x(x): the unique point P with x(P) = x and has_even_y(P), or
     * null if x >= p or x^3+7 is not a quadratic residue mod p (no such
     * point exists). secp256k1's p ≡ 3 (mod 4), so a square root of
     * c = x^3+7 is c^((p+1)/4) mod p directly — the same technique
     * Secp256k1.decompressPoint already uses for SEC1 decompression,
     * re-derived independently here since that function takes a 33-byte
     * prefixed input, not a bare x-coordinate.
     */
    private fun liftX(x: BigInteger): Secp256k1.Point? {
        if (x.signum() < 0 || x >= P) return null
        val c = x.modPow(BigInteger.valueOf(3), P).add(BigInteger.valueOf(7)).mod(P)
        val y = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.multiply(y).mod(P) != c) return null
        val evenY = if (y.testBit(0)) P.subtract(y) else y
        return Secp256k1.Point(x, evenY)
    }

    /** bytes(P) in BIP340's x-only sense: the 32-byte big-endian
     * x-coordinate alone, NOT Secp256k1.compressPoint's 33-byte SEC1
     * form (which carries a parity prefix byte BIP340 never uses). */
    private fun xOnly(point: Secp256k1.Point): ByteArray = point.x!!.toFixed32Bytes()

    /**
     * s*G - e*P. Secp256k1.scalarMultiply requires a strictly positive
     * scalar (0*anything is mathematically INFINITY, but that's outside
     * its precondition) — rather than loosening that precondition for
     * every other caller in this module, e = 0 is special-cased here to
     * just s*G directly.
     */
    private fun sGMinusEP(s: BigInteger, e: BigInteger, point: Secp256k1.Point): Secp256k1.Point {
        val sG = Secp256k1.scalarMultiply(s, G)
        if (e.signum() == 0) return sG
        val negE = N.subtract(e).mod(N)
        val eP = Secp256k1.scalarMultiply(negE, point)
        return Secp256k1.pointAdd(sG, eP)
    }

    private fun xorBytes(a: ByteArray, b: ByteArray): ByteArray {
        require(a.size == b.size) { "xorBytes requires equal-length arrays" }
        return ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
    }

    /** PubKey(sk): the 32-byte x-only public key for private key [secretKey]. */
    fun xOnlyPubKeyFromPrivateKey(secretKey: ByteArray): ByteArray {
        require(secretKey.size == 32) { "Secret key must be 32 bytes, got ${secretKey.size}" }
        val d0 = secretKey.toPositiveBigInteger()
        require(d0.signum() != 0 && d0 < N) { "Secret key out of the secp256k1 scalar field's range" }
        return xOnly(Secp256k1.scalarMultiply(d0, G))
    }

    /**
     * Sign(sk, m, a): BIP340's default signing algorithm. [auxRand] is
     * the caller-supplied 32-byte auxiliary randomness (BIP340's `a`) —
     * see this object's own doc for why it is never generated here.
     * Performs BIP340's own final self-check ("If Verify(...) returns
     * failure, abort") before returning.
     */
    fun sign(secretKey: ByteArray, message: ByteArray, auxRand: ByteArray): ByteArray {
        require(secretKey.size == 32) { "Secret key must be 32 bytes, got ${secretKey.size}" }
        require(auxRand.size == 32) { "auxRand must be 32 bytes, got ${auxRand.size}" }

        val d0 = secretKey.toPositiveBigInteger()
        require(d0.signum() != 0 && d0 < N) { "Secret key out of the secp256k1 scalar field's range" }
        val bigP = Secp256k1.scalarMultiply(d0, G)
        val d = if (hasEvenY(bigP)) d0 else N.subtract(d0)

        val t = xorBytes(d.toFixed32Bytes(), taggedHash("BIP0340/aux", auxRand))
        val rand = taggedHash("BIP0340/nonce", t + xOnly(bigP) + message)
        val kPrime = rand.toPositiveBigInteger().mod(N)
        require(kPrime.signum() != 0) { "Nonce derivation produced k' = 0 (astronomically unlikely) - retry with different auxRand" }

        val bigR = Secp256k1.scalarMultiply(kPrime, G)
        val k = if (hasEvenY(bigR)) kPrime else N.subtract(kPrime)

        val e = taggedHash("BIP0340/challenge", xOnly(bigR) + xOnly(bigP) + message).toPositiveBigInteger().mod(N)
        val sScalar = k.add(e.multiply(d)).mod(N)
        val signature = xOnly(bigR) + sScalar.toFixed32Bytes()

        require(verify(xOnly(bigP), message, signature)) {
            "Freshly produced signature failed its own verification - this indicates a bug, not bad input"
        }
        return signature
    }

    /**
     * Verify(pk, m, sig): BIP340 verification. Returns false (never
     * throws) for any malformed input or failed check, matching BIP340's
     * own definition of Verify as a success/failure predicate — a caller
     * that needs to distinguish "malformed" from "well-formed but
     * invalid" must inspect the input itself first.
     */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val bigP = liftX(publicKey.toPositiveBigInteger()) ?: return false

        val r = signature.copyOfRange(0, 32).toPositiveBigInteger()
        if (r >= P) return false
        val s = signature.copyOfRange(32, 64).toPositiveBigInteger()
        if (s >= N) return false

        val e = taggedHash("BIP0340/challenge", signature.copyOfRange(0, 32) + xOnly(bigP) + message)
            .toPositiveBigInteger().mod(N)
        val bigR = sGMinusEP(s, e, bigP)
        if (bigR == Secp256k1.INFINITY) return false
        if (!hasEvenY(bigR)) return false
        if (bigR.x != r) return false
        return true
    }
}
