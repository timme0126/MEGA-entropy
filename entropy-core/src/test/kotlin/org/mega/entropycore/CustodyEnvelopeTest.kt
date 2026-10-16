package org.mega.entropycore

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

/**
 * End-to-end envelope tests using JDK-standard AES-GCM as the injected
 * cipher — the same primitive the app injects at runtime.
 */
class CustodyEnvelopeTest {

    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val salt = ByteArray(16) { (it + 1).toByte() }
    private val iv = ByteArray(12) { (it + 2).toByte() }
    private val bk = ByteArray(4096) { (it % 251).toByte() }
    private val att = ByteArray(9000) { (128 - it % 128).toByte() }

    private fun inner(): ByteArray =
        CustodyEnvelope.buildInner(bk, "will-2026.pdf", att)

    private fun seal(inner: ByteArray, name: String): ByteArray {
        val header = CustodyEnvelope.buildHeader(1 shl 17, 8, 1, salt, iv, name, inner.size, att.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(header)
        return header + c.doFinal(inner)
    }

    private fun open(file: ByteArray): ByteArray {
        val env = CustodyEnvelope.open(file)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, env.iv))
        c.updateAAD(env.aad)
        return c.doFinal(env.ciphertext)
    }

    @Test
    fun roundTripSealsAndRecoversBothRecords() {
        val inner = inner()
        val file = seal(inner, "will-2026.pdf")
        val recovered = open(file)
        assertArrayEquals(inner, recovered)
        val doc = CustodyEnvelope.parseInner(recovered)
        assertArrayEquals(bk, doc.backupFileBytes)
        assertArrayEquals(att, doc.attachment)
        assertEquals("will-2026.pdf", doc.attachmentName)
    }

    @Test
    fun headerIsDeterministicAndCarriesRealMetadata() {
        val header = CustodyEnvelope.buildHeader(1 shl 17, 8, 1, salt, iv, "doc.bin", 12345, 9000)
        val text = String(header, Charsets.US_ASCII)
        val expected =
            "MEGACUSTODY-V1\nV:1\nN:131072\nR:8\nP:1\n" +
                "SALT:0102030405060708090a0b0c0d0e0f10\nIV:02030405060708090a0b0c0d\n" +
                "NAME:doc.bin\nPSIZE:12345\nFSIZE:9000\nEND-HEADER\n"
        assertEquals(expected, text)
    }

    @Test
    fun tamperedHeaderFailsAuthentication() {
        val file = seal(inner(), "will-2026.pdf")
        val bad = file.copyOf()
        val nameIdx = String(bad, Charsets.US_ASCII).indexOf("NAME:")
        bad[nameIdx + 6] = 'X'.code.toByte()
        assertThrows(AEADBadTagException::class.java) { open(bad) }
    }

    @Test
    fun flippedCiphertextByteFailsAuthentication() {
        val file = seal(inner(), "will-2026.pdf")
        val bad = file.copyOf()
        bad[bad.size - 20] = (bad[bad.size - 20].toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { open(bad) }
    }

    @Test
    fun hostileScryptCostsAreRejectedAtParseBeforeAnyDerivation() {
        val file = seal(inner(), "will-2026.pdf")
        val tampered = String(file, Charsets.US_ASCII)
            .replace("N:131072", "N:33554432")
            .toByteArray(Charsets.US_ASCII)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CustodyEnvelope.open(tampered)
        }
        // Either the bounds check or the resulting tag/parse mismatch —
        // what matters is that it never reaches a derivation.
        assertEquals(true, ex.message!!.contains("N"))
    }

    @Test
    fun wrongMagicAndTruncatedFilesAreRejected() {
        val file = seal(inner(), "will-2026.pdf")
        assertThrows(IllegalArgumentException::class.java) {
            CustodyEnvelope.open(
                "NOPE-XXXXXXXXV1V".toByteArray(Charsets.US_ASCII) + file.copyOfRange(16, file.size),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CustodyEnvelope.open(file.copyOfRange(0, 40))
        }
    }

    @Test
    fun innerFramingRejectsTrailingBytesAndBadPrefixes() {
        val inner = inner()
        assertThrows(IllegalArgumentException::class.java) {
            CustodyEnvelope.parseInner(inner + byteArrayOf(0x00))
        }
        val mangled = inner.copyOf()
        val firstNewline = String(mangled, Charsets.US_ASCII).indexOf('\n')
        mangled[firstNewline + 1] = 'x'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) {
            CustodyEnvelope.parseInner(mangled)
        }
    }

    @Test
    fun filenamesWithLineBreaksOrOversizeAreRefused() {
        try {
            CustodyEnvelope.buildHeader(1 shl 15, 8, 1, salt, iv, "bad\nname", 100, 50)
            fail("expected refusal for newline in filename")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            CustodyEnvelope.buildHeader(1 shl 15, 8, 1, salt, iv, "x".repeat(257), 100, 50)
            fail("expected refusal for oversize filename")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun buildInnerRejectsEmptyRecords() {
        try {
            CustodyEnvelope.buildInner(ByteArray(0), "a.pdf", att)
            fail("expected refusal for empty backup")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            CustodyEnvelope.buildInner(bk, "a.pdf", ByteArray(0))
            fail("expected refusal for empty attachment")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun streamingPreamblesReproduceBuildInnerByteForByte() {
        val nameBytes = "will-2026.pdf".toByteArray(Charsets.UTF_8)
        val expected = CustodyEnvelope.buildInner(bk, "will-2026.pdf", att)
        val out = java.io.ByteArrayOutputStream()
        out.write(CustodyEnvelope.innerPreambleA(nameBytes.size))
        out.write(nameBytes)
        out.write(CustodyEnvelope.innerPreambleB(bk.size))
        out.write(bk)
        out.write(CustodyEnvelope.innerPreambleC(att.size))
        out.write(att)
        assertArrayEquals(expected, out.toByteArray())
        assertEquals(
            expected.size.toLong(),
            CustodyEnvelope.innerLengthOf(nameBytes.size, bk.size, att.size).toLong(),
        )
    }

    @Test
    fun parseHeaderPrefixMatchesFullParseOnHeaderOnlyBuffer() {
        val file = seal(inner(), "will-2026.pdf")
        val info = CustodyEnvelope.parseHeaderPrefix(file.copyOf(2048))
        val full = CustodyEnvelope.open(file)
        assertArrayEquals(full.aad, info.aad)
        assertEquals(full.attachmentName, info.attachmentName)
        assertEquals(full.attachmentSize, info.attachmentSize)
        assertEquals(full.payloadSize, info.payloadSize)
        assertEquals(file.size, info.headerLength + full.ciphertext.size)
    }
}
