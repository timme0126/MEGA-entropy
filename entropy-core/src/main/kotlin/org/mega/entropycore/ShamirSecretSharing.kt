package org.mega.entropycore

import java.math.BigInteger
import java.security.SecureRandom

/**
 * One share of a secret split via Shamir's Secret Sharing over the
 * secp256k1 scalar field (mod [Secp256k1.N]) — same field the WIF-encoded
 * private keys and BIP32 derivation elsewhere in this module already
 * operate over, so no new curve/field machinery is introduced here.
 *
 * [integrityTag] is the first 8 hex characters of HASH160(compressed
 * pubkey of the ORIGINAL secret), computed once at split time and copied
 * onto every share unchanged. It is not itself secret (a pubkey hash
 * reveals nothing about the private scalar it hashes), but it lets
 * [reconstructSecret] detect a mistyped, corrupted, or accidentally
 * mixed-up (from a different split entirely) share set without needing
 * any other ground truth to compare against — the same self-describing
 * check BRC-140 (BSV's own threshold-sharing spec) uses.
 */
data class BackupShare(
    val x: Int,
    val y: BigInteger,
    val threshold: Int,
    val integrityTag: String,
)

private val SHARE_RANDOM = SecureRandom()

/**
 * Splits [secret] into [totalShares] shares, any [threshold] of which
 * reconstruct it exactly via [reconstructSecret]. [secret] is treated as
 * the constant term of a random polynomial of degree `threshold - 1`
 * evaluated at x = 1, 2, ..., totalShares (x = 0 is never handed out —
 * that point IS the secret).
 *
 * [secret] must be strictly less than [Secp256k1.N] — true with
 * overwhelming probability for any 16- or 32-byte value MEGA actually
 * splits (dice-roll or imported entropy is uniformly random over its full
 * byte length, and N is only a few parts in 2^128 short of 2^256), but
 * checked explicitly rather than silently reduced mod N: silently
 * wrapping a too-large secret would make the split's f(0) different from
 * the original bytes, corrupting recovery in a way round-trip tests
 * covering ordinary inputs would never catch.
 */
internal fun splitSecret(secret: ByteArray, threshold: Int, totalShares: Int): List<BackupShare> {
    require(threshold >= 2) {
        "Threshold must be at least 2 - a threshold of 1 provides no protection against a single lost or stolen share"
    }
    require(totalShares >= threshold) {
        "Total shares ($totalShares) must be at least the threshold ($threshold)"
    }
    require(totalShares in 1..255) { "Total shares must be between 1 and 255, got $totalShares" }
    require(secret.isNotEmpty()) { "Secret must not be empty" }

    val n = Secp256k1.N
    val secretScalar = secret.toPositiveBigInteger()
    require(secretScalar.signum() > 0 && secretScalar < n) {
        "Secret is out of the secp256k1 scalar field's range - this should not happen for real entropy; regenerate and retry"
    }

    val integrityTag = computeIntegrityTag(secretScalar)

    // f(x) = secret + a1*x + a2*x^2 + ... + a(threshold-1)*x^(threshold-1),
    // all arithmetic mod n. Random, uniformly distributed coefficients:
    // the only thing that makes fewer than `threshold` shares reveal
    // nothing about the secret.
    val coefficients = (1 until threshold).map { randomScalarModN(n) }

    return (1..totalShares).map { x ->
        val xBig = BigInteger.valueOf(x.toLong())
        var y = secretScalar
        var xPower = BigInteger.ONE
        for (coefficient in coefficients) {
            xPower = xPower.multiply(xBig).mod(n)
            y = y.add(coefficient.multiply(xPower).mod(n)).mod(n)
        }
        BackupShare(x = x, y = y, threshold = threshold, integrityTag = integrityTag)
    }
}

/**
 * Reconstructs the original secret from [shares] via Lagrange
 * interpolation at x = 0. Any qualifying subset of at least `threshold`
 * shares (not necessarily the first ones handed out by [splitSecret])
 * reconstructs the identical secret. [secretByteLength] must match what
 * was originally split (16 or 32 for MEGA's entropy) - the recovered
 * scalar is re-encoded to exactly that many unsigned big-endian bytes.
 *
 * Fails loudly (never returns a wrong-but-plausible secret) when: fewer
 * than the declared threshold shares are supplied, shares disagree on
 * their own threshold or integrity tag (mixed up from different splits),
 * two shares share the same x coordinate (duplicate/corrupted share), or
 * the reconstructed secret's own integrity tag doesn't match what every
 * share claims it should be (a mistyped digit in one share, most likely).
 */
