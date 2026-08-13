package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BbqrTest {

    // Reference vectors generated with Python's zlib/base64 (the reference
    // implementations BBQr-producing tools like Sparrow/Coldcard actually
    // build on) — see the BBQr spec at
    // https://github.com/coinkite/BBQr/blob/master/BBQr.md for the format
    // itself. Cross-checking against an independent implementation, not
    // just round-tripping this file's own encode/decode, is the point.

    private val descriptorText = "wsh(sortedmulti(2,[00000000/48'/0'/0'/2']xpub6ERApfZwUNrhLCkDtcHTcxd75RbzS1ed54G1LkBUHQVHQKqhMkhgbmJbZRkrgZw4koxb5JaHWkY4ALHY2grBGRjaDMzQLcgJvLJuZZvRcEL/<0;1>/*,[06afd46b/48'/0'/0'/2']xpub68NZiKmJWnxxS6aaHmn81bvJeTESw724CRDs6HbuccFQN9Ku14VQrADWgqbhhTHBaohPX4CjNLf9fq9MYo6oDaPPLPxSb7gwQN3ih19Zm4Y/<0;1>/*))#tjg09x5t"

    private val zlibPayload = "NXHNWTUDGAAIBYMH6FRJWMPBMBQSGGQTGZYA32YIU4G2T4NC4XIDEZDMDSNT3POG5COX77QH7CTI5T53U3W7HLA6H35XFLR6XTF36SLAGWJ6JH6V3GD3QDCUW4B7GUXA5HQLMHDNFKVU6YMURJWKSBPUCYVHTJUBVWBKV5IB7JD6R35OPRPXCRTLQ7RKA2QZTZANKCFKHECMMVICJQCBHFNV5NWXAIWW73TKHFBZENZANDGHEC2ZD5BMH4US7UX5G5DSORIGOT5A6Z7F4JZFOO7RLGEFAJYE23TZKQSHE6H6Y4C2VJQBGWE5B3UZBJVPXZV6YBQFDT65NNDCO2SZYR3QJUNO5PMBZXEULBKRLSGX3UXIRVCTYD3ZEKSEWNXZ5ZR4SFID24QPTYZMCZ373COJQ3IPULY"

    @Test
    fun `parseBbqrPart parses a well-formed single-part header`() {
        val part = parseBbqrPart("B\$2U0100NBSWY3DP")
        assertEquals('2', part?.encoding)
        assertEquals('U', part?.fileType)
        assertEquals(1, part?.total)
        assertEquals(0, part?.index)
        assertEquals("NBSWY3DP", part?.payload)
    }

    @Test
    fun `parseBbqrPart returns null for text without the B$ prefix`() {
        assertNull(parseBbqrPart("wsh(sortedmulti(2,...))"))
        assertNull(parseBbqrPart("[00000000/48'/0'/0'/2']xpub6E"))
    }

    @Test
    fun `parseBbqrPart returns null for a malformed total-or-index field`() {
        assertNull(parseBbqrPart("B\$2U__00payload"))
        assertNull(parseBbqrPart("B\$2U01ZZpayload")) // index ZZ (1295) out of range for total 01
    }

    @Test
    fun `parseBbqrPart returns null for text shorter than the 8-char header`() {
        assertNull(parseBbqrPart("B\$2U01"))
    }

    @Test
    fun `assembleBbqrParts decodes a Base32 (encoding 2) single part - hello world vector`() {
        // "hello" base32-encodes to NBSWY3DP (RFC4648, no padding) — a
        // textbook, hand-verifiable vector independent of this file's own
        // encoder (there isn't one).
        val part = parseBbqrPart("B\$2U0100NBSWY3DP")!!
        assertEquals("hello", assembleBbqrParts(listOf(part)))
    }

    @Test
    fun `assembleBbqrParts decodes a Zlib+Base32 (encoding Z) single part matching Python's reference zlib`() {
        val part = parseBbqrPart("B\$ZU0100$zlibPayload")!!
        assertEquals(descriptorText, assembleBbqrParts(listOf(part)))
    }

    @Test
    fun `assembleBbqrParts reassembles two Zlib+Base32 parts scanned out of order`() {
        val splitAt = 208 // matches the Python-side split point used to generate this fixture
        val part0 = parseBbqrPart("B\$ZU0200${zlibPayload.substring(0, splitAt)}")!!
        val part1 = parseBbqrPart("B\$ZU0201${zlibPayload.substring(splitAt)}")!!

        // Scanned in reverse order — BBQr parts must decode correctly
        // regardless of scan order per spec.
        assertEquals(descriptorText, assembleBbqrParts(listOf(part1, part0)))
    }

    @Test
    fun `assembleBbqrParts throws when a part is missing`() {
        val splitAt = 216
        val part0 = parseBbqrPart("B\$ZU0200${zlibPayload.substring(0, splitAt)}")!!

        val exception = assertThrows(IllegalArgumentException::class.java) {
            assembleBbqrParts(listOf(part0))
        }
        assertTrue(exception.message.orEmpty().contains("Missing"))
    }

    @Test
    fun `assembleBbqrParts throws on inconsistent total across parts`() {
        val partA = parseBbqrPart("B\$2U0200NBSWY3DP")!!
        val partB = parseBbqrPart("B\$2U0301XXXXXXXX")!!

        val exception = assertThrows(IllegalArgumentException::class.java) {
            assembleBbqrParts(listOf(partA, partB))
        }
        assertTrue(exception.message.orEmpty().contains("total"))
    }

    @Test
    fun `assembleBbqrParts rejects an unsupported file type like PSBT`() {
        val part = parseBbqrPart("B\$2P0100NBSWY3DP")!!
        val exception = assertThrows(IllegalArgumentException::class.java) {
            assembleBbqrParts(listOf(part))
        }
        assertTrue(exception.message.orEmpty().contains("Unsupported BBQr file type"))
    }

    @Test
    fun `assembleBbqrParts extracts a descriptor field from JSON file type`() {
        val json = """{"label":"My Vault","descriptor":"wsh(sortedmulti(2,A\/B))"}"""
        val payload = java.util.Base64.getEncoder().let {
            // Reuse this file's own Base32 alphabet indirectly by round-tripping
            // through parseBbqrPart/assembleBbqrParts with encoding '2' — build
            // the Base32 text by hand via the same RFC4648 alphabet Python used
            // above, to keep this test independent of any encoder in this file.
            base32EncodeForTest(json.toByteArray(Charsets.UTF_8))
        }
        val part = parseBbqrPart("B\$2J0100$payload")!!
        assertEquals("wsh(sortedmulti(2,A/B))", assembleBbqrParts(listOf(part)))
    }

    // A minimal, independent Base32 encoder used ONLY to build this test's
    // JSON fixture payload — assembleBbqrParts' own Base32 DEcoder is what's
    // actually under test elsewhere in this file via the Python-generated
    // vectors, so this doesn't undermine that coverage.
    private fun base32EncodeForTest(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder()
        var bits = 0
        var bitCount = 0
        for (b in data) {
            bits = (bits shl 8) or (b.toInt() and 0xFF)
            bitCount += 8
            while (bitCount >= 5) {
                bitCount -= 5
                sb.append(alphabet[(bits shr bitCount) and 0x1F])
            }
        }
        if (bitCount > 0) {
            sb.append(alphabet[(bits shl (5 - bitCount)) and 0x1F])
        }
        return sb.toString()
    }
}
