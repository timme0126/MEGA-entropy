package org.mega.entropycore

/**
 * Custody backup envelope ("MEGACUSTODY-V1").
 *
 * A custody envelope is a sealed transport container for a *third-party
 * custodian* scenario: the client encrypts their standard MEGA backup
 * file plus one attached document under a key held by someone else
 * (e.g. a trusted third party). The custodian receives a single blob
 * they cannot open, and verifies transport integrity via the
 * container's own GCM tag plus a public SHA-256 fingerprint of the
 * whole file.
 *
 * Design rules mirror the existing MEGA-BACKUP-V1 file format:
 *
 *  - a plaintext header (same "KEY:value" line style, terminated by
 *    "END-HEADER") that carries the KDF cost parameters, salt, IV, and
 *    transport metadata (attachment name/size, embedded payload size);
 *  - the header bytes are used VERBATIM as the AES-GCM AAD, so any
 *    tampering with the header or metadata is detected at decrypt time;
 *  - hostile cost parameters are rejected during parsing, BEFORE any
 *    key derivation, so a crafted file cannot force unbounded memory;
 *  - the plaintext is itself a tiny framed document (magic + three
 *    length-prefixed records), fully covered by the ciphertext tag.
 *
 * Create and verify paths can run STREAMING (large attachments never
 * materialize in memory): the byte-for-byte identical framing is
 * exposed via the innerRecordPreamble helpers, innerLengthOf, and
 * parseHeaderPrefix so callers can emit and consume records
 * incrementally. Files produced by either path are interchangeable.
 *
 * This file is deliberately free of crypto and Android imports — the
 * KDF and cipher are injected by the caller, exactly like the rest of
 * entropy-core.
 */
object CustodyEnvelope {

    const val MAGIC = "MEGACUSTODY-V1"
    const val CURRENT_VERSION = 1
    const val INNER_MAGIC = "MEGACUSTODY-INNER-V1"
    const val HEADER_LINE_COUNT = 11
    const val MAX_FILENAME_BYTES = 256
    const val MAX_LENGTH_PREFIX_DIGITS = 12

    /** KDF cost ceilings — same spirit as the backup file parser. */
    const val MAX_N = 1 shl 20
    const val MAX_R = 16
    const val MAX_P = 8

    class Envelope(
        val version: Int,
        val n: Int,
        val r: Int,
        val p: Int,
        val salt: ByteArray,
        val iv: ByteArray,
        val attachmentName: String,
        val payloadSize: Long,
        val attachmentSize: Int,
        val aad: ByteArray,
        val ciphertext: ByteArray,
    )

    /** Header-only view for the streaming path; ciphertext starts at [headerLength]. */
    data class EnvelopeInfo(
        val aad: ByteArray,
        val n: Int,
        val r: Int,
        val p: Int,
        val salt: ByteArray,
        val iv: ByteArray,
        val attachmentName: String,
        val payloadSize: Long,
        val attachmentSize: Int,
        val headerLength: Int,
    )

    /**
     * Builds the plaintext header. The returned bytes MUST be used
     * verbatim as the AES-GCM AAD for the inner document — the whole
     * security argument is that header/metadata tampering then fails
     * authentication. [salt] and [iv] must be fresh CSPRNG output of
     * exactly 16 and 12 bytes.
     */
    fun buildHeader(
        n: Int,
        r: Int,
        p: Int,
        salt: ByteArray,
        iv: ByteArray,
        attachmentName: String,
        innerSize: Int,
        attachmentSize: Int,
    ): ByteArray {
        requireCosts(n, r, p)
        require(salt.size == 16) { "salt must be 16 bytes" }
        require(iv.size == 12) { "iv must be 12 bytes" }
        require(innerSize > 0) { "inner document is empty" }
        require(attachmentName.none { it == '\n' || it == '\r' }) {
            "attachment name must not contain line breaks"
        }
        require(attachmentName.toByteArray(Charsets.UTF_8).size <= MAX_FILENAME_BYTES) {
            "attachment name too long"
        }
        return buildString {
            append("$MAGIC\n")
            append("V:1\n")
            append("N:$n\n")
            append("R:$r\n")
            append("P:$p\n")
            append("SALT:${salt.toHex()}\n")
            append("IV:${iv.toHex()}\n")
            append("NAME:$attachmentName\n")
            append("PSIZE:$innerSize\n")
            append("FSIZE:$attachmentSize\n")
            append("END-HEADER\n")
        }.toByteArray(Charsets.US_ASCII)
    }

