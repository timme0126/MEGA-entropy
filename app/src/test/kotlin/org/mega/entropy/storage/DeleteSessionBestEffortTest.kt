package org.mega.entropy.storage

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * deleteSessionBestEffort touches real files via a real SessionFileStore
 * (a temp directory — no Android Context needed) and only fakes the one
 * genuinely untestable piece, the Keystore key-deletion call — so these
 * tests prove the actual on-disk cleanup behavior, not just a simulation
 * of it.
 */
class DeleteSessionBestEffortTest {

    private fun newStore(): Pair<SessionFileStore, File> {
        val dir = createTempDirectory().toFile()
        return SessionFileStore(dir) to dir
    }

    private fun sessionFiles(dir: File, id: String): Pair<File, File> =
        File(dir, "mega_sessions/$id.meta") to File(dir, "mega_sessions/$id.enc")

    @Test
    fun `corrupt meta and its enc file are both removed`() = runBlocking {
        val (store, dir) = newStore()
        val id = "corrupt-session"
        File(dir, "mega_sessions").mkdirs()
        val (metaFile, encFile) = sessionFiles(dir, id)
        metaFile.writeBytes("not valid metadata".toByteArray())
        encFile.writeBytes("ciphertext".toByteArray())
        assertTrue(metaFile.exists())
        assertTrue(encFile.exists())

        store.deleteSessionBestEffort(id) { /* fake key deletion, succeeds */ }

        assertFalse("corrupt .meta must be deleted", metaFile.exists())
        assertFalse(".enc must be deleted even though its .meta was corrupt", encFile.exists())
    }

    @Test
    fun `files are still deleted even when Keystore key deletion fails`() = runBlocking {
        val (store, dir) = newStore()
        val id = "keystore-failure-session"
        File(dir, "mega_sessions").mkdirs()
        val (metaFile, encFile) = sessionFiles(dir, id)
        metaFile.writeBytes("also not valid metadata".toByteArray())
        encFile.writeBytes("ciphertext".toByteArray())

        store.deleteSessionBestEffort(id) {
            throw IllegalStateException("simulated Keystore failure")
        }

        assertFalse("files must be removed even though key deletion threw", metaFile.exists())
        assertFalse(encFile.exists())
    }

    @Test
    fun `orphaned enc file with no meta at all is still removed`() = runBlocking {
        val (store, dir) = newStore()
        val id = "orphaned-session"
        File(dir, "mega_sessions").mkdirs()
        val (metaFile, encFile) = sessionFiles(dir, id)
        encFile.writeBytes("orphaned ciphertext".toByteArray())
        assertFalse(metaFile.exists())
        assertTrue(encFile.exists())

        store.deleteSessionBestEffort(id) { }

        assertFalse(encFile.exists())
    }

    @Test
    fun `valid metadata resolves its real alias for key deletion, then removes files`() = runBlocking {
        val (store, dir) = newStore()
        val id = "valid-session"
        val metadata = SavedSessionMetadata(
            id = id,
            createdAtEpochMillis = 1_800_000_000_000L,
            rollsCount = 0,
            hasMnemonic = false,
            keystoreAlias = "mega_session_$id",
            label = "test",
        )
        store.writeMetaFile(metadata)
        val (metaFile, encFile) = sessionFiles(dir, id)
        encFile.writeBytes("ciphertext".toByteArray())

        var aliasPassed: String? = null
        store.deleteSessionBestEffort(id) { alias -> aliasPassed = alias }

        assertEquals(metadata.keystoreAlias, aliasPassed)
        assertFalse(metaFile.exists())
        assertFalse(encFile.exists())
    }
}
