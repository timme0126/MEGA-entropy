package org.mega.entropycore

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** RFC4648 Base32 alphabet — the alphabet BBQr's '2' and 'Z' encodings use
 * (see https://github.com/coinkite/BBQr/blob/master/BBQr.md, "Advanced
 * Encodings"). No padding character is ever present in a BBQr payload. */
private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
private const val BASE36_DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

/** One QR's worth of a BBQr-encoded file — the format Sparrow, Coldcard,
 * and other Bitcoin tools use to spread a file (a PSBT, or as with MEGA's
 * own multisig descriptor import, a text/JSON export) across a series of
 * QR codes, identified by an 8-character "B$" header:
 * `B$<encoding><fileType><total><index><payload...>`. [total] and [index]
 * are the header's base-36 fields decoded to plain ints; [payload] is
 * everything after the 8-character header, still in [encoding]'s raw text
 * form (not yet decoded to bytes — see [assembleBbqrParts]). */
data class BbqrPart(
    val encoding: Char,
    val fileType: Char,
    val total: Int,
    val index: Int,
    val payload: String,
)

/**
 * Parses one scanned QR's text as a BBQr part header. Returns null for
 * anything that isn't shaped like a BBQr part at all — no "B$" prefix, a
 * malformed total/index field, or an out-of-range index — so a caller can
 * fall back to treating the text as an ordinary single-QR payload, exactly
 * how a bare xpub/cosigner-fragment/full-descriptor QR already works.
 * Deliberately does NOT validate [BbqrPart.encoding]/[BbqrPart.fileType]
 * here (any character is accepted) — that's [assembleBbqrParts]'s job,
 * once every part has actually been collected and there's something
 * concrete to report as unsupported; a not-yet-recognized encoding
 * shouldn't stop this part from being recognized as fragment of *some*
 * BBQr sequence.
 */
fun parseBbqrPart(text: String): BbqrPart? {
    if (!text.startsWith("B$") || text.length < 8) return null
    val total = text.substring(4, 6).parseBase36() ?: return null
    val index = text.substring(6, 8).parseBase36() ?: return null
    if (total < 1 || index !in 0 until total) return null
    return BbqrPart(encoding = text[2], fileType = text[3], total = total, index = index, payload = text.substring(8))
}

private fun String.parseBase36(): Int? {
    if (length != 2) return null
    var value = 0
    for (ch in this) {
        val digit = BASE36_DIGITS.indexOf(ch)
        if (digit < 0) return null
        value = value * 36 + digit
    }
    return value
}

/**
 * Assembles a COMPLETE set of [BbqrPart]s — every index 0 until total,
 * scanned in any order per the BBQr spec — into the original file's text
 * content. Throws [IllegalArgumentException] with a specific message if
 * the parts disagree with each other, are missing any index, or use an
 * encoding/file type this function doesn't support — callers needing to
 * know "are we done scanning yet" should check completeness themselves
 * before calling this (see AdvancedModeMultisigScannerScreen), since this
 * function only knows how to succeed or fail, not "keep waiting."
 *
 * Only 'U' (Unicode text) and 'J' (JSON) file types are supported — the
 * only shapes a multisig descriptor export could plausibly take. 'P'
 * (PSBT), 'T' (transaction), 'C' (CBOR), 'B' (binary), and 'X'
 * (executable) are all rejected with a clear error rather than silently
 * misinterpreted as text.
 *
 * For 'J', a best-effort `"descriptor": "..."` field extraction runs on
 * the decoded JSON text — Sparrow's own wallet-export JSON wraps the
 * descriptor string inside an object rather than sending it bare — falling
 * back to the raw decoded text if no such field is found.
 */
fun assembleBbqrParts(parts: List<BbqrPart>): String {
    require(parts.isNotEmpty()) { "No BBQr parts to assemble." }
    val total = parts.first().total
    val encoding = parts.first().encoding
    val fileType = parts.first().fileType
    require(parts.all { it.total == total }) { "Scanned BBQr parts disagree on the total number of parts." }
    require(parts.all { it.encoding == encoding }) { "Scanned BBQr parts disagree on their encoding." }
    require(parts.all { it.fileType == fileType }) { "Scanned BBQr parts disagree on their file type." }
    val byIndex = parts.associateBy { it.index }
    require((0 until total).all { it in byIndex }) {
        "Missing BBQr parts — only ${byIndex.size} of $total have been scanned so far."
    }
    require(fileType == 'U' || fileType == 'J') {
        "Unsupported BBQr file type '$fileType' — MEGA can only import a text or JSON descriptor export ('U' or 'J')."
    }

    val combinedPayload = (0 until total).joinToString("") { byIndex.getValue(it).payload }
    val decodedBytes = when (encoding) {
        'H' -> decodeBbqrHex(combinedPayload)
        '2' -> decodeBase32(combinedPayload)
        'Z' -> inflateRawDeflate(decodeBase32(combinedPayload))
        else -> throw IllegalArgumentException("Unsupported BBQr encoding '$encoding'.")
    }
    val text = decodedBytes.toString(Charsets.UTF_8)
    return if (fileType == 'J') extractJsonDescriptorField(text) ?: text else text
}

private fun decodeBbqrHex(text: String): ByteArray {
    require(text.length % 2 == 0) { "BBQr hex payload has an odd number of characters." }
    return ByteArray(text.length / 2) { i ->
        val high = Character.digit(text[i * 2], 16)
        val low = Character.digit(text[i * 2 + 1], 16)
        require(high >= 0 && low >= 0) { "BBQr hex payload contains a non-hex character." }
        ((high shl 4) or low).toByte()
    }
}

/** Standard 5-bits-per-character Base32 decode, MSB-first, no padding.
 * [bits] only ever needs to hold a handful of not-yet-emitted bits
 * between characters (never a whole accumulated stream) because it's
 * masked back down to just the unconsumed remainder after every byte
 * emitted — without that masking, [bits] would grow by 5 bits per
 * character indefinitely and overflow Int well before a realistic BBQr
 * payload (a few hundred characters) finished decoding. */
private fun decodeBase32(text: String): ByteArray {
    var bits = 0
    var bitCount = 0
    val output = ByteArrayOutputStream((text.length * 5) / 8 + 1)
    for (ch in text) {
        val value = BASE32_ALPHABET.indexOf(ch)
        require(value >= 0) { "BBQr Base32 payload contains an invalid character: '$ch'." }
        bits = (bits shl 5) or value
        bitCount += 5
        if (bitCount >= 8) {
            bitCount -= 8
            output.write((bits shr bitCount) and 0xFF)
            bits = bits and ((1 shl bitCount) - 1)
        }
    }
    return output.toByteArray()
}

/** Zlib-inflates a raw (headerless) deflate stream — the fixed compression
 * BBQr's 'Z' encoding requires (wbits=10, no zlib/gzip header — see
 * BBQr.md's "Advanced Encodings" section). [Inflater]'s nowrap=true mode
 * decodes raw deflate without needing the encoder's window size (2^10 =
 * 1KB) configured explicitly: a decompressor's window only needs to be AT
 * LEAST as large as the one used to compress, and Inflater's default
 * window comfortably exceeds 1KB. */
private fun inflateRawDeflate(compressed: ByteArray): ByteArray {
    val inflater = Inflater(true)
    inflater.setInput(compressed)
    val output = ByteArrayOutputStream(compressed.size * 3)
    val buffer = ByteArray(4096)
    try {
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            output.write(buffer, 0, count)
        }
    } catch (e: DataFormatException) {
        throw IllegalArgumentException("Could not decompress BBQr data (zlib error).", e)
    } finally {
        inflater.end()
    }
    return output.toByteArray()
}

private val JSON_DESCRIPTOR_FIELD_REGEX = Regex(""""descriptor"\s*:\s*"((?:[^"\\]|\\.)*)"""")

private fun extractJsonDescriptorField(json: String): String? {
    val match = JSON_DESCRIPTOR_FIELD_REGEX.find(json) ?: return null
    return match.groupValues[1].replace("\\/", "/").replace("\\\"", "\"")
}
