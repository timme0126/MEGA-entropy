package org.mega.entropy.ui.advancedmode

import java.util.Base64

/**
 * Turns the raw bytes of a user-picked file into PSBT bytes ready for the
 * exact same downstream path a successful QR scan uses, or throws
 * [IllegalArgumentException] whose message is always a friendly,
 * user-facing sentence — every failure route here is wrapped, so a caller
 * can render `e.message` directly.
 *
 * Accepted formats, checked in order:
 *  - raw binary PSBT: the 5-byte BIP174 magic `psbt` + 0xFF
 *  - base64-encoded PSBT: single-line or wrapped across \n / \r\n lines,
 *    decoded first and then verified to start with the same magic —
 *    base64 text is never trusted until the decoded bytes prove it is a
 *    PSBT.
 *
 * Surrounding whitespace/newlines are tolerated on both. Validation is by
 * content sniff only; the file's extension is never consulted (the picker
 * deliberately accepts anything and this function decides).
 *
 * Deliberately no Log calls on this path: an exception message can carry
 * user-chosen file content or paths, and this app's policy is no Log calls
 * at all on sensitive paths (same reasoning as BackupCard.readBackupBytes).
 *
 * Uses java.util.Base64 rather than android.util.Base64 purely so plain
 * JUnit4 tests (no Robolectric) exercise this file — see [decodeBase64]
 * for why the MIME decoder matches the existing scan-path behavior.
 */
fun preparePsbtFileBytes(bytes: ByteArray): ByteArray {
    val trimmed = trimSurroundingWhitespace(bytes)
    if (trimmed.isEmpty()) {
        throw IllegalArgumentException("This file is empty, so it cannot be a PSBT.")
    }
    if (startsWithPsbtMagic(trimmed)) {
        return trimmed
    }
    val decoded = decodeBase64(trimmed)
    if (!startsWithPsbtMagic(decoded)) {
        throw IllegalArgumentException(
            "This file is not a PSBT. It contains neither PSBT bytes nor base64-encoded PSBT text.",
        )
    }
    return decoded
}

/**
 * BIP174 magic: ASCII "psbt" followed by the 0xFF separator — identical to
 * the check parsePsbt() performs in entropy-core (Psbt.kt), repeated here
 * only as a cheap pre-filter so a wrong file gets a friendly message
 * instead of reaching the parser. parsePsbt remains the real validator
 * downstream via onScanned() — no parallel parser is introduced.
 */
private val PSBT_MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xFF.toByte())

private fun startsWithPsbtMagic(bytes: ByteArray): Boolean {
    if (bytes.size < PSBT_MAGIC.size) return false
    for (i in PSBT_MAGIC.indices) {
        if (bytes[i] != PSBT_MAGIC[i]) return false
    }
    return true
}

/**
 * Strips leading/trailing whitespace (any byte that maps, as ISO-8859-1,
 * to a whitespace char — covers space, \t, \n, \r, incl. the \r\n pair).
 * Only the outer edges are touched; interior bytes are never rewritten.
 */
private fun trimSurroundingWhitespace(bytes: ByteArray): ByteArray {
    var start = 0
    var endExclusive = bytes.size
    while (start < endExclusive && (bytes[start].toInt() and 0xFF).toChar().isWhitespace()) start++
    while (endExclusive > start && (bytes[endExclusive - 1].toInt() and 0xFF).toChar().isWhitespace()) endExclusive--
    return if (start == 0 && endExclusive == bytes.size) bytes else bytes.copyOfRange(start, endExclusive)
}

/**
 * Decodes base64 text. The MIME decoder is the JVM equivalent of the
 * android.util.Base64.DEFAULT flags the existing QR scan path already
 * uses (PsbtScanScreen's handleDecodedText): both tolerate \r\n line
 * wrapping, which is exactly how base64 .txt/.b64 exports are conventionally
 * wrapped at 64/76 chars. The strict plain decoder is the fallback for
 * single-line files.
 *
 * NOTE: android.util.Base64 is deliberately NOT used here — it is a
 * framework class, so plain JUnit4 unit tests would fail on it with
 * "not mocked". java.util.Base64 requires minSdk 26; this app is minSdk 29.
 */
private fun decodeBase64(bytes: ByteArray): ByteArray {
    val text = bytes.toString(Charsets.ISO_8859_1)
    try {
        val mimeDecoded = Base64.getMimeDecoder().decode(text)
        if (mimeDecoded.isNotEmpty()) return mimeDecoded
    } catch (_: IllegalArgumentException) {
        // Fall through to the strict decoder below.
    }
    return try {
        Base64.getDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException(
            "This file is neither a raw PSBT nor readable base64 text.",
        )
    }
}
