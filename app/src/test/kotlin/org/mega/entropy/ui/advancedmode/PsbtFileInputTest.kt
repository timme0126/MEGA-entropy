package org.mega.entropy.ui.advancedmode

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers preparePsbtFileBytes — the content-sniffing file-import parser:
 * raw PSBT bytes and base64-encoded PSBTs (single-line or \r\n-wrapped,
 * with surrounding whitespace) pass; anything else is refused with a
 * friendly IllegalArgumentException message, never a raw framework
 * exception. Plain JUnit4: the helper deliberately uses
 * java.util.Base64, not android.util.Base64, so no Robolectric is needed.
 */
class PsbtFileInputTest {

    companion object {
        // The official BIP174 P2SH-P2WSH vector (same one the entropy-core
        // Psbt tests use), as raw PSBT bytes.
        private const val FULL_HEX =
            "70736274ff0100550200000001279a2323a5dfb51fc45f220fa58b0fc13e1e3342792a85d7e36cd6333b5cbc39" +
            "0000000000ffffffff01a05aea0b000000001976a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac" +
            "0000000000010120955eea0b0000000017a9146345200f68d189e1adc0df1c4d16ea8f14c0dbeb87220203b1" +
            "341ccba7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd4646304302200424b58effaaa694" +
            "e1559ea5c93bbfd4a89064224055cdf070b6771469442d07021f5c8eb0fea6516d60b8acb33ad64ede60e878" +
            "5bfb3aa94b99bdf86151db9a9a010104220020771fd18ad459666dd49f3d564e3dbc42f4c84774e360ada168" +
            "16a8ed488d5681010547522103b1341ccba7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd" +
            "462103de55d1e1dac805e3f8a58c1fbf9b94c02f3dbaafe127fefca4995f26f82083bd52ae220603b1341ccb" +
            "a7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd4610b4a6ba670000008000000080040000" +
            "80220603de55d1e1dac805e3f8a58c1fbf9b94c02f3dbaafe127fefca4995f26f82083bd10b4a6ba67000000" +
            "8000000080050000800000"

        private fun String.toBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        private fun psbtBytes(): ByteArray = FULL_HEX.toBytes()

        private fun assertFriendlyRejection(bytes: ByteArray) {
            val e = assertThrows(IllegalArgumentException::class.java) { preparePsbtFileBytes(bytes) }
            assertTrue("rejection must carry a user-facing message", e.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `raw psbt bytes pass through unchanged`() {
        val raw = psbtBytes()
        assertArrayEquals(raw, preparePsbtFileBytes(raw))
    }

    @Test
    fun `raw psbt bytes wrapped in crlf and spaces are trimmed and still pass`() {
        val raw = psbtBytes()
        val wrapped = ByteArray(1) { ' '.code.toByte() } +
            "\r\n".toByteArray(Charsets.ISO_8859_1) +
            raw +
            "\n".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf('\t'.code.toByte(), ' '.code.toByte())
        assertArrayEquals(raw, preparePsbtFileBytes(wrapped))
    }

    @Test
    fun `a single-line base64 psbt decodes to the psbt bytes`() {
        val raw = psbtBytes()
        val singleLine = Base64.getEncoder().encodeToString(raw).toByteArray(Charsets.ISO_8859_1)
        assertArrayEquals(raw, preparePsbtFileBytes(singleLine))
    }

    @Test
    fun `base64 wrapped at 76 chars with crlf line endings decodes to the psbt bytes`() {
        val raw = psbtBytes()
        val wrapped = Base64.getMimeEncoder(76, "\r\n".toByteArray(Charsets.ISO_8859_1))
            .encodeToString(raw)
            .toByteArray(Charsets.ISO_8859_1)
        assertArrayEquals(raw, preparePsbtFileBytes(wrapped))
    }

    @Test
    fun `a base64 psbt with a trailing newline decodes to the psbt bytes`() {
        val raw = psbtBytes()
        val withNewline = Base64.getEncoder().encodeToString(raw) + "\n"
        assertArrayEquals(raw, preparePsbtFileBytes(withNewline.toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun `an empty file is rejected with a friendly message`() {
        assertFriendlyRejection(byteArrayOf())
    }

    @Test
    fun `a whitespace-only file is rejected instead of crashing`() {
        assertFriendlyRejection(" \r\n\t\n".toByteArray(Charsets.ISO_8859_1))
    }

    @Test
    fun `a file holding only the psbt letters without the ff separator is rejected as truncated magic`() {
        // 4 bytes — too short for the 5-byte magic; the base64 route must
        // not resurrect it either (decoding "psbt" yields 3 unrelated bytes).
        assertFriendlyRejection("psbt".toByteArray(Charsets.ISO_8859_1))
    }

    @Test
    fun `psbt bytes with a corrupted magic byte are rejected`() {
        val broken = psbtBytes()
        broken[2] = 'X'.code.toByte() // "psXt\xff" — no longer a PSBT
        assertFriendlyRejection(broken)
    }

    @Test
    fun `valid base64 of non-psbt data is rejected as not a psbt`() {
        val notPsbt = Base64.getEncoder().encodeToString("hello world".toByteArray())
        assertFriendlyRejection(notPsbt.toByteArray(Charsets.ISO_8859_1))
    }

    @Test
    fun `binary garbage that is not readable base64 is rejected with a friendly message`() {
        // No 0xFF byte, no whitespace, and not a valid PSBT — must fail
        // cleanly on one of the two documented friendly paths.
        val garbage = ByteArray(256) { ((it % 23) + 2).toByte() }
        assertFriendlyRejection(garbage)
    }

    @Test
    fun `plain text that is neither psbt nor decodable is rejected without leaking a raw exception`() {
        assertFriendlyRejection("This is not a PSBT at all, honestly.\n".toByteArray(Charsets.ISO_8859_1))
    }
}
