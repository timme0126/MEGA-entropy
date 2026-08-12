package org.mega.entropy.storage

import java.io.File

/** Takes the base directory directly (the caller passes context.filesDir)
 * rather than a Context, so this class can be unit-tested with plain JVM
 * File I/O — a temp directory — with no Android framework dependency. */
class SessionFileStore(private val baseDir: File) {
    /**
     * Resolves the app-private internal storage directory for MEGA sessions.
     *
     * WHY: We strictly use context.filesDir to ensure data never leaves the
     * app's sandboxed storage, avoiding external storage permissions and
     * protecting against unauthorized access by other apps.
     */
    private fun sessionsDir(): File {
        val dir = File(baseDir, "mega_sessions")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun writeMetaFile(metadata: SavedSessionMetadata) {
        val file = File(sessionsDir(), "${metadata.id}.meta")
        file.writeBytes(encodeMetadata(metadata))
    }

    fun writeEncFile(sessionId: String, ivAndCiphertext: ByteArray) {
        val file = File(sessionsDir(), "$sessionId.enc")
        file.writeBytes(ivAndCiphertext)
    }

    fun readMetaFile(sessionId: String): SavedSessionMetadata? {
        val file = File(sessionsDir(), "$sessionId.meta")
        return file.takeIf { it.exists() }?.let { decodeMetadataFile(it) }
    }

    fun readEncFile(sessionId: String): ByteArray? {
        val file = File(sessionsDir(), "$sessionId.enc")
        return file.takeIf { it.exists() }?.readBytes()
    }

    /**
     * Scans for all session metadata files and parses them.
     *
     * WHY: We use runCatching around each individual parse so that one
     * corrupt session's metadata doesn't break listing every other session.
     * We explicitly skip failures rather than throwing, maintaining availability.
     */
    fun listAllMetadata(): List<SavedSessionMetadata> {
        return sessionsDir()
            .listFiles { _, name -> name.endsWith(".meta") }
            ?.mapNotNull { file ->
                runCatching { decodeMetadataFile(file) }.getOrNull()
            }
            ?: emptyList()
    }

    private fun decodeMetadataFile(file: File): SavedSessionMetadata {
        val metadata = decodeMetadata(file.readBytes())
        val modifiedAt = file.lastModified()
        return if (metadata.createdAtEpochMillis < FIRST_PUBLIC_BETA_EPOCH_MILLIS && modifiedAt >= FIRST_PUBLIC_BETA_EPOCH_MILLIS) {
            metadata.copy(createdAtEpochMillis = modifiedAt)
        } else {
            metadata
        }
    }

    fun deleteSessionFiles(sessionId: String) {
        File(sessionsDir(), "$sessionId.meta").delete()
        File(sessionsDir(), "$sessionId.enc").delete()
    }

    /**
     * Enumerates every session ID present in storage BY FILENAME ALONE — a
     * .meta file that fails to parse, or an orphaned .enc file with no
     * .meta at all (e.g. from an interrupted save), still counts.
     *
     * WHY: Unlike listAllMetadata() above, which deliberately drops a
     * corrupt/unparseable session so one bad file can't break the whole
     * Saved Sessions list, this is used by the "delete everything" path
     * (duress-PIN wipe, Secure Delete All) — silently skipping a session
     * there because its metadata happens to be corrupt would leave that
     * session's ciphertext and Keystore key behind after the user asked to
     * wipe everything, which defeats the entire point of a wipe.
     */
    fun listAllSessionIds(): Set<String> {
        val dir = sessionsDir()
        val fromMeta = dir.listFiles { _, name -> name.endsWith(".meta") }
            ?.map { it.name.removeSuffix(".meta") }.orEmpty()
        val fromEnc = dir.listFiles { _, name -> name.endsWith(".enc") }
            ?.map { it.name.removeSuffix(".enc") }.orEmpty()
        return (fromMeta + fromEnc).toSet()
    }

    companion object {
        private const val FIRST_PUBLIC_BETA_EPOCH_MILLIS = 1_786_147_200_000L
    }
}