    /**
     * Parses ONLY the header and splits the ciphertext out — no
     * decryption, no derivation. Cost parameters are bounds-checked
     * here first, so a hostile file never reaches the KDF.
     */
    fun open(bytes: ByteArray): Envelope {
        val headerEnd = findHeaderEnd(bytes)
        require(headerEnd > 0) { "malformed envelope: header terminator missing" }
        val aad = bytes.copyOfRange(0, headerEnd)
        val ciphertext = bytes.copyOfRange(headerEnd, bytes.size)
        require(ciphertext.isNotEmpty()) { "malformed envelope: empty ciphertext" }
        val h = parseHeaderFields(aad)
        return Envelope(
            h.version, h.n, h.r, h.p, h.salt, h.iv, h.attachmentName,
            h.payloadSize, h.attachmentSize, aad, ciphertext,
        )
    }

    /**
     * Parses a header from a buffer whose first bytes form the complete
     * header — no ciphertext required (streaming verify path).
     */
    fun parseHeaderPrefix(head: ByteArray): EnvelopeInfo {
        val headerEnd = findHeaderEnd(head)
        require(headerEnd > 0) { "malformed envelope: header terminator missing" }
        val aad = head.copyOf(headerEnd)
        val h = parseHeaderFields(aad)
        return EnvelopeInfo(
            aad, h.n, h.r, h.p, h.salt, h.iv, h.attachmentName,
            h.payloadSize, h.attachmentSize, headerEnd,
        )
    }

    private data class HeaderFields(
        val version: Int,
        val n: Int,
        val r: Int,
        val p: Int,
        val salt: ByteArray,
        val iv: ByteArray,
        val attachmentName: String,
        val payloadSize: Long,
        val attachmentSize: Int,
    )

