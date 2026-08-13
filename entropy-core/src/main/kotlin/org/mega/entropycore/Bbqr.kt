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
    val (fileType, decodedBytes) = combineAndDecodeBbqrParts(parts, setOf('U', 'J'))
    val text = decodedBytes.toString(Charsets.UTF_8)
    return if (fileType == 'J') extractJsonDescriptorField(text) ?: text else text
}

/**
 * Same completeness/consistency checks as [assembleBbqrParts], but for a
 * binary file type — a PSBT ('P') or raw transaction ('T') — where the
 * decoded bytes are the answer itself, not UTF-8 text to search for a
 * JSON field in. This is what a PSBT-import scanner uses.
 */
fun assembleBbqrPartsAsBytes(parts: List<BbqrPart>): ByteArray =
    combineAndDecodeBbqrParts(parts, setOf('P', 'T')).second

/** Validates a complete, self-consistent set of [BbqrPart]s and decodes
 * their combined payload to raw bytes, per [encoding]. Shared by both
 * [assembleBbqrParts] (text/JSON) and [assembleBbqrPartsAsBytes]
 * (PSBT/transaction) — every step through decoding is identical between
 * them; only what to do with the resulting bytes differs. */
private fun combineAndDecodeBbqrParts(parts: List<BbqrPart>, supportedFileTypes: Set<Char>): Pair<Char, ByteArray> {
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
    require(fileType in supportedFileTypes) {
        "Unsupported BBQr file type '$fileType' — expected one of $supportedFileTypes."
    }

    // Decodes each part's payload to bytes INDEPENDENTLY, then concatenates the
    // resulting byte arrays — NOT "concatenate every part's raw text first, then
    // decode the combined string once" (a prior version of this function did
    // that, and it silently tolerated per-part chunk lengths that don't
    // themselves decode to a whole number of bytes). The BBQr spec is explicit
    // that this is wrong: "Just as it is an error to send an odd number of hex
    // digits in a QR block, for Base32 you must send complete bytes."
    // (https://github.com/coinkite/BBQr/blob/master/BBQr.md). A spec-compliant
    // decoder (e.g. Sparrow, which uses Guava's strict BaseEncoding) decodes
    // each block on its own and rejects one that doesn't — decoding the same
    // way here means a chunk [encodeBbqr] could never legally produce is caught
    // immediately as a decode error instead of silently accepted by a decoder
    // lenient enough to paper over it.
    val orderedParts = (0 until total).map { byIndex.getValue(it) }
    val decodedBytes = when (encoding) {
        'H' -> orderedParts.fold(ByteArray(0)) { acc, part -> acc + decodeBbqrHex(part.payload) }
        '2' -> orderedParts.fold(ByteArray(0)) { acc, part -> acc + decodeBase32(part.payload) }
        'Z' -> {
            // Deflate is a single continuous compressed stream — unlike the
            // Base32 stage, it cannot be inflated one part at a time. Each
            // part's Base32 text is still decoded independently (per the spec
            // requirement above), then the resulting COMPRESSED bytes are
            // concatenated back into one stream before the single inflate call.
            val compressed = orderedParts.fold(ByteArray(0)) { acc, part -> acc + decodeBase32(part.payload) }
            inflateRawDeflate(compressed)
        }
        else -> throw IllegalArgumentException("Unsupported BBQr encoding '$encoding'.")
    }
    return fileType to decodedBytes
}

/** Maximum part-count BBQr's 2-character base-36 total/index fields can
 * address: 36^2 = 1296 (indices 0 until 1296). */
private const val MAX_BBQR_PARTS = 1296

/** Default payload size per part, in Base32 characters (excluding the
 * fixed 8-character "B$2<fileType><total><index>" header) — small enough
 * that each resulting QR stays comfortably scannable by a phone camera at
 * a normal viewing distance across an animated series, matching the kind
 * of part size other Bitcoin-signing-device BBQr exporters commonly use.
 * MUST be a multiple of 8 — see [encodeBbqr]'s partPayloadSize requirement. */
private const val DEFAULT_BBQR_PART_PAYLOAD_SIZE = 152

/**
 * Splits [data] into a sequence of complete BBQr part strings — each
 * ready to render directly as one frame of an animated QR export — using
 * the '2' (plain Base32, no compression) encoding. Plain Base32 was
 * chosen over 'Z' (deflate+Base32) for simplicity and to keep this
 * encoder's output trivially verifiable against [decodeBase32], which
 * this codebase already has tests for; over 'H' (hex) for using 3 payload
 * bits/char instead of 4, meaning fewer QR frames for the same data.
 *
 * [fileType] is a raw BBQr file-type character ('P' for PSBT, 'T' for a
 * raw transaction, etc — see the BBQr spec) — this function does not
 * validate it, since new file types may need exporting later and nothing
 * about splitting/encoding bytes actually depends on which one is used.
 *
 * [partPayloadSize] MUST be a multiple of 8: 8 Base32 characters encode
 * exactly 5 bytes with no leftover bits, so a multiple-of-8 chunk boundary
 * guarantees every part except possibly the last is independently a
 * "complete bytes" chunk per the BBQr spec's explicit requirement ("for
 * Base32 you must send complete bytes") — and since removing whole
 * multiples of 8 characters from the front of a validly-encoded payload
 * never changes its length's remainder mod 8, the trailing (possibly
 * shorter) last part inherits that same validity automatically, with no
 * special-casing needed. A part length that doesn't satisfy this (150, the
 * previous default, does not: 150 mod 8 = 6) produces BBQr output that
 * MEGA's own scanner round-trips successfully (since it used to decode
 * only after concatenating every part's raw text back together) but which
 * a spec-compliant decoder that decodes each part on its own — e.g.
 * Sparrow, which uses Guava's strict BaseEncoding — rejects outright with
 * a decoding error. This was a real interop bug: Sparrow reported "Error
 * scanning QR :: com.google.common.io.BaseEncoding$DecodingException:
 * Invalid input length 150" when scanning a PSBT MEGA exported at the old
 * default part size.
 */
