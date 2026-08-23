package org.mega.entropy.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionMetadataTagsTest {
    private fun metadata(tags: List<String>) = SavedSessionMetadata(
        id = "session-tags",
        createdAtEpochMillis = 1L,
        rollsCount = 0,
        hasMnemonic = true,
        keystoreAlias = "alias-1",
        label = "Cold storage",
        tags = tags,
    )

    @Test
    fun `tags round trip exactly through metadata V5 encoding`() {
        val original = metadata(listOf("cold storage", "inheritance"))
        val decoded = decodeMetadata(encodeMetadata(original))
        assertEquals(original.tags, decoded.tags)
        assertEquals(original, decoded)
    }

    @Test
    fun `no tags encodes and decodes back to an empty list`() {
        val original = metadata(emptyList())
        val decoded = decodeMetadata(encodeMetadata(original))
        assertEquals(emptyList<String>(), decoded.tags)
    }

    @Test
    fun `a V4 metadata file (no tags line) still decodes, with empty tags`() {
        val v4Lines = listOf(
            "MEGA-META-V4",
            "id:old-session",
            "createdAt:1700000000000",
            "rollsCount:0",
            "hasMnemonic:true",
            "alias:alias-1",
            "label:Old Label",
            "hasPassphraseCheck:false",
            "childSeedInfo:",
        )
        val decoded = decodeMetadata(v4Lines.joinToString("\n").toByteArray())

        assertEquals(emptyList<String>(), decoded.tags)
        assertEquals("Old Label", decoded.label)
        assertEquals("old-session", decoded.id)
    }

    @Test
    fun `a V3 metadata file (no childSeedInfo, no tags) still decodes, with empty tags`() {
        val v3Lines = listOf(
            "MEGA-META-V3",
            "id:very-old-session",
            "createdAt:1600000000000",
            "rollsCount:24",
            "hasMnemonic:false",
            "alias:alias-2",
            "label:",
            "hasPassphraseCheck:false",
        )
        val decoded = decodeMetadata(v3Lines.joinToString("\n").toByteArray())

        assertEquals(emptyList<String>(), decoded.tags)
        assertEquals("", decoded.childSeedInfo)
        assertEquals("very-old-session", decoded.id)
    }
}
