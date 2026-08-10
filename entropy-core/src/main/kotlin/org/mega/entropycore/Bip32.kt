package org.mega.entropycore

import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Shared BIP32 primitives — originally lived only inside Bip85.kt (which
 * needs a hardened-only private-key chain to reach a BIP85 derivation
 * path), now also used by the wallet-derivation code (WalletDerivation.kt)
 * which needs the full private+public, hardened+non-hardened chain plus
 * extended-public-key serialization. Kept in one file so a reviewer
 * checking "is BIP32 child derivation implemented correctly" has exactly
 * one place to look, instead of two copies that could quietly diverge.
 */
internal const val HARDENED_OFFSET = 0x80000000L

internal data class Bip32ExtendedPrivateKey(
    val privateKey: ByteArray,
    val chainCode: ByteArray,
    val depth: Int = 0,
    val parentFingerprint: ByteArray = ByteArray(4),
    val childNumber: Long = 0,
) {
    init {
        require(privateKey.size == 32) { "BIP32 private key must be 32 bytes" }
        require(chainCode.size == 32) { "BIP32 chain code must be 32 bytes" }
        require(parentFingerprint.size == 4) { "Parent fingerprint must be 4 bytes" }
        val keyValue = privateKey.toPositiveBigInteger()
        require(keyValue >= BigInteger.ONE && keyValue < Secp256k1.N) {
            "BIP32 private key must be in secp256k1 range"
        }
    }

    /** 33-byte SEC1 compressed public key derived from this private key. */
    fun compressedPublicKey(): ByteArray = Secp256k1.publicKeyFromPrivateKey(privateKey)

    /** First 4 bytes of HASH160(compressed pubkey) — this key's identifier
     * when it is the *parent* of the next derivation step. */
    fun fingerprint(): ByteArray = hash160(compressedPublicKey()).copyOfRange(0, 4)
}

/** BIP32 CKDpriv: derives a child private key, hardened or not. Hardened
 * (index + 2^31) mixes in the parent PRIVATE key; non-hardened mixes in
 * the parent's PUBLIC key, which is what lets an xpub alone (no private
 * key) derive further public non-hardened children — not used by this
 * app today, but it's why the split exists at all in BIP32. */
internal fun Bip32ExtendedPrivateKey.deriveChild(index: Long, hardened: Boolean): Bip32ExtendedPrivateKey {
    require(index in 0 until HARDENED_OFFSET) { "Child index must be unhardened before hardening, got $index" }
    val childNumber = if (hardened) index + HARDENED_OFFSET else index

    val data = ByteArray(37)
    if (hardened) {
        data[0] = 0
        privateKey.copyInto(data, destinationOffset = 1)
    } else {
        compressedPublicKey().copyInto(data, destinationOffset = 0)
    }
    writeUInt32BigEndian(childNumber, data, 33)

    val digest = hmacSha512(chainCode, data)
    val left = digest.copyOfRange(0, 32).toPositiveBigInteger()
    require(left < Secp256k1.N) { "Invalid BIP32 child key: left half is outside curve order" }

    val parent = privateKey.toPositiveBigInteger()
    val child = left.add(parent).mod(Secp256k1.N)
    require(child != BigInteger.ZERO) { "Invalid BIP32 child key: zero private key" }

    return Bip32ExtendedPrivateKey(
        privateKey = child.toFixed32Bytes(),
        chainCode = digest.copyOfRange(32, 64),
        depth = depth + 1,
        parentFingerprint = fingerprint(),
        childNumber = childNumber,
    )
}

internal fun Bip32ExtendedPrivateKey.deriveHardenedChild(index: Long): Bip32ExtendedPrivateKey =
    deriveChild(index, hardened = true)

internal fun bip32MasterKeyFromSeed(seed: ByteArray): Bip32ExtendedPrivateKey {
    // BIP32 itself accepts any 128-to-512-bit (16-to-64-byte) seed. This
    // app's only caller feeds it the 64-byte output of BIP39's deriveSeed,
    // but the official BIP32 test vectors (Bip32Test.kt) use a 16-byte
    // seed directly, so the check matches the spec, not just this app's
    // one call site.
    require(seed.size in 16..64) { "BIP32 seed must be 16 to 64 bytes, got ${seed.size}" }
    val digest = hmacSha512("Bitcoin seed".toByteArray(Charsets.US_ASCII), seed)
    return Bip32ExtendedPrivateKey(
        privateKey = digest.copyOfRange(0, 32),
        chainCode = digest.copyOfRange(32, 64),
    )
}

internal fun hmacSha512(key: ByteArray, message: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA512")
    mac.init(SecretKeySpec(key, "HmacSHA512"))
    return mac.doFinal(message)
}

internal fun writeUInt32BigEndian(value: Long, target: ByteArray, offset: Int) {
    require(value in 0..0xFFFF_FFFFL) { "Value does not fit uint32: $value" }
    target[offset] = ((value ushr 24) and 0xFF).toByte()
    target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
    target[offset + 3] = (value and 0xFF).toByte()
}

internal fun ByteArray.toPositiveBigInteger(): BigInteger = BigInteger(1, this)

internal fun BigInteger.toFixed32Bytes(): ByteArray {
    val raw = toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
    require(raw.size <= 32) { "Integer does not fit in 32 bytes" }
    return ByteArray(32 - raw.size) + raw
}

private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

