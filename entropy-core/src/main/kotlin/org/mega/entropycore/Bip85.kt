package org.mega.entropycore

import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HARDENED_OFFSET = 0x80000000L
private val SECP256K1_ORDER = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)

/**
 * Supported BIP-85 BIP39 child mnemonic sizes for MEGA's UI. BIP-85 also
 * defines 15/18/21 words and non-BIP39 applications, but this app exposes
 * only the 12- and 24-word English cases users already understand here.
 */
enum class Bip85MnemonicWords(val wordCount: Int, val entropyBytes: Int) {
    TWELVE(wordCount = 12, entropyBytes = 16),
    TWENTY_FOUR(wordCount = 24, entropyBytes = 32),
}

data class Bip85DerivedMnemonic(
    val index: Long,
    val words: Bip85MnemonicWords,
    val path: String,
    val entropy: MnemonicEntropy,
    val mnemonicWords: List<String>,
)

internal data class Bip32ExtendedPrivateKey(
    val privateKey: ByteArray,
    val chainCode: ByteArray,
) {
    init {
        require(privateKey.size == 32) { "BIP32 private key must be 32 bytes" }
        require(chainCode.size == 32) { "BIP32 chain code must be 32 bytes" }
        val keyValue = privateKey.toPositiveBigInteger()
        require(keyValue >= BigInteger.ONE && keyValue < SECP256K1_ORDER) {
            "BIP32 private key must be in secp256k1 range"
        }
    }
}

/**
 * Derives a BIP-85 English BIP39 child mnemonic from a parent BIP39 mnemonic.
 * The path is m/83696968'/39'/0'/{12|24}'/{index}', where 0' is English.
 *
 * The optional passphrase is the parent BIP39 passphrase. Leaving it empty
 * matches the normal no-passphrase BIP39 seed case.
 */
fun deriveBip85Bip39Mnemonic(
    parentWords: List<String>,
    childWords: Bip85MnemonicWords,
    index: Long,
    parentPassphrase: String = "",
): Bip85DerivedMnemonic {
    validateBip85ParentWords(parentWords)
    validateBip85Index(index)
    val seed = deriveSeed(parentWords, parentPassphrase)
    val master = bip32MasterKeyFromSeed(seed.bytes)
    return deriveBip85Bip39Mnemonic(master, childWords, index)
}

/**
 * Derives a BIP-85 English BIP39 child mnemonic from a BIP32 root xprv.
 * This overload exists mainly to test against the official BIP-85 vectors.
 */
internal fun deriveBip85Bip39Mnemonic(
    rootXprv: String,
    childWords: Bip85MnemonicWords,
    index: Long,
): Bip85DerivedMnemonic {
    validateBip85Index(index)
    return deriveBip85Bip39Mnemonic(decodeBip32RootXprv(rootXprv), childWords, index)
}

private fun deriveBip85Bip39Mnemonic(
    rootKey: Bip32ExtendedPrivateKey,
    childWords: Bip85MnemonicWords,
    index: Long,
): Bip85DerivedMnemonic {
    val pathIndexes = listOf(83696968L, 39L, 0L, childWords.wordCount.toLong(), index)
    val derivedKey = pathIndexes.fold(rootKey) { key, childIndex ->
        key.deriveHardenedChild(childIndex)
    }
    val bip85Entropy = hmacSha512(
        key = "bip-entropy-from-k".toByteArray(Charsets.US_ASCII),
        message = derivedKey.privateKey,
    )
    val entropyBytes = bip85Entropy.copyOfRange(0, childWords.entropyBytes)
    val entropy = MnemonicEntropy(entropyBytes)
    val mnemonicWords = mnemonicWordsFromEntropy(entropyBytes)
    val path = "m/83696968'/39'/0'/${childWords.wordCount}'/${index}'"
    return Bip85DerivedMnemonic(index, childWords, path, entropy, mnemonicWords)
}


private fun validateBip85ParentWords(parentWords: List<String>) {
    require(parentWords.size == 12 || parentWords.size == 24) {
        "BIP85 parent mnemonic must contain 12 or 24 words, got ${parentWords.size}"
    }
    val wordList = loadOfficialEnglishWordList()
    require(parentWords.all { it in wordList }) {
        "BIP85 parent mnemonic contains a word outside the official English BIP39 list"
    }
}