fun encodeBbqr(fileType: Char, data: ByteArray, partPayloadSize: Int = DEFAULT_BBQR_PART_PAYLOAD_SIZE): List<String> {
    require(partPayloadSize > 0) { "partPayloadSize must be positive, got $partPayloadSize" }
    require(partPayloadSize % 8 == 0) {
        "partPayloadSize must be a multiple of 8 (8 Base32 characters = 5 complete bytes, " +
            "with no leftover bits) so every part independently decodes to a whole number of " +
            "bytes, per the BBQr spec's Base32 requirement — got $partPayloadSize"
    }
    val payload = encodeBase32(data)
    val total = if (payload.isEmpty()) 1 else (payload.length + partPayloadSize - 1) / partPayloadSize
    require(total <= MAX_BBQR_PARTS) {
        "Data too large to encode as BBQr at $partPayloadSize chars/part: would need $total parts (max $MAX_BBQR_PARTS)."
    }
    return (0 until total).map { index ->
        val start = index * partPayloadSize
        val end = minOf(start + partPayloadSize, payload.length)
        val chunk = payload.substring(start, end)
        "B$" + '2' + fileType + total.toBase36Pair() + index.toBase36Pair() + chunk
    }
}

private fun Int.toBase36Pair(): String {
    require(this in 0 until MAX_BBQR_PARTS) { "Value $this does not fit BBQr's 2-character base-36 field." }
    return "" + BASE36_DIGITS[this / 36] + BASE36_DIGITS[this % 36]
}

/** Standard 5-bits-per-character Base32 encode, MSB-first, no padding —
 * the exact mirror of [decodeBase32]. A final partial group of fewer than
 * 5 leftover bits is left-shifted to fill out one more full character
 * (matching [decodeBase32]'s "no padding character" contract: decoding
 * this encoder's own output never has a partial trailing byte to worry
 * about, since the decoder simply stops accumulating once the input
 * characters run out). */
private fun encodeBase32(data: ByteArray): String {
    val sb = StringBuilder((data.size * 8 + 4) / 5)
    var bits = 0
    var bitCount = 0
    for (byte in data) {
        bits = (bits shl 8) or (byte.toInt() and 0xFF)
        bitCount += 8
        while (bitCount >= 5) {
            bitCount -= 5
            sb.append(BASE32_ALPHABET[(bits shr bitCount) and 0x1F])
        }
    }
    if (bitCount > 0) {
        sb.append(BASE32_ALPHABET[(bits shl (5 - bitCount)) and 0x1F])
    }
    return sb.toString()
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

/** Characters mod 8 that a complete (no-padding) Base32 encoding of a
 * whole number of bytes can legally end on: 0 (0 leftover bits — an exact
 * multiple of 5 bytes), 2 (1 byte), 4 (2 bytes), 5 (3 bytes), 7 (4 bytes).
 * 1, 3, and 6 are impossible outputs of [encodeBase32] for any input and
 * indicate a truncated or corrupted chunk if seen by [decodeBase32]. */
private val VALID_BASE32_LENGTH_REMAINDERS = setOf(0, 2, 4, 5, 7)

/** Standard 5-bits-per-character Base32 decode, MSB-first, no padding.
 * [bits] only ever needs to hold a handful of not-yet-emitted bits
 * between characters (never a whole accumulated stream) because it's
 * masked back down to just the unconsumed remainder after every byte
 * emitted — without that masking, [bits] would grow by 5 bits per
 * character indefinitely and overflow Int well before a realistic BBQr
 * payload (a few hundred characters) finished decoding.
 *
 * Requires [text]'s length to be one every complete encoding can actually
 * produce (see [VALID_BASE32_LENGTH_REMAINDERS]) — matching the strict
 * validation a spec-compliant decoder (e.g. Sparrow, via Guava's
 * BaseEncoding) performs, rather than silently dropping whatever partial
 * bits are left over at the end of a length this function was never
 * meant to be handed as a complete unit. Without this check, a caller
 * accidentally handing this function a truncated chunk (e.g. one BBQr
 * part out of several, decoded independently) would get back silently
 * truncated — wrong — bytes instead of a clear error. */
private fun decodeBase32(text: String): ByteArray {
    require(text.length % 8 in VALID_BASE32_LENGTH_REMAINDERS) {
        "BBQr Base32 payload has an invalid length (${text.length} characters) — not a " +
            "complete encoding of a whole number of bytes."
    }
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
