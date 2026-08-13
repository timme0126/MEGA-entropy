package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Tests for `finalizePsbt` focusing on P2WSH bare-multisig inputs.
 * Verifies that OP_CHECKMULTISIG's positional signature-matching requirement is correctly enforced:
 * signatures are matched to pubkeys in witness-script order, exactly `threshold` are used,
 * and inputs with insufficient signatures are left unchanged.
 */
@RunWith(JUnit4::class)
class PsbtFinalizationOrderingTest {

    companion object {
        // Three real compressed secp256k1 pubkeys (valid points, taken from existing tests in this repo).
        private val PUBKEY_A = "03b1341ccba7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd46".hexToBytes()
        private val PUBKEY_B = "03de55d1e1dac805e3f8a58c1fbf9b94c02f3dbaafe127fefca4995f26f82083bd".hexToBytes()
        private val PUBKEY_C = "0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c".hexToBytes()
        private const val AMOUNT = 199909013L
        // Each dummy signature is distinguishable by its filler byte so a test can assert WHICH sig landed where.
        private val SIG_A = byteArrayOf(0x30, 0x44, 0x01) + ByteArray(68) { 0xAA.toByte() } + byteArrayOf(0x01)
        private val SIG_B = byteArrayOf(0x30, 0x44, 0x01) + ByteArray(68) { 0xBB.toByte() } + byteArrayOf(0x01)
        private val SIG_C = byteArrayOf(0x30, 0x44, 0x01) + ByteArray(68) { 0xCC.toByte() } + byteArrayOf(0x01)

        private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun Long.toUInt64LE(): ByteArray = byteArrayOf(
            (this and 0xFF).toByte(), ((this shr 8) and 0xFF).toByte(),
            ((this shr 16) and 0xFF).toByte(), ((this shr 24) and 0xFF).toByte(),
            ((this shr 32) and 0xFF).toByte(), ((this shr 40) and 0xFF).toByte(),
            ((this shr 48) and 0xFF).toByte(), ((this shr 56) and 0xFF).toByte(),
        )

        /** Builds `OP_<m> <0x21 pk>... OP_<n> OP_CHECKMULTISIG` for the given keys IN THE GIVEN ORDER. */
        private fun multisigScript(threshold: Int, pubkeys: List<ByteArray>): ByteArray {
            var out = byteArrayOf((0x50 + threshold).toByte())
            for (pk in pubkeys) out += byteArrayOf(0x21) + pk
            out += byteArrayOf((0x50 + pubkeys.size).toByte(), 0xAE.toByte())
            return out
        }

        private fun p2wshScriptPubKey(witnessScript: ByteArray): ByteArray = byteArrayOf(0x00, 0x20) + sha256(witnessScript)

        private fun witnessUtxoEntry(scriptPubKey: ByteArray): PsbtKeyValue =
            PsbtKeyValue(0x01, ByteArray(0), AMOUNT.toUInt64LE() + byteArrayOf(scriptPubKey.size.toByte()) + scriptPubKey)

        /** Input map for a P2WSH multisig input; [sigs] are added in the ORDER GIVEN. */
        private fun inputMapWith(witnessScript: ByteArray, sigs: List<Pair<ByteArray, ByteArray>>): PsbtMap {
            val entries = mutableListOf(witnessUtxoEntry(p2wshScriptPubKey(witnessScript)))
            entries += PsbtKeyValue(0x05, ByteArray(0), witnessScript)
            sigs.forEach { (pubkey, sig) -> entries += PsbtKeyValue(0x02, pubkey, sig) }
            return PsbtMap(entries)
        }

        private fun psbtWith(inputMap: PsbtMap): Psbt {
            val tx = Transaction(
                version = 2,
                inputs = listOf(TxIn(ByteArray(32) { 0x11 }, 0, ByteArray(0), 0xffffffffL)),
                outputs = listOf(TxOut(1000L, byteArrayOf(0x00, 0x14) + ByteArray(20) { 0x22 })),
                locktime = 0,
            )
            return Psbt(tx, PsbtMap(emptyList()), listOf(inputMap), listOf(PsbtMap(emptyList())))
        }

        /** Splits a BIP144-serialized witness stack back into its items. All items here are < 253 bytes. */
        private fun parseWitnessStack(witness: ByteArray): List<ByteArray> {
            val items = mutableListOf<ByteArray>()
            var offset = 0
            val count = witness[offset].toInt() and 0xFF
            offset += 1
            repeat(count) {
                val len = witness[offset].toInt() and 0xFF
                offset += 1
                items.add(witness.copyOfRange(offset, offset + len))
                offset += len
            }
            return items
        }
    }

