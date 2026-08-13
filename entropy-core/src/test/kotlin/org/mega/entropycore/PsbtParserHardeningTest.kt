package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the v0.1.9 audit hardening of parsePsbt:
 * duplicate keys within a map are rejected (BIP174 requires uniqueness),
 * the global unsigned transaction must be in canonical non-witness
 * serialization, and its scriptSigs must be empty.
 */
class PsbtParserHardeningTest {

    companion object {
        // The official BIP174 P2SH-P2WSH vector PsbtTest.kt already uses.
        private const val FULL_HEX = "70736274ff0100550200000001279a2323a5dfb51fc45f220fa58b0fc13e1e3342792a85d7e36cd6333b5cbc390000000000ffffffff01a05aea0b000000001976a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac0000000000010120955eea0b0000000017a9146345200f68d189e1adc0df1c4d16ea8f14c0dbeb87220203b1341ccba7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd4646304302200424b58effaaa694e1559ea5c93bbfd4a89064224055cdf070b6771469442d07021f5c8eb0fea6516d60b8acb33ad64ede60e8785bfb3aa94b99bdf86151db9a9a010104220020771fd18ad459666dd49f3d564e3dbc42f4c84774e360ada16816a8ed488d5681010547522103b1341ccba7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd462103de55d1e1dac805e3f8a58c1fbf9b94c02f3dbaafe127fefca4995f26f82083bd52ae220603b1341ccba7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd4610b4a6ba67000000800000008004000080220603de55d1e1dac805e3f8a58c1fbf9b94c02f3dbaafe127fefca4995f26f82083bd10b4a6ba670000008000000080050000800000"

        private fun String.toBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun basePsbt(): Psbt = parsePsbt(FULL_HEX.toBytes())

        private fun writeCompactSizeForTest(value: Long): ByteArray = when {
            value < 253 -> byteArrayOf(value.toByte())
            value <= 0xFFFFL -> byteArrayOf(0xfd.toByte(), (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
            else -> throw IllegalArgumentException()
        }
    }

    @Test
    fun `the official vector still parses and round-trips after hardening`() {
        assertEquals(FULL_HEX, serializePsbt(basePsbt()).toHex())
    }

    @Test
    fun `a duplicate key within an input map is rejected`() {
        // Re-emit the witness_utxo entry (same keyType AND same keyData) — the
        // map model allows constructing it, the parser must refuse to read it.
        val base = basePsbt()
        val dupInput = PsbtMap(base.inputs[0].entries + base.inputs[0].entries[0])
        val psbt = base.copy(inputs = listOf(dupInput))
        val e = assertThrows(IllegalArgumentException::class.java) { parsePsbt(serializePsbt(psbt)) }
        assertTrue(e.message.orEmpty().contains("Duplicate key"))
    }

    @Test
    fun `a duplicate partial-sig entry for the same pubkey is rejected`() {
        val base = basePsbt()
        val partialSigEntry = base.inputs[0].entries.first { it.keyType == 0x02 }
        val dupInput = PsbtMap(base.inputs[0].entries + partialSigEntry)
        val psbt = base.copy(inputs = listOf(dupInput))
        assertThrows(IllegalArgumentException::class.java) { parsePsbt(serializePsbt(psbt)) }
    }

    @Test
    fun `two bip32 derivations with distinct keyData are still allowed`() {
        // Sanity: the vector's input map legitimately repeats keyType 0x06 with
        // different pubkeys — the duplicate check must only fire on identical
        // full keys, never on same-type-different-keyData entries.
        assertEquals(FULL_HEX, serializePsbt(basePsbt()).toHex())
    }

    @Test
    fun `an unsigned transaction with trailing garbage is rejected`() {
        val base = basePsbt()
        val cleanTxBytes = serializeTransaction(base.unsignedTx)
        val garbageValue = cleanTxBytes + byteArrayOf(0x00)
        val psbt = base.copy(global = PsbtMap(listOf(PsbtKeyValue(0x00, ByteArray(0), garbageValue))))
        assertThrows(IllegalArgumentException::class.java) { parsePsbt(serializePsbt(psbt)) }
    }

    @Test
    fun `an unsigned transaction with a non-empty scriptSig is rejected`() {
        val base = basePsbt()
        val dirty = base.unsignedTx.copy(inputs = listOf(base.unsignedTx.inputs[0].copy(scriptSig = byteArrayOf(0x51))))
        val psbt = Psbt(
            unsignedTx = dirty,
            global = PsbtMap(listOf(PsbtKeyValue(0x00, ByteArray(0), serializeTransaction(dirty)))),
            inputs = listOf(PsbtMap(emptyList())),
            outputs = dirty.outputs.map { PsbtMap(emptyList()) },
        )
        val e = assertThrows(IllegalArgumentException::class.java) { parsePsbt(serializePsbt(psbt)) }
        assertTrue(e.message.orEmpty().contains("scriptSigs"))
    }

    @Test
    fun `a witness-serialized unsigned transaction is rejected`() {
        // 0x00 0x01 marker/flag straight after the version — a witness-program
        // serialization must never be accepted as the PSBT unsigned tx.
        val txBytes = FULL_HEX.toBytes().copyOfRange(8, 93)
        val withMarker = txBytes.copyOfRange(0, 4) + byteArrayOf(0x00, 0x01) + txBytes.copyOfRange(4, txBytes.size)
        val rawBytes = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xFF.toByte()) +
            byteArrayOf(0x01, 0x00) +
            writeCompactSizeForTest(withMarker.size.toLong()) +
            withMarker +
            byteArrayOf(0x00)
        assertThrows(IllegalArgumentException::class.java) { parsePsbt(rawBytes) }
    }
}
