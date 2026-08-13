package org.mega.entropycore

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.Deflater

/**
 * Regression test for the v0.1.9 audit hardening of BBQr 'Z' decoding:
 * inflate output is capped so a hostile QR series can't act as a zip bomb.
 */
class BbqrHardeningTest {

    private fun rawDeflate(data: ByteArray): ByteArray {
        val deflater = Deflater(9, true) // raw deflate, no zlib header — as BBQr 'Z' uses
        deflater.setInput(data)
        deflater.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            out.write(buf, 0, deflater.deflate(buf))
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun base32Encode(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder()
        var bits = 0
        var bitCount = 0
        for (byte in data) {
            bits = (bits shl 8) or (byte.toInt() and 0xFF)
            bitCount += 8
            while (bitCount >= 5) {
                bitCount -= 5
                sb.append(alphabet[(bits shr bitCount) and 0x1F])
            }
        }
        if (bitCount > 0) sb.append(alphabet[(bits shl (5 - bitCount)) and 0x1F])
        return sb.toString()
    }

    private fun toBase36(value: Int): String =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".let { "" + it[value / 36] + it[value % 36] }

    /** Builds a complete one-frame BBQr part list carrying [payload] under 'Z'. */
    private fun bbqrZParts(payload: ByteArray): List<BbqrPart> {
        val encoded = base32Encode(payload)
        val partSize = 152 // multiple of 8, same as the encoder's own default
        val total = (encoded.length + partSize - 1) / partSize
        return (0 until total).map { index ->
            val chunk = encoded.substring(index * partSize, minOf((index + 1) * partSize, encoded.length))
            BbqrPart(encoding = 'Z', fileType = 'P', total = total, index = index, payload = chunk)
        }
    }

    @Test
    fun `a deflate bomb fails closed instead of exhausting memory`() {
        // 16 MB of zero bytes deflates to ~16 KB — a tiny QR series inflating
        // far past any legitimate PSBT. Must be rejected by the output cap.
        val bomb = rawDeflate(ByteArray(16 * 1024 * 1024))
        val parts = bbqrZParts(bomb)
        val e = assertThrows(IllegalArgumentException::class.java) {
            assembleBbqrPartsAsBytes(parts)
        }
        assertTrue(e.message.orEmpty().contains("decompresses to more than"))
    }

    @Test
    fun `a small legitimately-compressed payload still decodes`() {
        val payload = "wsh(sortedmulti(2,placeholder)) — a small, legit export, repeated a bit. ".repeat(20)
            .encodeToByteArray()
        val compressed = rawDeflate(payload)
        val parts = bbqrZParts(compressed)
        val decoded = assembleBbqrPartsAsBytes(parts)
        assertTrue(decoded.contentEquals(payload))
    }
}
