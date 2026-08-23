package org.mega.entropycore

import java.math.BigInteger

/**
 * BIP341 Taproot output-key tweaking — turns an internal (x-only) public
 * key plus an optional script-tree Merkle root into the actual output key
 * that goes on-chain, exactly per BIP341's `taproot_tweak_pubkey`
 * (github.com/bitcoin/bips/blob/master/bip-0341.mediawiki, fetched and
 * verified directly against the primary spec text and the BIP's own
 * published wallet test vectors — see TapTweakVectorsTest.kt).
 *
 * A thin layer over [Schnorr]'s x-only lift_x and [Secp256k1]'s point
 * arithmetic — no new curve math, only the tweak-hash and the private-key
 * side of the same tweak (needed so MEGA can actually sign a key-path
 * spend, not just compute the public output key for display/address
 * derivation).
 */
internal object TapTweak {
    private val N = Secp256k1.N
    private val G = Secp256k1.G

    /** hash_TapTweak(internalPubkey || merkleRoot), reduced mod n — the
     * scalar `t` in BIP341's taproot_tweak_pubkey. [merkleRoot] is empty
     * when the output has no script tree (key-path-only), or the
     * script tree's computed Merkle root when it does — this function
     * does not compute that root itself (script-tree construction is a
     * separate concern for the inheritance/script-path work later),
     * it only accepts whatever 0 or 32 bytes the caller already has. */
    private fun tweakScalar(internalPubkey: ByteArray, merkleRoot: ByteArray): BigInteger {
        require(internalPubkey.size == 32) { "Internal pubkey must be 32 bytes (x-only), got ${internalPubkey.size}" }
        require(merkleRoot.isEmpty() || merkleRoot.size == 32) {
            "Merkle root must be empty (no script tree) or exactly 32 bytes, got ${merkleRoot.size}"
        }
        val tagHash = sha256("TapTweak".toByteArray(Charsets.UTF_8))
        val hash = sha256(tagHash + tagHash + internalPubkey + merkleRoot)
        return hash.toPositiveBigInteger()
    }

    /** One tweaked (output) key, plus which secp256k1 point (of the two
     * with this x-coordinate) it actually is — needed by [tweakPrivateKey]
     * to know whether the internal private key needs negating before the
     * tweak is added, the same even-y convention BIP340 itself uses. */
    internal data class TweakedKey(val outputKeyXOnly: ByteArray, val outputKeyIsEvenY: Boolean)

    /**
     * taproot_tweak_pubkey(pubkey, h): computes the output key from an
     * x-only internal public key and a (possibly empty) Merkle root.
     * Throws [IllegalArgumentException] if the tweak scalar is out of
     * range or [internalPubkey] doesn't lift to a valid curve point —
     * both are the spec's own failure conditions, not implementation
     * choices.
     */
    fun tweakPubKey(internalPubkey: ByteArray, merkleRoot: ByteArray): TweakedKey {
        val t = tweakScalar(internalPubkey, merkleRoot)
        require(t < N) { "Tweak scalar is out of the secp256k1 scalar field's range" }
        val internalPoint = liftXOnly(internalPubkey)
            ?: throw IllegalArgumentException("Internal pubkey does not lift to a valid curve point")

        val tweakPoint = if (t.signum() == 0) Secp256k1.INFINITY else Secp256k1.scalarMultiply(t, G)
        val outputPoint = Secp256k1.pointAdd(internalPoint, tweakPoint)
        require(outputPoint != Secp256k1.INFINITY) { "Tweaked output key is the point at infinity (t = -internalKey, vanishingly unlikely)" }

        val isEvenY = !outputPoint.y!!.testBit(0)
        return TweakedKey(outputPoint.x!!.toFixed32Bytes(), isEvenY)
    }

    /**
     * The private-key side of the same tweak, needed to actually sign a
     * key-path spend: `d_tweaked = (d_even + t) mod n`, where `d_even` is
     * [internalPrivateKey] negated iff its own point has odd y — exactly
     * BIP341's reference `taproot_sign_key` procedure, no more.
     *
     * `d_even * G` is, by construction, EXACTLY [liftXOnly]'s result for
     * [internalPubkey] (both are "the even-y point at that x-coordinate" —
     * the same point, not merely the same x). [tweakPubKey] computes its
     * output point as `liftXOnly(internalPubkey) + t*G`, so
     * `d_tweaked * G = d_even*G + t*G` is EXACTLY that same output point —
     * full point equality, not just matching x — with no further parity
     * correction needed here. (An earlier draft of this function
     * recomputed `d_tweaked * G` and conditionally re-negated to match
     * [TweakedKey.outputKeyIsEvenY]; that branch never actually fired —
     * confirmed both by this proof and empirically, across every parity
     * combination in BIP341's own test vectors — and was removed as
     * confusing dead code, not a correctness fix.) The signature itself
     * still works out regardless of `d_tweaked * G`'s own y-parity:
     * [Schnorr.sign] performs its own independent `d = d' if
     * has_even_y(d'*G) else n - d'` adjustment on whatever scalar it's
     * given, per BIP340's own Sign algorithm.
     */
    fun tweakPrivateKey(internalPrivateKey: ByteArray, internalPubkey: ByteArray, merkleRoot: ByteArray): ByteArray {
        require(internalPrivateKey.size == 32) { "Internal private key must be 32 bytes, got ${internalPrivateKey.size}" }
        val d0 = internalPrivateKey.toPositiveBigInteger()
        require(d0.signum() != 0 && d0 < N) { "Internal private key out of the secp256k1 scalar field's range" }

        val internalPoint = Secp256k1.scalarMultiply(d0, G)
        require(internalPoint.x!!.toFixed32Bytes().contentEquals(internalPubkey)) {
            "internalPubkey does not match the public key derived from internalPrivateKey"
        }
        val dEven = if (!internalPoint.y!!.testBit(0)) d0 else N.subtract(d0)

        val t = tweakScalar(internalPubkey, merkleRoot)
        require(t < N) { "Tweak scalar is out of the secp256k1 scalar field's range" }

        val dTweaked = dEven.add(t).mod(N)
        return dTweaked.toFixed32Bytes()
    }

    /** lift_x, exposed here (rather than reusing Schnorr's private one) —
     * TapTweak needs it independently of Schnorr's sign/verify, and
     * BIP341 itself just says "lift_x as defined in BIP340" rather than
     * this being a distinct algorithm. Duplicated, not shared, to keep
     * Schnorr.kt's lift_x private to its own file per this codebase's
     * existing per-file encapsulation style (see e.g. Secp256k1's own
     * self-contained point arithmetic). */
    private fun liftXOnly(xOnly: ByteArray): Secp256k1.Point? {
        require(xOnly.size == 32) { "x-only key must be 32 bytes, got ${xOnly.size}" }
        val p = Secp256k1.P
        val x = xOnly.toPositiveBigInteger()
        if (x.signum() < 0 || x >= p) return null
        val c = x.modPow(BigInteger.valueOf(3), p).add(BigInteger.valueOf(7)).mod(p)
        val y = c.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
        if (y.multiply(y).mod(p) != c) return null
        val evenY = if (y.testBit(0)) p.subtract(y) else y
        return Secp256k1.Point(x, evenY)
    }
}
