package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for canonical-encoding defects found during the
 * independent re-review of the v0.1.9 audit hardening — issues the original
 * audit report did not list, each empirically confirmed against the code
 * before being fixed:
 *
 *  - a global key of type 0x00 carrying KEYDATA (`00 aa`) was accepted and
 *    used as the unsigned transaction, even though BIP174 defines that key
 *    as the type byte alone and Bitcoin Core/Sparrow both reject it. Because
 *    the duplicate-key rule compares FULL keys, `00` and `00 aa` are
 *    "different" keys, so a file could carry both and MEGA would resolve to
 *    whichever came first while a strict peer rejected the file outright —
 *    exactly the display-vs-sign divergence the duplicate-key rule exists to
 *    close;
 *  - trailing bytes after the final output map were silently ignored, so the
 *    reviewed-and-signed PSBT could be a mere prefix of the scanned bytes;
 *  - non-minimal compact-size varints were accepted, giving the same logical
 *    content multiple valid byte representations.
 */
class PsbtCanonicalEncodingTest {

    companion object {
        /** The same real 1-input/1-output unsigned transaction the rest of the
         * PSBT test suite uses. */
        private const val UNSIGNED_TX_HEX =
            "0200000001279a2323a5dfb51fc45f220fa58b0fc13e1e3342792a85d7e36cd6333b5cbc39" +
                "0000000000ffffffff01a05aea0b000000001976a914ffe9c0061097cc3b636f2cb0460f" +
                "a4fc427d2b4588ac00000000"

        private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        /** Minimal compact-size encoding, matching writeCompactSize. */
        private fun cs(n: Int): ByteArray = when {
            n < 253 -> byteArrayOf(n.toByte())
            n <= 0xFFFF -> byteArrayOf(0xFD.toByte(), (n and 0xFF).toByte(), ((n shr 8) and 0xFF).toByte())
            else -> throw IllegalArgumentException("test helper only needs small sizes")
        }

        private fun keyValue(key: ByteArray, value: ByteArray): ByteArray =
            cs(key.size) + key + cs(value.size) + value

        /**
         * Assembles raw PSBT bytes from explicit key/value entries per map, so a
         * test can express encodings [serializePsbt] would never produce.
         */
        private fun rawPsbt(
            globalEntries: List<ByteArray>,
            inputEntries: List<List<ByteArray>>,
            outputEntries: List<List<ByteArray>>,
            trailing: ByteArray = ByteArray(0),
        ): ByteArray {
            var out = "70736274ff".hexToBytes()
            globalEntries.forEach { out += it }
            out += byteArrayOf(0x00)
            inputEntries.forEach { entries ->
                entries.forEach { out += it }
                out += byteArrayOf(0x00)
            }
            outputEntries.forEach { entries ->
                entries.forEach { out += it }
                out += byteArrayOf(0x00)
            }
            return out + trailing
        }

        /** A well-formed single-input/single-output PSBT: the control case. */
        private fun validPsbtBytes(trailing: ByteArray = ByteArray(0)): ByteArray = rawPsbt(
            globalEntries = listOf(keyValue(byteArrayOf(0x00), UNSIGNED_TX_HEX.hexToBytes())),
            inputEntries = listOf(emptyList()),
            outputEntries = listOf(emptyList()),
            trailing = trailing,
        )
    }

    @Test
    fun `the control PSBT parses and round-trips`() {
        val psbt = parsePsbt(validPsbtBytes())
        assertEquals(1, psbt.inputs.size)
        assertEquals(1, psbt.outputs.size)
        assertTrue(serializePsbt(psbt).contentEquals(validPsbtBytes()))
    }

    @Test
    fun `a global unsigned-tx key carrying keydata is rejected`() {
        val bytes = rawPsbt(
            globalEntries = listOf(keyValue(byteArrayOf(0x00, 0xAA.toByte()), UNSIGNED_TX_HEX.hexToBytes())),
            inputEntries = listOf(emptyList()),
            outputEntries = listOf(emptyList()),
        )
        val e = assertThrows(IllegalArgumentException::class.java) { parsePsbt(bytes) }
        assertTrue(e.message!!.contains("type byte alone"))
    }

    @Test
    fun `two distinct type-0x00 global keys are rejected rather than resolved to the first`() {
        // `00` and `00 aa` are DIFFERENT full keys, so the duplicate-key rule
        // does not catch this on its own — the count check must.
        val bytes = rawPsbt(
            globalEntries = listOf(
                keyValue(byteArrayOf(0x00, 0xAA.toByte()), UNSIGNED_TX_HEX.hexToBytes()),
                keyValue(byteArrayOf(0x00), UNSIGNED_TX_HEX.hexToBytes()),
            ),
            inputEntries = listOf(emptyList()),
            outputEntries = listOf(emptyList()),
        )
        val e = assertThrows(IllegalArgumentException::class.java) { parsePsbt(bytes) }
        assertTrue(e.message!!.contains("exactly one global unsigned transaction"))
    }