internal fun reconstructSecret(shares: List<BackupShare>, secretByteLength: Int): ByteArray {
    require(shares.isNotEmpty()) { "At least one share is required" }
    val threshold = shares.first().threshold
    require(shares.all { it.threshold == threshold }) {
        "Supplied shares don't all declare the same threshold - they may be from different backup share sets"
    }
    require(shares.map { it.x }.distinct().size == shares.size) {
        "Duplicate share supplied (same share index) - each share must be distinct"
    }
    require(shares.size >= threshold) {
        "Need at least $threshold shares to reconstruct, only ${shares.size} supplied"
    }
    val expectedTag = shares.first().integrityTag
    require(shares.all { it.integrityTag == expectedTag }) {
        "Supplied shares don't all carry the same integrity tag - they may be from different backup share sets"
    }

    val n = Secp256k1.N
    val usableShares = shares.take(threshold)
    val secretScalar = lagrangeInterpolateAtZero(usableShares, n)

    require(computeIntegrityTag(secretScalar) == expectedTag) {
        "Reconstructed secret does not match the shares' integrity tag - check each share was entered correctly"
    }

    return bigIntegerToUnsignedBytes(secretScalar, secretByteLength)
}

/** Lagrange interpolation of [shares] at x = 0, i.e. recovering f(0) from
 * degree-(threshold-1) polynomial evaluations f(x_i) = y_i, all mod [n]. */
private fun lagrangeInterpolateAtZero(shares: List<BackupShare>, n: BigInteger): BigInteger {
    var result = BigInteger.ZERO
    for (i in shares.indices) {
        val xi = BigInteger.valueOf(shares[i].x.toLong())
        var numerator = BigInteger.ONE
        var denominator = BigInteger.ONE
        for (j in shares.indices) {
            if (i == j) continue
            val xj = BigInteger.valueOf(shares[j].x.toLong())
            numerator = numerator.multiply(xj.negate()).mod(n)
            denominator = denominator.multiply(xi.subtract(xj)).mod(n)
        }
        val lagrangeCoefficient = numerator.multiply(denominator.modInverse(n)).mod(n)
        result = result.add(shares[i].y.multiply(lagrangeCoefficient).mod(n)).mod(n)
    }
    return result
}

private fun randomScalarModN(n: BigInteger): BigInteger {
    while (true) {
        val bytes = ByteArray(32)
        SHARE_RANDOM.nextBytes(bytes)
        val candidate = bytes.toPositiveBigInteger()
        if (candidate.signum() > 0 && candidate < n) return candidate
    }
}

/** HASH160 of the compressed pubkey corresponding to [secretScalar] as a
 * secp256k1 private key, first 8 hex characters — see [BackupShare]'s doc
 * for why this is safe to embed on every share unencrypted. */
internal fun computeIntegrityTag(secretScalar: BigInteger): String {
    val pubKey = Secp256k1.publicKeyFromPrivateKey(secretScalar.toFixed32Bytes())
    val hash = hash160(pubKey)
    return hash.joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(8)
}

/**
 * Encodes a [BackupShare] as `<x>.<y>.<threshold>.<integrityTag>`, dot-
 * separated so it can be handwritten, typed back in, or read aloud
 * unambiguously - the `y` field is base58 (reusing [encodeBase58] from
 * Bip32.kt, MEGA's one existing base58 implementation) over the value's
 * fixed 32-byte representation, so encode/decode round-trips exactly
 * regardless of how many leading zero bytes `y` happens to have.
 */
fun encodeBackupShare(share: BackupShare): String {
    val yBase58 = encodeBase58(share.y.toFixed32Bytes())
    return "${share.x}.$yBase58.${share.threshold}.${share.integrityTag}"
}

/** Inverse of [encodeBackupShare]. Rejects anything that isn't exactly
 * four dot-separated fields in the expected shape - a share string is
 * handwritten/retyped input, and a malformed one must fail loudly here
 * rather than silently producing a share with a wrong x or y. */
fun decodeBackupShare(text: String): BackupShare {
    val trimmed = text.trim()
    val fields = trimmed.split(".")
    require(fields.size == 4) { "A backup share must have exactly 4 dot-separated fields, got ${fields.size}" }

    val x = fields[0].toIntOrNull()
    require(x != null && x in 1..255) { "Backup share index must be an integer between 1 and 255" }

    val yBytes = try {
        decodeBase58(fields[1])
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Backup share value is not valid base58", e)
    }
    require(yBytes.isNotEmpty()) { "Backup share value must not be empty" }
    val y = yBytes.toPositiveBigInteger()
    require(y < Secp256k1.N) { "Backup share value is out of the secp256k1 scalar field's range" }

    val threshold = fields[2].toIntOrNull()
    require(threshold != null && threshold >= 2) { "Backup share threshold must be an integer of at least 2" }

    val integrityTag = fields[3]
    require(integrityTag.matches(Regex("[0-9a-f]{8}"))) { "Backup share integrity tag must be 8 lowercase hex characters" }

    return BackupShare(x = x, y = y, threshold = threshold, integrityTag = integrityTag)
}
