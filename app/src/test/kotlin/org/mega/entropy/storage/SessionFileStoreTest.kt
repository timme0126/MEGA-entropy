package org.mega.entropy.storage

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM unit tests proving listAllSessionIds() finds every session by
 * filename — including ones listAllMetadata() would silently drop — since
 * that's exactly the property the duress-wipe / Secure Delete All path
 * depends on. No Keystore/crypto involved, so these stay JVM-only.
 */
class SessionFileStoreTest {

    private fun newStore(): Pair<SessionFileStore, File> {
        val dir = createTempDirectory().toFile()
        return SessionFileStore(dir) to dir
    }

    @Test
    fun `finds a normal session with both meta and enc`() {
        val (store, dir) = newStore()
        val id = "normal-session-id"
        File(dir, "mega_sessions").mkdirs()
        File(dir, "mega_sessions/$id.meta").writeBytes("valid metadata".toByteArray())
        File(dir, "mega_sessions/$id.enc").writeBytes("encrypted data".toByteArray())

        val ids = store.listAllSessionIds()
        assertEquals(1, ids.size)
        assertTrue(ids.contains(id))
    }

    @Test
    fun `finds a session whose meta file is corrupt and unparseable`() {
        val (store, dir) = newStore()
        val id = "corrupt-meta-session"
        File(dir, "mega_sessions").mkdirs()
        File(dir, "mega_sessions/$id.meta").writeBytes("not valid metadata".toByteArray())

        // listAllMetadata() would silently drop this one (runCatching { }.getOrNull());
        // listAllSessionIds() must not, since it's what the wipe path relies on.
        val ids = store.listAllSessionIds()
        assertEquals(1, ids.size)
        assertTrue(ids.contains(id))
    }

    @Test
    fun `finds an orphaned enc file with no meta file at all`() {
        val (store, dir) = newStore()
        val id = "orphaned-enc-session"
        File(dir, "mega_sessions").mkdirs()
        File(dir, "mega_sessions/$id.enc").writeBytes("orphaned ciphertext".toByteArray())

        val ids = store.listAllSessionIds()
        assertEquals(1, ids.size)
        assertTrue(ids.contains(id))
    }

    @Test
    fun `returns each session ID only once even with both files present`() {
        val (store, dir) = newStore()
        val id = "duplicate-check-session"
        File(dir, "mega_sessions").mkdirs()
        File(dir, "mega_sessions/$id.meta").writeBytes("meta".toByteArray())
        File(dir, "mega_sessions/$id.enc").writeBytes("enc".toByteArray())

        val ids = store.listAllSessionIds()
        assertEquals(1, ids.size)
        assertTrue(ids.contains(id))
    }

    @Test
    fun `returns an empty set for a directory with no session files`() {
        val (store, dir) = newStore()
        File(dir, "mega_sessions").mkdirs()

        assertEquals(emptySet<String>(), store.listAllSessionIds())
    }
}