    @Test
    fun `trailing bytes after the final output map are rejected`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            parsePsbt(validPsbtBytes(trailing = "deadbeef".hexToBytes()))
        }
        assertTrue(e.message!!.contains("Trailing bytes"))
    }

    @Test
    fun `a single trailing byte is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            parsePsbt(validPsbtBytes(trailing = byteArrayOf(0x00)))
        }
    }

    @Test
    fun `a non-minimal two-byte compact size is rejected`() {
        // Key length 1 written as `fd 01 00` instead of `01`.
        var out = "70736274ff".hexToBytes()
        out += byteArrayOf(0xFD.toByte(), 0x01, 0x00) + byteArrayOf(0x00)
        out += cs(UNSIGNED_TX_HEX.hexToBytes().size) + UNSIGNED_TX_HEX.hexToBytes()
        out += byteArrayOf(0x00, 0x00, 0x00)
        val e = assertThrows(IllegalArgumentException::class.java) { parsePsbt(out) }
        assertTrue(e.message!!.contains("Non-minimal"))
    }

    @Test
    fun `a non-minimal four-byte compact size is rejected`() {
        // Key length 1 written as `fe 01 00 00 00`.
        var out = "70736274ff".hexToBytes()
        out += byteArrayOf(0xFE.toByte(), 0x01, 0x00, 0x00, 0x00) + byteArrayOf(0x00)
        out += cs(UNSIGNED_TX_HEX.hexToBytes().size) + UNSIGNED_TX_HEX.hexToBytes()
        out += byteArrayOf(0x00, 0x00, 0x00)
        val e = assertThrows(IllegalArgumentException::class.java) { parsePsbt(out) }
        assertTrue(e.message!!.contains("Non-minimal"))
    }

    @Test
    fun `a compact size with bit 63 set is rejected instead of reading back negative`() {
        // `ff ff ff ff ff ff ff ff ff` decodes to a negative Kotlin Long, which
        // would slip past every `offset + len > size` bounds check.
        var out = "70736274ff".hexToBytes()
        out += ByteArray(9) { 0xFF.toByte() }
        val e = assertThrows(IllegalArgumentException::class.java) { parsePsbt(out) }
        assertTrue(e.message!!.contains("exceeds the maximum supported length"))
    }

    @Test
    fun `an input witness_utxo key carrying keydata is rejected`() {
        // A decoy `01 aa` would be returned by PsbtMap.witnessUtxo()'s
        // `find { keyType == 0x01 }` ahead of a real `01` key.
        val bytes = rawPsbt(
            globalEntries = listOf(keyValue(byteArrayOf(0x00), UNSIGNED_TX_HEX.hexToBytes())),
            inputEntries = listOf(listOf(keyValue(byteArrayOf(0x01, 0xAA.toByte()), ByteArray(8)))),
            outputEntries = listOf(emptyList()),
        )
        val e = assertThrows(IllegalArgumentException::class.java) { parsePsbt(bytes) }
        assertTrue(e.message!!.contains("type byte alone"))
    }

    @Test
    fun `an input sighash-type key carrying keydata is rejected`() {
        val bytes = rawPsbt(
            globalEntries = listOf(keyValue(byteArrayOf(0x00), UNSIGNED_TX_HEX.hexToBytes())),
            inputEntries = listOf(listOf(keyValue(byteArrayOf(0x03, 0x01), byteArrayOf(0x01, 0x00, 0x00, 0x00)))),
            outputEntries = listOf(emptyList()),
        )
        assertThrows(IllegalArgumentException::class.java) { parsePsbt(bytes) }
    }

    @Test
    fun `an output witness_script key carrying keydata is rejected`() {
        val bytes = rawPsbt(
            globalEntries = listOf(keyValue(byteArrayOf(0x00), UNSIGNED_TX_HEX.hexToBytes())),
            inputEntries = listOf(emptyList()),
            outputEntries = listOf(listOf(keyValue(byteArrayOf(0x01, 0xAA.toByte()), ByteArray(4)))),
        )
        assertThrows(IllegalArgumentException::class.java) { parsePsbt(bytes) }
    }

    @Test
    fun `a keyed type may still carry its pubkey keydata`() {
        // 0x06 (bip32_derivation) legitimately carries a 33-byte pubkey — the
        // singleton-key rule must not touch it.
        val pubkey = "03b1341ccba7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd46".hexToBytes()
        val derivationValue = ByteArray(4) + ByteArray(4)
        val bytes = rawPsbt(
            globalEntries = listOf(keyValue(byteArrayOf(0x00), UNSIGNED_TX_HEX.hexToBytes())),
            inputEntries = listOf(listOf(keyValue(byteArrayOf(0x06) + pubkey, derivationValue))),
            outputEntries = listOf(emptyList()),
        )
        val psbt = parsePsbt(bytes)
        assertEquals(1, psbt.inputs[0].bip32Derivations().size)
    }
}