internal fun encodeBase58(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    var number = BigInteger(1, bytes)
    val fifty8 = BigInteger.valueOf(58)
    val sb = StringBuilder()
    while (number > BigInteger.ZERO) {
        val (quotient, remainder) = number.divideAndRemainder(fifty8)
        sb.append(BASE58_ALPHABET[remainder.toInt()])
        number = quotient
    }
    val leadingZeroCount = bytes.takeWhile { it == 0.toByte() }.size
    repeat(leadingZeroCount) { sb.append('1') }
    return sb.reverse().toString()
}

internal fun encodeBase58Check(payload: ByteArray): String {
    val checksum = sha256(sha256(payload)).copyOfRange(0, 4)
    return encodeBase58(payload + checksum)
}

internal fun decodeBase58(value: String): ByteArray {
    var number = BigInteger.ZERO
    for (char in value) {
        val digit = BASE58_ALPHABET.indexOf(char)
        require(digit >= 0) { "Invalid Base58 character: $char" }
        number = number.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
    }

    val leadingZeroCount = value.takeWhile { it == '1' }.length
    val bytes = number.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
    return ByteArray(leadingZeroCount) + bytes
}

internal fun decodeBase58Check(value: String): ByteArray {
    val raw = decodeBase58(value)
    require(raw.size >= 5) { "Base58Check payload is too short" }
    val payload = raw.copyOfRange(0, raw.size - 4)
    val checksum = raw.copyOfRange(raw.size - 4, raw.size)
    val expected = sha256(sha256(payload)).copyOfRange(0, 4)
    require(checksum.contentEquals(expected)) { "Base58Check checksum mismatch" }
    return payload
}

/** Decodes a root (depth 0) BIP32 extended private key (xprv) string —
 * used only to test BIP85 derivation against the official BIP85 vectors,
 * which are published as an xprv rather than a raw seed. */
internal fun decodeBip32RootXprv(xprv: String): Bip32ExtendedPrivateKey {
    val decoded = decodeBase58Check(xprv)
    require(decoded.size == 78) { "BIP32 extended private key payload must be 78 bytes, got ${decoded.size}" }
    val version = decoded.copyOfRange(0, 4).toHex()
    require(version == "0488ade4") { "Only mainnet xprv keys are supported" }
    require(decoded[4].toInt() == 0) { "Only root xprv keys are supported for BIP85 root input" }
    require(decoded.copyOfRange(5, 9).all { it == 0.toByte() }) { "Root xprv parent fingerprint must be zero" }
    require(decoded.copyOfRange(9, 13).all { it == 0.toByte() }) { "Root xprv child number must be zero" }
    require(decoded[45].toInt() == 0) { "BIP32 private key payload must have a leading zero byte" }
    return Bip32ExtendedPrivateKey(
        chainCode = decoded.copyOfRange(13, 45),
        privateKey = decoded.copyOfRange(46, 78),
    )
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/** Bitcoin network for address/extended-key formatting. Derivation math
 * itself doesn't depend on network — only version bytes and address
 * prefixes do. */
internal enum class Bip32Network { MAINNET, TESTNET }

/** Which BIP defines the account-level derivation path and, correspondingly,
 * which extended-public-key version bytes / address format to use — see
 * SLIP-132 for the xpub/ypub/zpub version byte registry. Taproot (BIP86,
 * "P2TR"/no widely-used xpub prefix) is intentionally not included yet. */
internal enum class ExtendedKeyScriptType(val purpose: Long) {
    LEGACY(44L),
    NESTED_SEGWIT(49L),
    NATIVE_SEGWIT(84L),
}

private fun extendedPublicKeyVersionBytes(scriptType: ExtendedKeyScriptType, network: Bip32Network): ByteArray =
    when (network) {
        Bip32Network.MAINNET -> when (scriptType) {
            ExtendedKeyScriptType.LEGACY -> byteArrayOf(0x04, 0x88.toByte(), 0xB2.toByte(), 0x1E)
            ExtendedKeyScriptType.NESTED_SEGWIT -> byteArrayOf(0x04, 0x9D.toByte(), 0x7C, 0xB2.toByte())
            ExtendedKeyScriptType.NATIVE_SEGWIT -> byteArrayOf(0x04, 0xB2.toByte(), 0x47, 0x46)
        }
        Bip32Network.TESTNET -> when (scriptType) {
            ExtendedKeyScriptType.LEGACY -> byteArrayOf(0x04, 0x35, 0x87.toByte(), 0xCF.toByte())
            ExtendedKeyScriptType.NESTED_SEGWIT -> byteArrayOf(0x04, 0x4A, 0x52, 0x62)
            ExtendedKeyScriptType.NATIVE_SEGWIT -> byteArrayOf(0x04, 0x5F, 0x1C, 0xF6.toByte())
        }
    }

/** Serializes this key's PUBLIC half as a base58check xpub/ypub/zpub (or
 * testnet tpub/upub/vpub) string per SLIP-132. Never touches the private
 * key beyond deriving the public key that's always shown alongside it. */
internal fun Bip32ExtendedPrivateKey.serializeExtendedPublicKey(
    scriptType: ExtendedKeyScriptType,
    network: Bip32Network,
): String {
    val payload = ByteArray(78)
    extendedPublicKeyVersionBytes(scriptType, network).copyInto(payload, 0)
    payload[4] = depth.toByte()
    parentFingerprint.copyInto(payload, 5)
    writeUInt32BigEndian(childNumber, payload, 9)
    chainCode.copyInto(payload, 13)
    compressedPublicKey().copyInto(payload, 45)
    return encodeBase58Check(payload)
}
