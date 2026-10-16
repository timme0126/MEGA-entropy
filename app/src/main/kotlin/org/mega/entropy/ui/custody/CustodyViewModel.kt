package org.mega.entropy.ui.custody

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.mega.entropy.security.backup.BACKUP_GCM_IV_LENGTH_BYTES
import org.mega.entropy.security.backup.BACKUP_GCM_TAG_LENGTH_BITS
import org.mega.entropy.security.backup.BACKUP_SALT_LENGTH_BYTES
import org.mega.entropy.security.backup.BACKUP_SCRYPT_N
import org.mega.entropy.security.backup.BACKUP_SCRYPT_P
import org.mega.entropy.security.backup.BACKUP_SCRYPT_R
import org.mega.entropy.security.backup.deriveBackupKey
import org.mega.entropy.storage.BackupRepository
import org.mega.entropycore.CustodyEnvelope

/** A sealed container written to the user-chosen destination. */
data class CreatedEnvelope(
    val fingerprintHex: String,
    val containerSizeBytes: Long,
)

/** A verified container; records were extracted only when [wroteFiles]. */
data class VerifyResult(
    val attachmentName: String,
    val attachmentSize: Int,
    val backupSizeBytes: Int,
    val wroteFiles: Boolean,
)

/**
 * Orchestrates the custody envelope around [BackupRepository.exportBackup]
 * (the standard, passphrase-encrypted backup document — so a restore
 * needs no special tooling) and the BackupCrypto scrypt + AES-GCM
 * primitives.
 *
 * One secret per arrangement: the SAME passphrase encrypts the inner
 * standard backup and the outer envelope, so the key a trusted third
 * party holds is the whole trust story. There is deliberately NO
 * recovery path and no second key — not even MEGA can open a
 * container.
 *
 * Large attachments are STREAMED (64 KB window) straight through the
 * cipher to the SAF destination — nothing attachment-sized is ever
 * held in memory (the earlier all-in-memory version OOMed on real
 * documents). [progress] narrates the phases so the slow scrypt step
 * reads as work, not a hang. Key material is zeroed as soon as each
 * use completes.
 */
class CustodyViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BackupRepository(application)

    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 256-bit passphrase as 64 hex chars, grouped for transcription. */
    fun generatePassphrase(): String =
        randomBytes(32)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            .chunked(8)
            .joinToString(" ")

    /** Display name + byte size of a SAF-picked document, with guards. */
    suspend fun describe(uri: Uri): Pair<String, Int> = withContext(Dispatchers.IO) {
        var name = "document"
        var size = -1
        getApplication<Application>().contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                size = if (c.isNull(1)) -1 else c.getInt(1)
            }
        }
        require(size > 0) { "Cannot determine the attachment size — re-pick the file" }
        require(name.none { it == '\n' || it == '\r' }) { "File name contains line breaks" }
        require(name.toByteArray(Charsets.UTF_8).size <= CustodyEnvelope.MAX_FILENAME_BYTES) {
            "File name too long (max ${CustodyEnvelope.MAX_FILENAME_BYTES} bytes)"
        }
        name to size
    }

    /** Header-only read of an existing container (no key, no decryption). */
    suspend fun readEnvelopeInfo(envelopeUri: Uri): CustodyEnvelope.EnvelopeInfo =
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver
                .openInputStream(envelopeUri)?.use { input ->
                    CustodyEnvelope.parseHeaderPrefix(readN(input, HEADER_PROBE_BYTES))
                } ?: error("Could not open the selected file")
        }

    /** Byte size of a SAF document, or -1 when unknown. */
    suspend fun sizeOf(uri: Uri): Long = withContext(Dispatchers.IO) {
        var size = -1L
        getApplication<Application>().contentResolver.query(
            uri, arrayOf(OpenableColumns.SIZE), null, null, null,
        )?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0)
        }
        size
    }

    /**
     * Seal + write. [attachmentSize] comes from [describe] and is
     * cross-checked while streaming; a mid-write change aborts loudly.
     * A failure part-way leaves a partial file at [destUri] — the
     * caller tells the user to delete it before retrying.
     */
    suspend fun createCustodyEnvelope(
        passphrase: String,
        attachmentUri: Uri,
        attachmentName: String,
        attachmentSize: Int,
        destUri: Uri,
    ): CreatedEnvelope {
        require(passphrase.length >= MIN_PASSPHRASE_CHARS) {
            "Passphrase is too short — use at least $MIN_PASSPHRASE_CHARS characters"
        }
        requireNotBusy()
        try {
            return withContext(Dispatchers.Default) {
                _progress.value = "Preparing your backup file…"
                val backupBytes = repository.exportBackup(passphrase)
                try {
                    val nameBytes = attachmentName.toByteArray(Charsets.UTF_8)
                    val innerLen = CustodyEnvelope.innerLengthOf(
                        nameBytes.size, backupBytes.size, attachmentSize,
                    )
                    val salt = randomBytes(BACKUP_SALT_LENGTH_BYTES)
                    val iv = randomBytes(BACKUP_GCM_IV_LENGTH_BYTES)
                    val header = CustodyEnvelope.buildHeader(
                        BACKUP_SCRYPT_N, BACKUP_SCRYPT_R, BACKUP_SCRYPT_P,
                        salt, iv, attachmentName, innerLen, attachmentSize,
                    )

                    _progress.value = DERIVING_PHASE
                    val key = deriveBackupKey(passphrase, salt, BACKUP_SCRYPT_N, BACKUP_SCRYPT_R, BACKUP_SCRYPT_P)
                    try {
                        _progress.value = WRITING_PHASE
                        val digest = MessageDigest.getInstance("SHA-256")
                        val counting = CountingOutputStream(
                            getApplication<Application>().contentResolver
                                .openOutputStream(destUri)
                                ?: error("Could not open the destination"),
                        )
                        val out = DigestOutputStream(counting, digest)
                        out.write(header)

                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(
                            Cipher.ENCRYPT_MODE,
                            SecretKeySpec(key, "AES"),
                            GCMParameterSpec(BACKUP_GCM_TAG_LENGTH_BITS, iv),
                        )
                        cipher.updateAAD(header)
                        CipherOutputStream(out, cipher).use { cos ->
                            cos.write(CustodyEnvelope.innerPreambleA(nameBytes.size))
                            cos.write(nameBytes)
                            cos.write(CustodyEnvelope.innerPreambleB(backupBytes.size))
                            cos.write(backupBytes)
                            cos.write(CustodyEnvelope.innerPreambleC(attachmentSize))
                            var written = 0
                            getApplication<Application>().contentResolver
                                .openInputStream(attachmentUri)?.use { input ->
                                    val buffer = ByteArray(STREAM_WINDOW)
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        cos.write(buffer, 0, n)
                                        written += n
                                    }
                                } ?: error("Could not read the attachment")
                            require(written == attachmentSize) {
                                "The attachment changed while sealing — delete the partial file and retry"
                            }
                        }
                        val fingerprint = digest.digest()
                            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                        CreatedEnvelope(fingerprint, counting.bytesWritten)
                    } finally {
                        Arrays.fill(key, 0.toByte())
                    }
                } finally {
                    Arrays.fill(backupBytes, 0.toByte())
                }
            }
        } finally {
            _busy.value = false
            _progress.value = null
        }
    }

    /**
     * Verify a container's passphrase and integrity. With output URIs,
     * also extracts BOTH records (backup + attachment) to the
     * user-chosen destinations; without them, NOTHING is ever written —
     * the whole container is authenticated and discarded, which is the
     * "just test the passphrase" path.
     *
     * Containers up to [IN_MEMORY_VERIFY_LIMIT] are fully authenticated
     * in memory BEFORE any output file is written, so a wrong
     * passphrase leaves no junk files behind. Larger ones stream, where
     * the tag can only fail at the final block — on that path a
     * wrong-key ATTEMPT leaves two garbage files that must be deleted
     * (the error message says so).
     */
    suspend fun verifyContainer(
        passphrase: String,
        envelopeUri: Uri,
        envelopeSize: Long,
        backupOutUri: Uri? = null,
        attachmentOutUri: Uri? = null,
    ): VerifyResult {
        require((backupOutUri == null) == (attachmentOutUri == null)) {
            "Extraction needs destinations for BOTH records"
        }
        val extracting = backupOutUri != null
        requireNotBusy()
        try {
            return withContext(Dispatchers.Default) {
                _progress.value = "Reading the container…"
                val resolver = getApplication<Application>().contentResolver
                val info: CustodyEnvelope.EnvelopeInfo =
                    resolver.openInputStream(envelopeUri)?.use { input ->
                        CustodyEnvelope.parseHeaderPrefix(readN(input, HEADER_PROBE_BYTES))
                    } ?: error("Could not open the selected file")

                _progress.value = DERIVING_PHASE
                val key = deriveBackupKey(passphrase, info.salt, info.n, info.r, info.p)
                try {
                    if (envelopeSize in 1..IN_MEMORY_VERIFY_LIMIT) {
                        _progress.value = "Checking the passphrase and verifying integrity…"
                        val fileBytes = resolver.openInputStream(envelopeUri)?.use { input ->
                            readN(input, IN_MEMORY_VERIFY_LIMIT.toInt() + 1)
                        } ?: error("Could not read the container")
                        val envelope = CustodyEnvelope.open(fileBytes)
                        val inner = decryptFull(key, envelope)
                        try {
                            val doc = CustodyEnvelope.parseInner(inner)
                            check(doc.attachmentName == info.attachmentName) { "envelope metadata mismatch" }
                            check(doc.attachment.size == envelope.attachmentSize) { "attachment size mismatch" }
                            if (extracting) {
                                _progress.value = "Writing the two extracted files…"
                                writeAll(resolver, backupOutUri!!, doc.backupFileBytes)
                                writeAll(resolver, attachmentOutUri!!, doc.attachment)
                            }
                            VerifyResult(
                                doc.attachmentName,
                                doc.attachment.size,
                                doc.backupFileBytes.size,
                                wroteFiles = extracting,
                            )
                        } finally {
                            Arrays.fill(inner, 0.toByte())
                        }
                    } else {
                        _progress.value = if (extracting) {
                            STREAMING_VERIFY_PHASE
                        } else {
                            "Checking the passphrase across the whole container — " +
                                "large files take a few minutes. Nothing is written."
                        }
                        val backupSize = verifyStreaming(
                            resolver, key, info, envelopeUri,
                            backupOutUri, attachmentOutUri,
                        )
                        VerifyResult(
                            info.attachmentName,
                            info.attachmentSize,
                            backupSize,
                            wroteFiles = extracting,
                        )
                    }
                } finally {
                    Arrays.fill(key, 0.toByte())
                }
            }
        } finally {
            _busy.value = false
            _progress.value = null
        }
    }

    private fun decryptFull(key: ByteArray, env: CustodyEnvelope.Envelope): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(BACKUP_GCM_TAG_LENGTH_BITS, env.iv),
        )
        cipher.updateAAD(env.aad)
        return cipher.doFinal(env.ciphertext)
    }

    /**
     * Streaming verify. With output streams the two records are
     * extracted; without, they are read and discarded. Either way the
     * GCM tag is verified at EOF before this returns.
     * Returns the backup record's byte size.
     */
    private fun verifyStreaming(
        resolver: ContentResolver,
        key: ByteArray,
        info: CustodyEnvelope.EnvelopeInfo,
        envelopeUri: Uri,
        backupOutUri: Uri?,
        attachmentOutUri: Uri?,
    ): Int {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(BACKUP_GCM_TAG_LENGTH_BITS, info.iv),
        )
        cipher.updateAAD(info.aad)
        resolver.openInputStream(envelopeUri)?.use { raw ->
            skipFully(raw, info.headerLength.toLong())
            CipherBlockStream(raw, cipher).use { cis ->
                val magicLine = readAsciiLine(cis)
                require(magicLine == CustodyEnvelope.INNER_MAGIC) { "Corrupted container" }
                val nameLen = readLength(cis)
                val name = readUtf8Exact(cis, nameLen)
                require(name == info.attachmentName) { "Corrupted container: filename mismatch" }
                val backupLen = readLength(cis)
                drainOrCopy(cis, backupOutUri?.let {
                    resolver.openOutputStream(it) ?: error("Could not open the backup destination")
                }, backupLen)
                val attachLen = readLength(cis)
                require(attachLen == info.attachmentSize) { "Corrupted container: size mismatch" }
                drainOrCopy(cis, attachmentOutUri?.let {
                    resolver.openOutputStream(it) ?: error("Could not open the attachment destination")
                }, attachLen)
                // Cipher.doFinal runs at EOF: it is where the GCM tag is
                // verified, so one more read is mandatory before success.
                val extra = cis.read()
                require(extra == -1) { "Trailing data — the transfer is not faithful" }
                return backupLen
            }
        } ?: error("Could not open the container")
    }

    private fun requireNotBusy() {
        check(!_busy.value) { "Already working — wait for the current operation to finish" }
        _busy.value = true
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var bytesWritten: Long = 0
            private set

        override fun write(b: Int) {
            out.write(b)
            bytesWritten++
        }

        // FilterOutputStream's default loops per byte — bypass it.
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            bytesWritten += len.toLong()
        }

        override fun write(b: ByteArray) = write(b, 0, b.size)
    }

    companion object {
        const val MIN_PASSPHRASE_CHARS = 16

        private const val HEADER_PROBE_BYTES = 2048
        private const val STREAM_WINDOW = 64 * 1024

        /** Full-auth-before-write ceiling for verify. */
        const val IN_MEMORY_VERIFY_LIMIT = 32L * 1024 * 1024

        const val DERIVING_PHASE =
            "Deriving the key from your passphrase — normally 1 to 2 minutes. Keep this screen open."
        const val WRITING_PHASE =
            "Encrypting and writing — large files add a few minutes. Keep this screen open."
        const val STREAMING_VERIFY_PHASE =
            "Decrypting and writing — large files take a few minutes. Keep this screen open."

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        private fun randomBytes(length: Int): ByteArray =
            ByteArray(length).also { SecureRandom().nextBytes(it) }

        /**
         * InputStream.readNBytes is API 33; minSdk is 29. This is the
         * same contract: fill up to n bytes, stopping early only at EOF.
         */
        private fun readN(input: InputStream, n: Int): ByteArray {
            val out = ByteArray(n)
            var total = 0
            while (total < n) {
                val r = input.read(out, total, n - total)
                if (r < 0) break
                total += r
            }
            return if (total == n) out else out.copyOf(total)
        }

        private fun skipFully(input: InputStream, n: Long) {
            var remaining = n
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                require(skipped > 0) { "Unexpected end of file" }
                remaining -= skipped
            }
        }

        private fun readAsciiLine(input: InputStream): String {
            val bytes = java.io.ByteArrayOutputStream(32)
            while (true) {
                val b = input.read()
                require(b >= 0 && bytes.size() < 256) { "Corrupted container" }
                if (b == '\n'.code) break
                bytes.write(b)
            }
            return bytes.toString("US-ASCII")
        }

        private fun readLength(input: InputStream): Int {
            var value = 0
            var digits = 0
            while (true) {
                val b = input.read()
                require(b >= 0 && digits < CustodyEnvelope.MAX_LENGTH_PREFIX_DIGITS) { "Corrupted container" }
                if (b == ':'.code) {
                    require(digits > 0 && value > 0) { "Corrupted container" }
                    return value
                }
                require(b in '0'.code..'9'.code) { "Corrupted container" }
                value = value * 10 + (b - '0'.code)
                digits++
            }
        }

        private fun readUtf8Exact(input: InputStream, n: Int): String {
            val bytes = readN(input, n)
            require(bytes.size == n) { "Corrupted container: truncated filename record" }
            return bytes.toString(java.nio.charset.StandardCharsets.UTF_8)
        }

        /** Copy exactly [n] decrypted bytes to [out], or drain them if null. */
        private fun drainOrCopy(cis: InputStream, out: OutputStream?, n: Int) {
            try {
                var remaining = n
                val buffer = ByteArray(STREAM_WINDOW)
                while (remaining > 0) {
                    val read = cis.read(buffer, 0, minOf(remaining, buffer.size))
                    require(read > 0) { "Truncated container" }
                    out?.write(buffer, 0, read)
                    remaining -= read
                }
            } finally {
                runCatching { out?.close() }
            }
        }

        private fun writeAll(resolver: ContentResolver, uri: Uri, bytes: ByteArray) {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("Could not open the destination")
        }

        /**
         * Feeds ciphertext through [Cipher] chunk by chunk. Deliberately
         * NOT CipherInputStream: on Android that class swallows the
         * AEADBadTagException, silently truncating reads instead of
         * failing. Here the final doFinal at EOF either verifies the GCM
         * tag or throws — deterministically, before the caller can
         * report success.
         */
        private class CipherBlockStream(
            private val input: InputStream,
            private val cipher: Cipher,
        ) : InputStream() {

            private var pending = ByteArray(0)
            private var pos = 0
            private var eof = false

            private fun pull() {
                val data = readN(input, STREAM_WINDOW)
                if (data.isEmpty()) {
                    eof = true
                    // GCM tag verification happens right here.
                    pending = cipher.doFinal()
                    pos = 0
                } else {
                    pending = cipher.update(data) ?: EMPTY
                    pos = 0
                }
            }

            override fun read(): Int {
                while (pos >= pending.size) {
                    if (eof) return -1
                    pull()
                }
                return pending[pos++].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (pos >= pending.size) {
                    if (eof) return -1
                    pull()
                }
                val n = minOf(len, pending.size - pos)
                pending.copyInto(b, off, pos, pos + n)
                pos += n
                return n
            }

            override fun close() = input.close()

            companion object {
                private val EMPTY = ByteArray(0)
            }
        }
    }
}