    private fun findHeaderEnd(bytes: ByteArray): Int {
        var headerEnd = -1
        var lines = 0
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == '\n'.code.toByte()) {
                lines++
                if (lines == HEADER_LINE_COUNT) {
                    headerEnd = i + 1
                    break
                }
            }
            i++
        }
        return headerEnd
    }

    private fun parseHeaderFields(aad: ByteArray): HeaderFields {
        val linesText = String(aad, Charsets.US_ASCII).split('\n')
        fun value(index: Int, key: String): String {
            val prefix = "$key:"
            val line = linesText[index]
            require(line.startsWith(prefix)) { "malformed envelope: expected $key line" }
            return line.substring(prefix.length)
        }
        require(linesText.size == HEADER_LINE_COUNT + 1) { "malformed envelope: bad header shape" }
        require(linesText[0] == MAGIC) { "not a MEGACUSTODY-V1 envelope" }
        val version = value(1, "V").toIntOrNull() ?: error("bad version")
        require(version == CURRENT_VERSION) { "unsupported envelope version $version" }
        val n = value(2, "N").toIntOrNull() ?: error("bad N")
        val r = value(3, "R").toIntOrNull() ?: error("bad R")
        val p = value(4, "P").toIntOrNull() ?: error("bad P")
        requireCosts(n, r, p)
        val salt = value(5, "SALT").hexToBytes()
        val iv = value(6, "IV").hexToBytes()
        require(salt.size == 16 && iv.size == 12) { "malformed envelope: salt/iv lengths" }
        val name = value(7, "NAME")
        val payloadSize = value(8, "PSIZE").toLongOrNull() ?: error("bad PSIZE")
        val attachmentSize = value(9, "FSIZE").toIntOrNull() ?: error("bad FSIZE")
        require(payloadSize > 0 && attachmentSize in 1 until (1 shl 30)) {
            "malformed envelope: declared sizes out of range"
        }
        return HeaderFields(version, n, r, p, salt, iv, name, payloadSize, attachmentSize)
    }

    /**
     * Frames the inner document: [backupFileBytes] is a complete
     * MEGA-BACKUP-V1 file, [attachment] one opaque document.
     */
    fun buildInner(backupFileBytes: ByteArray, filename: String, attachment: ByteArray): ByteArray {
        require(filename.isNotEmpty() && filename.toByteArray(Charsets.UTF_8).size <= MAX_FILENAME_BYTES) {
            "attachment name empty or too long"
        }
        require(attachment.isNotEmpty()) { "attachment is empty" }
        require(backupFileBytes.isNotEmpty()) { "backup file is empty" }
        val nameBytes = filename.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        out.write(INNER_MAGIC.toByteArray(Charsets.US_ASCII))
        out.write('\n'.code)
        writeLengthPrefixed(out, nameBytes)
        writeLengthPrefixed(out, backupFileBytes)
        writeLengthPrefixed(out, attachment)
        return out.toByteArray()
    }

    data class InnerDocument(
        val backupFileBytes: ByteArray,
        val attachmentName: String,
        val attachment: ByteArray,
    )

    /**
     * Unframes an inner document. The caller has already authenticated
     * the bytes via GCM; this only re-checks structure.
     */
    fun parseInner(inner: ByteArray): InnerDocument {
        var pos = 0
        fun take(n: Int): ByteArray {
            require(pos + n <= inner.size) { "truncated inner document" }
            val out = inner.copyOfRange(pos, pos + n)
            pos += n
            return out
        }
        val magic = take(INNER_MAGIC.length + 1)
        require(magic.contentEquals(INNER_MAGIC.toByteArray(Charsets.US_ASCII) + '\n'.code.toByte())) {
            "bad inner document magic"
        }
        fun takeLen(): Int {
            var digits = 0
            var value = 0
            while (true) {
                require(pos < inner.size && digits < MAX_LENGTH_PREFIX_DIGITS) { "bad length prefix" }
                val b = inner[pos].toInt() and 0xFF
                if (b == ':'.code) {
                    pos++
                    return value
                }
                require(b in '0'.code..'9'.code) { "bad length prefix" }
                value = value * 10 + (b - '0'.code)
                digits++
                pos++
            }
        }
        fun takePrefixed(): ByteArray = take(takeLen())
        val name = takePrefixed().toString(Charsets.UTF_8)
        val backup = takePrefixed()
        val attachment = takePrefixed()
        require(pos == inner.size) { "trailing bytes in inner document" }
        require(backup.isNotEmpty() && attachment.isNotEmpty() && name.isNotEmpty()) {
            "empty inner record"
        }
        return InnerDocument(backup, name, attachment)
    }

    // ---- streaming framing (byte-identical to buildInner for same inputs) ----

    /** Inner magic line + filename length prefix; filename bytes follow. */
    fun innerPreambleA(nameLen: Int): ByteArray =
        (INNER_MAGIC + "\n" + nameLen + ":").toByteArray(Charsets.US_ASCII)

    /** Backup record length prefix; backup bytes follow. */
    fun innerPreambleB(backupLen: Int): ByteArray =
        (backupLen.toString() + ":").toByteArray(Charsets.US_ASCII)

    /** Attachment record length prefix; attachment bytes follow. */
    fun innerPreambleC(attachLen: Int): ByteArray =
        (attachLen.toString() + ":").toByteArray(Charsets.US_ASCII)

    /** Exact framed inner length, computable before any bytes exist. */
    fun innerLengthOf(nameLen: Int, backupLen: Int, attachLen: Int): Int =
        INNER_MAGIC.length + 1 +
            "$nameLen".length + 1 + nameLen +
            "$backupLen".length + 1 + backupLen +
            "$attachLen".length + 1 + attachLen

    private fun writeLengthPrefixed(out: java.io.ByteArrayOutputStream, data: ByteArray) {
        out.write(data.size.toString().toByteArray(Charsets.US_ASCII))
        out.write(':'.code)
        out.write(data)
    }

    private fun requireCosts(n: Int, r: Int, p: Int) {
        require(n in 16..MAX_N && Integer.bitCount(n) == 1) { "scrypt N must be a power of two in 16..$MAX_N" }
        require(r in 1..MAX_R) { "scrypt r out of range" }
        require(p in 1..MAX_P) { "scrypt p out of range" }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "odd-length hex" }
        return ByteArray(length / 2) { i ->
            ((this[i * 2].digitToIntOrNull(16) ?: error("bad hex")).let { h1 ->
                (h1 shl 4) or (this[i * 2 + 1].digitToIntOrNull(16) ?: error("bad hex"))
            }.toByte())
        }
    }
}
