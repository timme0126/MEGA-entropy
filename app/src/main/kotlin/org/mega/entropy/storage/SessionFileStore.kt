package org.mega.entropy.storage

import android.content.Context
import java.io.File

class SessionFileStore(private val context: Context) {
    /**
     * Resolves the app-private internal storage directory for MEGA sessions.
     *
     * WHY: We strictly use context.filesDir to ensure data never leaves the
     * app's sandboxed storage, avoiding external storage permissions and
     * protecting against unauthorized access by other apps.
     */
    private fun sessionsDir(): File {
        val dir = File(context.filesDir, "mega_sessions")
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
        return file.takeIf { it.exists() }?.readBytes()?.let { decodeMetadata(it) }
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
                runCatching { decodeMetadata(file.readBytes()) }.getOrNull()
            }
            ?: emptyList()
    }

    fun deleteSessionFiles(sessionId: String) {
        File(sessionsDir(), "$sessionId.meta").delete()
        File(sessionsDir(), "$sessionId.enc").delete()
    }
}