private fun validateBip85Index(index: Long) {
    require(index in 0 until HARDENED_OFFSET) {
        "BIP85 index must be between 0 and ${HARDENED_OFFSET - 1}, got $index"
    }
}

private fun bip32MasterKeyFromSeed(seed: ByteArray): Bip32ExtendedPrivateKey {
    require(seed.size == 64) { "BIP39 seed must be 64 bytes" }
    val digest = hmacSha512("Bitcoin seed".toByteArray(Charsets.US_ASCII), seed)
    return Bip32ExtendedPrivateKey(
        privateKey = digest.copyOfRange(0, 32),
        chainCode = digest.copyOfRange(32, 64),
    )
}

private fun Bip32ExtendedPrivateKey.deriveHardenedChild(index: Long): Bip32ExtendedPrivateKey {
    require(index in 0 until HARDENED_OFFSET) { "Child index must be unhardened before hardening, got $index" }
    val hardenedIndex = index + HARDENED_OFFSET
    val data = ByteArray(37)
    data[0] = 0
    privateKey.copyInto(data, destinationOffset = 1)
    writeUInt32BigEndian(hardenedIndex, data, 33)

    val digest = hmacSha512(chainCode, data)
    val left = digest.copyOfRange(0, 32).toPositiveBigInteger()
    require(left < SECP256K1_ORDER) { "Invalid BIP32 child key: left half is outside curve order" }

    val parent = privateKey.toPositiveBigInteger()
    val child = left.add(parent).mod(SECP256K1_ORDER)
    require(child != BigInteger.ZERO) { "Invalid BIP32 child key: zero private key" }

    return Bip32ExtendedPrivateKey(
        privateKey = child.toFixed32Bytes(),
        chainCode = digest.copyOfRange(32, 64),
    )
}

private fun mnemonicWordsFromEntropy(entropyBytes: ByteArray): List<String> {
    val checksum = calculateChecksum(entropyBytes)
    val bitStream = buildBitStream(entropyBytes, checksum.checksumBits)
    val indices = splitInto11BitGroups(bitStream)
    return deriveWords(indices, loadOfficialEnglishWordList())
}

private fun decodeBip32RootXprv(xprv: String): Bip32ExtendedPrivateKey {
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

private fun decodeBase58Check(value: String): ByteArray {
    val raw = decodeBase58(value)
    require(raw.size >= 5) { "Base58Check payload is too short" }
    val payload = raw.copyOfRange(0, raw.size - 4)
    val checksum = raw.copyOfRange(raw.size - 4, raw.size)
    val expected = bip85Sha256(bip85Sha256(payload)).copyOfRange(0, 4)
    require(checksum.contentEquals(expected)) { "Base58Check checksum mismatch" }
    return payload
}

private fun decodeBase58(value: String): ByteArray {
    val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    var number = BigInteger.ZERO
    for (char in value) {
        val digit = alphabet.indexOf(char)
        require(digit >= 0) { "Invalid Base58 character: $char" }
        number = number.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
    }

    val leadingZeroCount = value.takeWhile { it == '1' }.length
    val bytes = number.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
    return ByteArray(leadingZeroCount) + bytes
}

private fun hmacSha512(key: ByteArray, message: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA512")
    mac.init(SecretKeySpec(key, "HmacSHA512"))
    return mac.doFinal(message)
}

private fun bip85Sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

private fun writeUInt32BigEndian(value: Long, target: ByteArray, offset: Int) {
    require(value in 0..0xFFFF_FFFFL) { "Value does not fit uint32: $value" }
    target[offset] = ((value ushr 24) and 0xFF).toByte()
    target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
    target[offset + 3] = (value and 0xFF).toByte()
}

private fun ByteArray.toPositiveBigInteger(): BigInteger = BigInteger(1, this)

private fun BigInteger.toFixed32Bytes(): ByteArray {
    val raw = toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
    require(raw.size <= 32) { "Integer does not fit in 32 bytes" }
    return ByteArray(32 - raw.size) + raw
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