    @Test
    fun `Signatures are ordered by witness script, not by partial_sig insertion order`() {
        val witnessScript = multisigScript(2, listOf(PUBKEY_A, PUBKEY_B))
        val inputMap = inputMapWith(witnessScript, listOf(PUBKEY_B to SIG_B, PUBKEY_A to SIG_A))
        val psbt = psbtWith(inputMap)
        val finalized = finalizePsbt(psbt)
        val witness = finalized.inputs[0].finalScriptWitness()
        assertNotNull(witness)
        val items = parseWitnessStack(witness!!)
        assertEquals(4, items.size)
        assertEquals(0, items[0].size)
        assertEquals(SIG_A.toHex(), items[1].toHex())
        assertEquals(SIG_B.toHex(), items[2].toHex())
        assertEquals(witnessScript.toHex(), items[3].toHex())
    }

    @Test
    fun `Exactly threshold signatures are used when more are available`() {
        val witnessScript = multisigScript(2, listOf(PUBKEY_A, PUBKEY_B, PUBKEY_C))
        val inputMap = inputMapWith(witnessScript, listOf(PUBKEY_A to SIG_A, PUBKEY_B to SIG_B, PUBKEY_C to SIG_C))
        val psbt = psbtWith(inputMap)
        val finalized = finalizePsbt(psbt)
        val witness = finalized.inputs[0].finalScriptWitness()
        assertNotNull(witness)
        val items = parseWitnessStack(witness!!)
        assertEquals(4, items.size)
        assertEquals(0, items[0].size)
        assertEquals(SIG_A.toHex(), items[1].toHex())
        assertEquals(SIG_B.toHex(), items[2].toHex())
        assertEquals(witnessScript.toHex(), items[3].toHex())
        assertFalse(items.any { it.toHex() == SIG_C.toHex() })
    }

    @Test
    fun `The threshold signatures chosen are the first ones in script order even when the available subset skips a key`() {
        val witnessScript = multisigScript(2, listOf(PUBKEY_A, PUBKEY_B, PUBKEY_C))
        val inputMap = inputMapWith(witnessScript, listOf(PUBKEY_C to SIG_C, PUBKEY_A to SIG_A))
        val psbt = psbtWith(inputMap)
        val finalized = finalizePsbt(psbt)
        val witness = finalized.inputs[0].finalScriptWitness()
        assertNotNull(witness)
        val items = parseWitnessStack(witness!!)
        assertEquals(4, items.size)
        assertEquals(0, items[0].size)
        assertEquals(SIG_A.toHex(), items[1].toHex())
        assertEquals(SIG_C.toHex(), items[2].toHex())
        assertEquals(witnessScript.toHex(), items[3].toHex())
    }

    @Test
    fun `Too few signatures leaves the input completely unchanged`() {
        val witnessScript = multisigScript(2, listOf(PUBKEY_A, PUBKEY_B, PUBKEY_C))
        val inputMap = inputMapWith(witnessScript, listOf(PUBKEY_B to SIG_B))
        val psbt = psbtWith(inputMap)
        val finalized = finalizePsbt(psbt)
        val witness = finalized.inputs[0].finalScriptWitness()
        assertNull(witness)
        assertEquals(inputMap.entries.size, finalized.inputs[0].entries.size)
    }

    @Test
    fun `A 1-of-2 finalizes with exactly one signature`() {
        val witnessScript = multisigScript(1, listOf(PUBKEY_A, PUBKEY_B))
        val inputMap = inputMapWith(witnessScript, listOf(PUBKEY_A to SIG_A, PUBKEY_B to SIG_B))
        val psbt = psbtWith(inputMap)
        val finalized = finalizePsbt(psbt)
        val witness = finalized.inputs[0].finalScriptWitness()
        assertNotNull(witness)
        val items = parseWitnessStack(witness!!)
        assertEquals(3, items.size)
        assertEquals(0, items[0].size)
        assertEquals(SIG_A.toHex(), items[1].toHex())
        assertEquals(witnessScript.toHex(), items[2].toHex())
    }
}
