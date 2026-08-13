package org.mega.entropycore

import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC6979 deterministic ECDSA signing over secp256k1.
 * Implements signEcdsaRaw, signEcdsaDer, and verifyEcdsa per the specification.
 */
data class EcdsaSignature(val r: ByteArray, val s: ByteArray)

/**
 * HMAC-SHA256 helper following the existing module's crypto style.
 * Used exclusively for RFC6979 deterministic nonce generation.
 */
internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

internal fun signEcdsaRaw(privateKey: ByteArray, messageHash32: ByteArray): EcdsaSignature {
    // Step 1: Validate inputs strictly before any crypto operations.
    // A single byte mismatch here could silently proceed with invalid state.
    if (privateKey.size != 32) throw IllegalArgumentException("privateKey must be 32 bytes, got ${privateKey.size}")
    if (messageHash32.size != 32) throw IllegalArgumentException("messageHash32 must be 32 bytes, got ${messageHash32.size}")
    
    val d = privateKey.toPositiveBigInteger()
    if (d.signum() <= 0 || d >= Secp256k1.N) throw IllegalArgumentException("privateKey must be in [1, N-1]")
    
    // Step 2: h1 is the message hash directly. Since hlen == 32 (SHA-256 output)
    // and the curve order is also 256 bits, bits2octets simplifies to a direct copy.
    val h1 = messageHash32
    val h1BigInt = h1.toPositiveBigInteger()
    
    // Step 3: RFC6979 section 3.2 initialization
    var V = ByteArray(32) { 0x01 }
    var K = ByteArray(32) { 0x00 }
    
    // Step 3c: K = HMAC(K, V || 0x00 || privkey || h1)
    K = hmacSha256(K, V + byteArrayOf(0x00) + privateKey + h1)
    // Step 3d: V = HMAC(K, V)
    V = hmacSha256(K, V)
    // Step 3e: K = HMAC(K, V || 0x01 || privkey || h1)
    K = hmacSha256(K, V + byteArrayOf(0x01) + privateKey + h1)
    // Step 3f: V = HMAC(K, V)
    V = hmacSha256(K, V)
    
    // Step 3g: Generate deterministic k
    val halfN = Secp256k1.N.shiftRight(1)
    while (true) {
        V = hmacSha256(K, V)
        val candidateK = V.toPositiveBigInteger()
        
        if (candidateK.signum() > 0 && candidateK < Secp256k1.N) {
            // Step 4: Attempt to compute signature with this k
            val R = Secp256k1.scalarMultiply(candidateK, Secp256k1.G)
            val r = R.x!!.mod(Secp256k1.N)
            
            if (r.signum() > 0) {
                val s = (candidateK.modInverse(Secp256k1.N) * (h1BigInt + r * d)).mod(Secp256k1.N)
                if (s.signum() > 0) {
                    // Step 5: Low-S normalization (BIP62/BIP146)
                    // Ensures signature malleability resistance and consensus validity.
                    val finalS = if (s > halfN) Secp256k1.N - s else s
                    return EcdsaSignature(r.toFixed32Bytes(), finalS.toFixed32Bytes())
                }
            }
        }
        
        // Step 3g retry path: k out of range or degenerate signature (r==0 or s==0)
        // RFC6979 mandates this fallback to guarantee a valid nonce exists.
        K = hmacSha256(K, V + byteArrayOf(0x00))
        V = hmacSha256(K, V)
    }
}

internal fun signEcdsaDer(privateKey: ByteArray, messageHash32: ByteArray): ByteArray {
    val sig = signEcdsaRaw(privateKey, messageHash32)
    val rDer = encodeDerInteger(sig.r)
    val sDer = encodeDerInteger(sig.s)
    val content = rDer + sDer
    return byteArrayOf(0x30.toByte(), content.size.toByte()) + content
}

/**
 * Encodes a 32-byte big-endian integer into minimal ASN.1 DER INTEGER format.
 * Strips leading zero bytes but prepends 0x00 if the high bit is set to
 * prevent misinterpretation as a negative number (ASN.1 INTEGER is signed).
 */
private fun encodeDerInteger(bytes: ByteArray): ByteArray {
    var start = 0
    while (start < bytes.size - 1 && bytes[start] == 0.toByte()) {
        start++
    }
    var stripped = bytes.copyOfRange(start, bytes.size)
    // Byte in Kotlin is signed, so `stripped[0] >= 0x80.toByte()` would compare
    // against 0x80.toByte() == -128 (two's-complement overflow) — true for
    // EVERY byte value, always prepending 0x00 regardless of the actual high
    // bit. Masking to an unsigned Int first is what actually tests "is the
    // high bit set".
    if ((stripped[0].toInt() and 0xFF) >= 0x80) {
        stripped = byteArrayOf(0x00) + stripped
    }
    return byteArrayOf(0x02.toByte(), stripped.size.toByte()) + stripped
}

internal fun verifyEcdsa(publicKey: ByteArray, messageHash32: ByteArray, derSignature: ByteArray): Boolean {
    return try {
        // Step 1: Wrap entire logic in try/catch. This function must NEVER throw,
        // per contract. Any malformed input or internal error returns false.
        if (derSignature.size < 8) return false // Minimum valid DER size
        if (derSignature[0] != 0x30.toByte()) return false
        
        val totalLen = derSignature[1].toInt() and 0xFF
        if (totalLen != derSignature.size - 2) return false
        
        var offset = 2
        
        // Parse r INTEGER
        if (derSignature[offset] != 0x02.toByte()) return false
        offset++
        val rLen = derSignature[offset].toInt() and 0xFF
        offset++
        if (offset + rLen > derSignature.size) return false
        val rBytes = derSignature.copyOfRange(offset, offset + rLen)
        offset += rLen
        val r = BigInteger(1, rBytes)
        
        // Parse s INTEGER
        if (derSignature[offset] != 0x02.toByte()) return false
        offset++
        val sLen = derSignature[offset].toInt() and 0xFF
        offset++
        if (offset + sLen > derSignature.size) return false
        val sBytes = derSignature.copyOfRange(offset, offset + sLen)
        offset += sLen
        val s = BigInteger(1, sBytes)
        
        // Reject if there are trailing bytes after the two integers
        if (offset != derSignature.size) return false
        
        // Step 3: Reject invalid r, s values
        if (r.signum() <= 0 || r >= Secp256k1.N) return false
        if (s.signum() <= 0 || s >= Secp256k1.N) return false
        // Step 4: Reject high-S signatures (must be low-S per signing convention)
        if (s > Secp256k1.N.shiftRight(1)) return false
        
        // Step 5: Decompress public key (throws on invalid input, caught by outer try/catch)
        val pubkeyPoint = Secp256k1.decompressPoint(publicKey)
        
        // Step 6: Standard ECDSA verification
        val z = messageHash32.toPositiveBigInteger()
        val w = s.modInverse(Secp256k1.N)
        val u1 = (z * w).mod(Secp256k1.N)
        val u2 = (r * w).mod(Secp256k1.N)
        
        // Handle zero scalars to avoid violating scalarMultiply's precondition
        val u1G = if (u1.signum() > 0) Secp256k1.scalarMultiply(u1, Secp256k1.G) else Secp256k1.INFINITY
        val u2Pub = if (u2.signum() > 0) Secp256k1.scalarMultiply(u2, pubkeyPoint) else Secp256k1.INFINITY
        
        val point = Secp256k1.pointAdd(u1G, u2Pub)
        
        if (point == Secp256k1.INFINITY) return false
        point.x!!.mod(Secp256k1.N) == r
    } catch (e: Exception) {
        false
    }
}
