package org.mega.entropy.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * bestEffortDeleteAll takes no Android Context and touches no Keystore —
 * tested directly here with a fake deleteOne, proving the control-flow
 * property the real deleteAllSessions() depends on: one failing session
 * (corrupt metadata, or anything else) can't stop the rest from being
 * visited.
 */
class BestEffortDeleteAllTest {

    @Test
    fun `one ID throws, the others are still visited`() = runBlocking {
        val visited = mutableListOf<String>()
        val ids = setOf("id1", "id2", "id3")

        bestEffortDeleteAll(ids) { sessionId ->
            if (sessionId == "id2") throw IllegalStateException("corrupt metadata")
            visited.add(sessionId)
        }

        assertEquals(2, visited.size)
        assertTrue(visited.contains("id1"))
        assertTrue(visited.contains("id3"))
    }

    @Test
    fun `no failures, every ID is visited exactly once`() = runBlocking {
        val visited = mutableListOf<String>()
        val ids = setOf("a", "b", "c")

        bestEffortDeleteAll(ids) { visited.add(it) }

        assertEquals(3, visited.size)
        assertTrue(visited.containsAll(listOf("a", "b", "c")))
    }

    @Test
    fun `empty set completes without error and visits nothing`() = runBlocking {
        val visited = mutableListOf<String>()

        bestEffortDeleteAll(emptySet()) { visited.add(it) }

        assertEquals(0, visited.size)
    }
}
