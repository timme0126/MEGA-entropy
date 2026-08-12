package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptorChecksumTest {

    // Reference vectors taken from Bitcoin Core's own test suite
    // (src/test/descriptor_tests.cpp) and BIP-380's mediawiki text, so this
    // verifies MEGA's port is byte-identical to Bitcoin Core's, not just
    // internally self-consistent.

    @Test
    fun `matches Bitcoin Core's raw(deadbeef) reference vector`() {
        assertEquals("89f8spxm", descriptorChecksum("raw(deadbeef)"))
    }

    @Test
    fun `matches Bitcoin Core's sh-multi reference vector`() {
        val body = "sh(multi(2,[00000000/111'/222]xpub6ERApfZwUNrhLCkDtcHTcxd75RbzS1ed54G1LkBUHQVHQKqhMkhgbmJbZRkrgZw4koxb5JaHWkY4ALHY2grBGRjaDMzQLcgJvLJuZZvRcEL,xpub68NZiKmJWnxxS6aaHmn81bvJeTESw724CRDs6HbuccFQN9Ku14VQrADWgqbhhTHBaohPX4CjNLf9fq9MYo6oDaPPLPxSb7gwQN3ih19Zm4Y/0))"
        assertEquals("tjg09x5t", descriptorChecksum(body))
    }

    @Test
    fun `matches Bitcoin Core's h-hardened-marker reference vector`() {
        val body = "sh(multi(2,[00000000/111h/222]xpub6ERApfZwUNrhLCkDtcHTcxd75RbzS1ed54G1LkBUHQVHQKqhMkhgbmJbZRkrgZw4koxb5JaHWkY4ALHY2grBGRjaDMzQLcgJvLJuZZvRcEL,xpub68NZiKmJWnxxS6aaHmn81bvJeTESw724CRDs6HbuccFQN9Ku14VQrADWgqbhhTHBaohPX4CjNLf9fq9MYo6oDaPPLPxSb7gwQN3ih19Zm4Y/0))"
        assertEquals("hgmsckna", descriptorChecksum(body))
    }

    @Test
    fun `a single-character change anywhere always changes the checksum`() {
        val original = "raw(deadbeef)"
        val tampered = "raw(deadbee0)"
        assertNotEquals(descriptorChecksum(original), descriptorChecksum(tampered))
    }

    @Test
    fun `appendDescriptorChecksum produces a checksum verifyAndStripDescriptorChecksum accepts`() {
        val body = "wsh(sortedmulti(2,[00000000/48'/0'/0'/2']xpub6E/<0;1>/*))"
        val withChecksum = appendDescriptorChecksum(body)
        assertEquals(body, verifyAndStripDescriptorChecksum(withChecksum))
    }

    @Test
    fun `verifyAndStripDescriptorChecksum returns text unchanged when no checksum is present`() {
        val body = "raw(deadbeef)"
        assertEquals(body, verifyAndStripDescriptorChecksum(body))
    }

    @Test
    fun `verifyAndStripDescriptorChecksum rejects a mismatched checksum`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            verifyAndStripDescriptorChecksum("raw(deadbeef)#89f8spxx")
        }
        assertTrue(exception.message.orEmpty().contains("does not match"))
    }

    @Test
    fun `verifyAndStripDescriptorChecksum rejects a too-short checksum`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            verifyAndStripDescriptorChecksum("raw(deadbeef)#89f8spx")
        }
        assertTrue(exception.message.orEmpty().contains("8 characters"))
    }

    @Test
    fun `verifyAndStripDescriptorChecksum rejects a too-long checksum`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            verifyAndStripDescriptorChecksum("raw(deadbeef)#89f8spxmx")
        }
        assertTrue(exception.message.orEmpty().contains("8 characters"))
    }

    @Test
    fun `verifyAndStripDescriptorChecksum rejects a missing checksum after the hash`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            verifyAndStripDescriptorChecksum("raw(deadbeef)#")
        }
        assertTrue(exception.message.orEmpty().contains("8 characters"))
    }

    @Test
    fun `verifyAndStripDescriptorChecksum rejects more than one hash`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            verifyAndStripDescriptorChecksum("raw(deadbeef)##89f8spxm")
        }
        assertTrue(exception.message.orEmpty().contains("more than one"))
    }

    @Test
    fun `descriptorChecksum rejects a character outside the descriptor charset`() {
        assertThrows(IllegalArgumentException::class.java) { descriptorChecksum("raw(deadbeef)é") }
    }
}
