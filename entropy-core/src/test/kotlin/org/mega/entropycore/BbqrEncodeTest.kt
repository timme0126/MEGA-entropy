package org.mega.entropycore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers encodeBbqr and assembleBbqrPartsAsBytes — the outbound (export)
 * and binary-inbound (PSBT import) halves of BBQr support this codebase's
 * existing Bbqr.kt only had the text/JSON inbound half of before. The
 * Base32 reference payload below was cross-checked against Python's
 * base64.b32encode (RFC4648 Base32, the same alphabet BBQr's '2' encoding
 * uses), with its '=' padding stripped to match this codebase's
 * no-padding convention.
 */
class BbqrEncodeTest {

    companion object {
        private const val TEST_DATA_HEX = "68656c6c6f20776f726c64207073627420627974657320746573742031323334353637383930"
        private const val EXPECTED_BASE32_PAYLOAD = "NBSWY3DPEB3W64TMMQQHA43COQQGE6LUMVZSA5DFON2CAMJSGM2DKNRXHA4TA"

        private fun String.hexToBytes(): ByteArray {
            require(length % 2 == 0)
            return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }

    @Test
    fun `encodeBbqr produces a single well-formed part when the payload fits in one`() {
        val data = TEST_DATA_HEX.hexToBytes()
        val parts = encodeBbqr('P', data, partPayloadSize = 152)

        assertEquals(1, parts.size)
        assertEquals("B\$2P0100$EXPECTED_BASE32_PAYLOAD", parts[0])
    }

    @Test
    fun `encodeBbqr splits into the correct number of parts with correct total-index headers`() {
        val data = TEST_DATA_HEX.hexToBytes()
        val parts = encodeBbqr('P', data, partPayloadSize = 8)

        // 61 Base32 chars / 8 per part = 8 parts (ceiling division); partPayloadSize
        // must be a multiple of 8 so every part but the last independently decodes
        // to a whole number of bytes, per the BBQr spec's Base32 requirement.
        assertEquals(8, parts.size)
        parts.forEachIndexed { i, part ->
            val parsed = parseBbqrPart(part)
            assertEquals('2', parsed?.encoding)
            assertEquals('P', parsed?.fileType)
            assertEquals(8, parsed?.total)
            assertEquals(i, parsed?.index)
        }
        // Reassembling every part's payload in order reproduces the full Base32 string.
        assertEquals(EXPECTED_BASE32_PAYLOAD, parts.joinToString("") { parseBbqrPart(it)!!.payload })
    }

    @Test
    fun `encodeBbqr followed by assembleBbqrPartsAsBytes round-trips arbitrary bytes`() {
        val original = TEST_DATA_HEX.hexToBytes()
        val parts = encodeBbqr('P', original, partPayloadSize = 16).map { parseBbqrPart(it)!! }

        assertArrayEquals(original, assembleBbqrPartsAsBytes(parts))
    }

    @Test
    fun `encodeBbqr followed by assembleBbqrPartsAsBytes round-trips a single-byte payload`() {
        val original = byteArrayOf(0x2a)
        val parts = encodeBbqr('T', original).map { parseBbqrPart(it)!! }

        assertArrayEquals(original, assembleBbqrPartsAsBytes(parts))
    }

    @Test
    fun `assembleBbqrPartsAsBytes rejects a text file type`() {
        val original = TEST_DATA_HEX.hexToBytes()
        val parts = encodeBbqr('U', original).map { parseBbqrPart(it)!! }

        assertThrows(IllegalArgumentException::class.java) { assembleBbqrPartsAsBytes(parts) }
    }

    @Test
    fun `assembleBbqrParts rejects a PSBT file type`() {
        val original = TEST_DATA_HEX.hexToBytes()
        val parts = encodeBbqr('P', original).map { parseBbqrPart(it)!! }

        assertThrows(IllegalArgumentException::class.java) { assembleBbqrParts(parts) }
    }

    @Test
    fun `encodeBbqr rejects a non-positive partPayloadSize`() {
        assertThrows(IllegalArgumentException::class.java) { encodeBbqr('P', byteArrayOf(0x01), partPayloadSize = 0) }
    }

    @Test
    fun `encodeBbqr rejects data that would need more parts than BBQr's header can address`() {
        // 10000 bytes -> 16000 Base32 chars; at 8 chars/part (the smallest valid,
        // multiple-of-8 size) that's 2000 parts, over the 1296 max.
        val tooBigForSmallestValidPartSize = ByteArray(10000)
        assertThrows(IllegalArgumentException::class.java) {
            encodeBbqr('P', tooBigForSmallestValidPartSize, partPayloadSize = 8)
        }
    }

    @Test
    fun `encodeBbqr rejects a partPayloadSize that is not a multiple of 8`() {
        // The BBQr spec requires every Base32 block to independently decode to a
        // whole number of bytes ("for Base32 you must send complete bytes") — the
        // reported real-world failure (Sparrow: "Invalid input length 150") came
        // from exactly this: a 150-character part size, and 150 % 8 == 6.
        assertThrows(IllegalArgumentException::class.java) {
            encodeBbqr('P', TEST_DATA_HEX.hexToBytes(), partPayloadSize = 150)
        }
    }
}
