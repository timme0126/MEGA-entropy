package org.mega.entropycore

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class BbqrSparrowCompatTest {

    /**
     * An independently-written RFC4648 Base32 decoder, deliberately NOT sharing
     * any code with Bbqr.kt's own (private, inaccessible from this file anyway)
     * decodeBase32 — this exists so this test suite provides genuine
     * cross-implementation verification, not just "MEGA agrees with itself".
     * Mirrors the exact strict length validation Google Guava's
     * BaseEncoding.base32().omitPadding() applies (and which Sparrow, a real
     * Guava-based Bitcoin wallet, enforces in practice — this is what actually
     * produced the reported "Invalid input length 150" error): a chunk's
     * length, mod 8, must be one of {0, 2, 4, 5, 7} or it is rejected outright,
     * matching the BBQr spec's own explicit requirement that a Base32 block
     * "must send complete bytes."
     */
    private object ReferenceBase32 {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        fun decodeStrict(chunk: String): ByteArray {
            val remainder = chunk.length % 8
            require(remainder in setOf(0, 2, 4, 5, 7)) { "Invalid input length ${chunk.length}" }
            var bitBuffer = 0L
            var bitsInBuffer = 0
            val out = java.io.ByteArrayOutputStream()
            for (c in chunk) {
                val value = ALPHABET.indexOf(c)
                require(value >= 0) { "Invalid Base32 character: $c" }
                bitBuffer = (bitBuffer shl 5) or value.toLong()
                bitsInBuffer += 5
                if (bitsInBuffer >= 8) {
                    bitsInBuffer -= 8
                    out.write(((bitBuffer shr bitsInBuffer) and 0xFF).toInt())
                }
            }
            return out.toByteArray()
        }

        fun encode(bytes: ByteArray): String {
            val out = StringBuilder()
            var bitBuffer = 0L
            var bitsInBuffer = 0
            for (b in bytes) {
                bitBuffer = (bitBuffer shl 8) or (b.toLong() and 0xFF)
                bitsInBuffer += 8
                while (bitsInBuffer >= 5) {
                    bitsInBuffer -= 5
                    out.append(ALPHABET[((bitBuffer shr bitsInBuffer) and 0x1F).toInt()])
                }
            }
            if (bitsInBuffer > 0) {
                out.append(ALPHABET[((bitBuffer shl (5 - bitsInBuffer)) and 0x1F).toInt()])
            }
            return out.toString()
        }
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private val TWO_COSIGNER_DERIVATION_PATH = listOf(2147483696L, 2147483648L, 2147483648L, 2147483650L, 0L, 0L)
    private val TWO_COSIGNER_UNSIGNED_TX_HEX =
        "0200000001279a2323a5dfb51fc45f220fa58b0fc13e1e3342792a85d7e36cd6333b5cbc390000000000ffffffff01a05aea0b000000001976a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac00000000"
    private val TWO_COSIGNER_WITNESS_UTXO_AMOUNT = 199909013L

    private fun masterKeyFor(words: List<String>) = bip32MasterKeyFromSeed(deriveSeed(words, "").bytes)

    private fun childKeyFor(master: Bip32ExtendedPrivateKey): Bip32ExtendedPrivateKey {
        var child = master
        for (rawIndex in TWO_COSIGNER_DERIVATION_PATH) {
            val hardened = rawIndex >= HARDENED_OFFSET
            val index = if (hardened) rawIndex - HARDENED_OFFSET else rawIndex
            child = child.deriveChild(index, hardened)
        }
        return child
    }

    private fun Long.toUInt32LE(): ByteArray = byteArrayOf(
        (this and 0xFF).toByte(), ((this shr 8) and 0xFF).toByte(),
        ((this shr 16) and 0xFF).toByte(), ((this shr 24) and 0xFF).toByte(),
    )

    private fun Long.toUInt64LE(): ByteArray = byteArrayOf(
        (this and 0xFF).toByte(), ((this shr 8) and 0xFF).toByte(),
        ((this shr 16) and 0xFF).toByte(), ((this shr 24) and 0xFF).toByte(),
        ((this shr 32) and 0xFF).toByte(), ((this shr 40) and 0xFF).toByte(),
        ((this shr 48) and 0xFF).toByte(), ((this shr 56) and 0xFF).toByte(),
    )

    private fun shortCompactSize(len: Int): ByteArray {
        require(len < 0xFD)
        return byteArrayOf(len.toByte())
    }

    private fun bip32DerivationValue(fingerprint: ByteArray): ByteArray =
        fingerprint + TWO_COSIGNER_DERIVATION_PATH.fold(ByteArray(0)) { acc, element -> acc + element.toUInt32LE() }

    /**
     * Builds a real, unsigned 2-of-2 P2WSH multisig PSBT — same fixture
     * shape CosignerPsbtSigningTest.kt/PsbtSummaryTest.kt use — with ONE
     * input carrying BOTH cosigners' bip32_derivation entries plus a real
     * witness_utxo/witness_script, and ONE output. parsePsbt requires
     * exactly one input map and one output map per entry in the unsigned
     * transaction, so both must be populated to match unsignedTx.inputs/
     * .outputs — an empty `inputs`/`outputs` list here would leave the
     * serialized bytes truncated relative to what the unsigned tx's own
     * counts promise, and parsePsbt would fail the moment anything tried
     * to re-parse them.
     */
    private fun buildTwoCosignerPsbtBytes(): ByteArray {
        val masterA = masterKeyFor(TWO_COSIGNER_WORDS_A)
        val masterB = masterKeyFor(TWO_COSIGNER_WORDS_B)
        val pubkeyA = childKeyFor(masterA).compressedPublicKey()
        val pubkeyB = childKeyFor(masterB).compressedPublicKey()
        val sortedPubkeys = sortPublicKeysBip67(listOf(pubkeyA, pubkeyB))
        val witnessScript = buildMultisigWitnessScript(2, sortedPubkeys)
        val scriptPubKey = byteArrayOf(0x00, 0x20) + sha256(witnessScript)
        val witnessUtxoValue = TWO_COSIGNER_WITNESS_UTXO_AMOUNT.toUInt64LE() + shortCompactSize(scriptPubKey.size) + scriptPubKey
        val inputMap = PsbtMap(
            entries = listOf(
                PsbtKeyValue(keyType = 0x01, keyData = ByteArray(0), value = witnessUtxoValue),
                PsbtKeyValue(keyType = 0x05, keyData = ByteArray(0), value = witnessScript),
                PsbtKeyValue(keyType = 0x06, keyData = pubkeyA, value = bip32DerivationValue(masterA.fingerprint())),
                PsbtKeyValue(keyType = 0x06, keyData = pubkeyB, value = bip32DerivationValue(masterB.fingerprint())),
            ),
        )
        val unsignedTx = parseTransaction(TWO_COSIGNER_UNSIGNED_TX_HEX.hexToBytes())
        val psbt = Psbt(
            unsignedTx = unsignedTx,
            global = PsbtMap(listOf(PsbtKeyValue(keyType = 0x00, keyData = ByteArray(0), value = serializeTransaction(unsignedTx)))),
            inputs = listOf(inputMap),
            outputs = unsignedTx.outputs.map { PsbtMap(emptyList()) },
        )
        return serializePsbt(psbt)
    }

    private val TWO_COSIGNER_WORDS_A =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(' ')
    private val TWO_COSIGNER_WORDS_B =
        "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote".split(' ')

    @Test fun `single frame BBQr round-trips exactly and payload independently satisfies strict length rule`() {
        val data = "hello bbqr test payload".toByteArray()
        val frames = encodeBbqr('P', data)
        assertEquals(1, frames.size)
        val part = parseBbqrPart(frames[0])
        assertNotNull(part)
        assertEquals(1, part!!.total)
        assertEquals(0, part.index)
        assertEquals('2', part.encoding)
        assertEquals('P', part.fileType)
        val decoded = ReferenceBase32.decodeStrict(part.payload)
        assertArrayEquals(data, decoded)
    }

    @Test fun `multi frame BBQr round-trips exactly every frame independently valid no gaps or duplicates`() {
        val data = ByteArray(500) { it.toByte() }
        val frames = encodeBbqr('P', data)
        assertTrue(frames.size > 1)
        val parsedParts = frames.map { parseBbqrPart(it)!! }
        val total = parsedParts[0].total
        assertEquals(frames.size, total)
        parsedParts.forEach { assertEquals(total, it.total) }
        val indices = parsedParts.map { it.index }.toSet()
        assertEquals((0 until frames.size).toSet(), indices)
        val decodedBytes = parsedParts.sortedBy { it.index }.map { ReferenceBase32.decodeStrict(it.payload) }.flatMap { it.toList() }.toByteArray()
        assertArrayEquals(data, decodedBytes)
    }

    @Test fun `every frame at a range of payload sizes independently satisfies strict length rule`() {
        val lengths = listOf(1, 5, 19, 63, 64, 65, 94, 95, 150, 151, 189, 190, 191, 500, 1000, 4000)
        for (length in lengths) {
            val data = ByteArray(length) { (it % 256).toByte() }
            val frames = encodeBbqr('P', data)
            for ((idx, frame) in frames.withIndex()) {
                val part = parseBbqrPart(frame)
                try {
                    ReferenceBase32.decodeStrict(part!!.payload)
                } catch (e: Exception) {
                    fail("Frame $idx failed strict decode for byte length $length: ${e.message}")
                }
            }
            val decodedBytes = frames.map { parseBbqrPart(it)!! }.sortedBy { it.index }.map { ReferenceBase32.decodeStrict(it.payload) }.flatMap { it.toList() }.toByteArray()
            assertArrayEquals(data, decodedBytes)
        }
    }

    @Test fun `last frames length is often not a multiple of 8 but is still independently valid`() {
        val data = ByteArray(1000) { (it % 256).toByte() }
        val frames = encodeBbqr('P', data)
        val lastPart = parseBbqrPart(frames.last())!!
        val remainder = lastPart.payload.length % 8
        assertTrue("Last frame payload length mod 8 must be in {0, 2, 4, 5, 7}, got $remainder", remainder in setOf(0, 2, 4, 5, 7))
        ReferenceBase32.decodeStrict(lastPart.payload)
    }

    @Test fun `real signed multisig PSBT BBQr export is fully Sparrow compatible`() {
        val psbtBytes = buildTwoCosignerPsbtBytes()
        val signedBytes = signAndFinalizePsbt(psbtBytes, TWO_COSIGNER_WORDS_A, "")
        val frames = encodeBbqr('P', signedBytes)
        for ((idx, frame) in frames.withIndex()) {
            val part = parseBbqrPart(frame)
            try {
                ReferenceBase32.decodeStrict(part!!.payload)
            } catch (e: Exception) {
                fail("Frame $idx failed strict decode for multisig PSBT: ${e.message}")
            }
        }
        val decodedBytes = frames.map { parseBbqrPart(it)!! }.sortedBy { it.index }.map { ReferenceBase32.decodeStrict(it.payload) }.flatMap { it.toList() }.toByteArray()
        assertArrayEquals(signedBytes, decodedBytes)
    }

    @Test fun `finalized transaction BBQr export uses file type T and is fully Sparrow compatible`() {
        val psbtBytes = buildTwoCosignerPsbtBytes()
        val onceSigned = signAndFinalizePsbt(psbtBytes, TWO_COSIGNER_WORDS_A, "")
        val twiceSigned = signAndFinalizePsbt(onceSigned, TWO_COSIGNER_WORDS_B, "")
        assertTrue(isPsbtFullyFinalized(twiceSigned))
        val finalTxHex = extractFinalTransactionHex(twiceSigned)!!
        val txBytes = finalTxHex.hexToBytes()
        val frames = encodeBbqr('T', txBytes)
        val firstPart = parseBbqrPart(frames[0])!!
        assertEquals('T', firstPart.fileType)
        for ((idx, frame) in frames.withIndex()) {
            val part = parseBbqrPart(frame)
            try {
                ReferenceBase32.decodeStrict(part!!.payload)
            } catch (e: Exception) {
                fail("Frame $idx failed strict decode for finalized TX: ${e.message}")
            }
        }
        val decodedBytes = frames.map { parseBbqrPart(it)!! }.sortedBy { it.index }.map { ReferenceBase32.decodeStrict(it.payload) }.flatMap { it.toList() }.toByteArray()
        assertArrayEquals(txBytes, decodedBytes)
    }

    @Test fun `compressed Z encoding BBQr series from external encoder is correctly decoded`() {
        val original = "compressible test data ".repeat(20).toByteArray()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(original)
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            compressed.write(buf, 0, count)
        }
        deflater.end()
        val compressedBytes = compressed.toByteArray()
        val base32Str = ReferenceBase32.encode(compressedBytes)
        val frame = "B\$ZU0100$base32Str"
        val part = parseBbqrPart(frame)
        assertNotNull(part)
        assertEquals('Z', part!!.encoding)
        assertEquals('U', part.fileType)
        assertEquals(1, part.total)
        assertEquals(0, part.index)
        // assembleBbqrParts already performs the full pipeline internally
        // (Base32-decode -> inflate, since encoding is 'Z' -> UTF-8 decode
        // to text, since file type is 'U') — its return value IS the final
        // decompressed text, not something that needs decompressing again.
        val assembled = assembleBbqrParts(listOf(part))
        assertEquals(original.toString(Charsets.UTF_8), assembled)
    }

    @Test fun `malformed frame is rejected not silently accepted`() {
        assertNull(parseBbqrPart("not a bbqr frame at all"))
        val data = ByteArray(200) { it.toByte() }
        val frames = encodeBbqr('P', data)
        val parts = frames.map { parseBbqrPart(it)!! }
        val remaining = parts.drop(1)
        assertThrows(IllegalArgumentException::class.java) {
            assembleBbqrPartsAsBytes(remaining)
        }
    }

    @Test fun `wrong or unsupported encoding or file type is rejected`() {
        val badEncodingFrame = "B\$XP0100somepayload"
        val part = parseBbqrPart(badEncodingFrame)
        assertNotNull(part)
        assertThrows(IllegalArgumentException::class.java) {
            assembleBbqrPartsAsBytes(listOf(part!!))
        }
        val textData = "hello".toByteArray()
        val textFrames = encodeBbqr('U', textData)
        val textParts = textFrames.map { parseBbqrPart(it)!! }
        assertThrows(IllegalArgumentException::class.java) {
            assembleBbqrPartsAsBytes(textParts)
        }
    }

    @Test fun `encodeBbqr itself refuses a non multiple of 8 part size`() {
        assertThrows(IllegalArgumentException::class.java) {
            encodeBbqr('P', byteArrayOf(1, 2, 3), partPayloadSize = 150)
        }
    }
}
