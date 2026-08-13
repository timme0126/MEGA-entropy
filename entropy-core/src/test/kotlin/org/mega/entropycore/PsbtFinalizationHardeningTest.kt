package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the v0.1.9 audit hardening of finalizePsbt:
 * a witness script that isn't exactly the OP_M <keys> OP_N OP_CHECKMULTISIG
 * template is left UN-finalized (the old lenient parse could finalize an
 * input with ZERO signatures for a script starting with a byte <= 0x50),
 * the witness script must match the spent UTXO's scriptPubKey, and a P2WPKH
 * partial_sig whose pubkey doesn't match the UTXO's program never finalizes.
 */
class PsbtFinalizationHardeningTest {

    companion object {
        private val PUBKEY_A = "03b1341ccba7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd46".hexToBytes()
        private val PUBKEY_B = "03de55d1e1dac805e3f8a58c1fbf9b94c02f3dbaafe127fefca4995f26f82083bd".hexToBytes()
        private val WITNESS_SCRIPT = "522103b1341ccba7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd462103de55d1e1dac805e3f8a58c1fbf9b94c02f3dbaafe127fefca4995f26f82083bd52ae".hexToBytes()
        private val SCRIPT_PUBKEY = "0020771fd18ad459666dd49f3d564e3dbc42f4c84774e360ada16816a8ed488d5681".hexToBytes()
        private const val AMOUNT = 199909013L
        private val DUMMY_SIG_A = byteArrayOf(0x30, 0x44, 0x01) + ByteArray(68) { 0x11 } + byteArrayOf(0x01)
        private val DUMMY_SIG_B = byteArrayOf(0x30, 0x44, 0x01) + ByteArray(68) { 0x22 } + byteArrayOf(0x01)

        private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
        private fun Long.toUInt64LE(): ByteArray = byteArrayOf(
            (this and 0xFF).toByte(), ((this shr 8) and 0xFF).toByte(),
            ((this shr 16) and 0xFF).toByte(), ((this shr 24) and 0xFF).toByte(),
            ((this shr 32) and 0xFF).toByte(), ((this shr 40) and 0xFF).toByte(),
            ((this shr 48) and 0xFF).toByte(), ((this shr 56) and 0xFF).toByte(),
        )

        private fun witnessUtxoEntry(scriptPubKey: ByteArray): PsbtKeyValue {
            val spk = scriptPubKey
            return PsbtKeyValue(0x01, ByteArray(0), AMOUNT.toUInt64LE() + byteArrayOf(spk.size.toByte()) + spk)
        }

        private fun inputMapWith(
            script: ByteArray?,
            sigs: List<Pair<ByteArray, ByteArray>>,
            scriptPubKey: ByteArray = SCRIPT_PUBKEY,
        ): PsbtMap {
            val entries = mutableListOf(witnessUtxoEntry(scriptPubKey))
            if (script != null) entries += PsbtKeyValue(0x05, ByteArray(0), script)
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
    }

    @Test
    fun `a well-formed 2-of-2 with both signatures finalizes exactly as before`() {
        val psbt = psbtWith(inputMapWith(WITNESS_SCRIPT, listOf(PUBKEY_A to DUMMY_SIG_A, PUBKEY_B to DUMMY_SIG_B)))
        val finalized = finalizePsbt(psbt)
        assertNotNull(finalized.inputs[0].finalScriptWitness())
    }

    @Test
    fun `a script whose first byte is OP_0 does NOT finalize with zero signatures`() {
        // The old template parse computed threshold = 0x00 - 0x50 (negative),
        // so zero signatures "satisfied" it and the input finalized into an
        // invalid witness. Now it must stay unfinalized.
        val garbageScript = byteArrayOf(0x00) + WITNESS_SCRIPT.copyOfRange(1, WITNESS_SCRIPT.size)
        val psbt = psbtWith(inputMapWith(garbageScript, emptyList()))
        val finalized = finalizePsbt(psbt)
        assertNull(finalized.inputs[0].finalScriptWitness())
    }

    @Test
    fun `a script with a truncated pubkey push does NOT finalize and does NOT throw`() {
        val truncated = WITNESS_SCRIPT.copyOfRange(0, 40) // ends mid-pubkey
        val psbt = psbtWith(inputMapWith(truncated, listOf(PUBKEY_A to DUMMY_SIG_A, PUBKEY_B to DUMMY_SIG_B)))
        val finalized = finalizePsbt(psbt) // must not throw IndexOutOfBounds
        assertNull(finalized.inputs[0].finalScriptWitness())
    }

    @Test
    fun `a script missing OP_CHECKMULTISIG does NOT finalize`() {
        val noCms = WITNESS_SCRIPT.copyOf()
        noCms[noCms.size - 1] = 0x51.toByte()
        val psbt = psbtWith(inputMapWith(noCms, listOf(PUBKEY_A to DUMMY_SIG_A, PUBKEY_B to DUMMY_SIG_B)))
        assertNull(finalizePsbt(psbt).inputs[0].finalScriptWitness())
    }

    @Test
    fun `a script whose key-count opcode disagrees with its pushes does NOT finalize`() {
        // Claim OP_3 keys but only push 2.
        val wrongCount = WITNESS_SCRIPT.copyOf()
        wrongCount[wrongCount.size - 2] = 0x53.toByte()
        val psbt = psbtWith(inputMapWith(wrongCount, listOf(PUBKEY_A to DUMMY_SIG_A, PUBKEY_B to DUMMY_SIG_B)))
        assertNull(finalizePsbt(psbt).inputs[0].finalScriptWitness())
    }

    @Test
    fun `a witnessScript the UTXO does not commit to does NOT finalize`() {
        val unrelatedScriptPubKey = "002031cac084d8f475b03993ffed425ddd0d2fd31b0bcd4395ee0d57ed42ead8ecdd".hexToBytes()
        val psbt = psbtWith(
            inputMapWith(
                WITNESS_SCRIPT,
                listOf(PUBKEY_A to DUMMY_SIG_A, PUBKEY_B to DUMMY_SIG_B),
                scriptPubKey = unrelatedScriptPubKey,
            ),
        )
        assertNull(finalizePsbt(psbt).inputs[0].finalScriptWitness())
    }

    @Test
    fun `a P2WPKH partial sig for the wrong pubkey does NOT finalize`() {
        val p2wpkhProgram = "0014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2".hexToBytes()
        // Sig is attributed to PUBKEY_A, whose hash160 is NOT the UTXO's program.
        val psbt = psbtWith(inputMapWith(null, listOf(PUBKEY_A to DUMMY_SIG_A), scriptPubKey = p2wpkhProgram))
        assertNull(finalizePsbt(psbt).inputs[0].finalScriptWitness())
    }

    @Test
    fun `a P2WPKH partial sig for the right pubkey still finalizes`() {
        // hash160(PUBKEY at m/84'/0'/0'/0/0 of the standard test mnemonic) —
        // c0cebcd6... is PsbtSigningP2wpkhTest's fixture program.
        val pubkey = "0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c".hexToBytes()
        val p2wpkhProgram = "0014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2".hexToBytes()
        val psbt = psbtWith(inputMapWith(null, listOf(pubkey to DUMMY_SIG_A), scriptPubKey = p2wpkhProgram))
        val witness = finalizePsbt(psbt).inputs[0].finalScriptWitness()
        assertNotNull(witness)
        // witness = 02 <len sig> <sig> <len pubkey> <pubkey>
        assertEquals("02", witness!!.copyOfRange(0, 1).toHex())
    }
}
