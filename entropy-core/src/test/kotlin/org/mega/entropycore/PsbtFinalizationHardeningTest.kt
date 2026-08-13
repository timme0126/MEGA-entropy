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
        private val PRIVATE_KEY_A = ByteArray(32) { 0x01 }
        private val PRIVATE_KEY_B = ByteArray(32) { 0x02 }
        private val PRIVATE_KEY_P2WPKH = ByteArray(32) { 0x03 }
        private val PUBKEY_A = Secp256k1.publicKeyFromPrivateKey(PRIVATE_KEY_A)
        private val PUBKEY_B = Secp256k1.publicKeyFromPrivateKey(PRIVATE_KEY_B)
        private val WITNESS_SCRIPT = multisigScript(2, listOf(PUBKEY_A, PUBKEY_B))
        private val SCRIPT_PUBKEY = byteArrayOf(0x00, 0x20) + sha256(WITNESS_SCRIPT)
        private const val AMOUNT = 199909013L

        private val UNSIGNED_TX = Transaction(
            version = 2,
            inputs = listOf(TxIn(ByteArray(32) { 0x11 }, 0, ByteArray(0), 0xffffffffL)),
            outputs = listOf(TxOut(1000L, byteArrayOf(0x00, 0x14) + ByteArray(20) { 0x22 })),
            locktime = 0,
        )

        // Real, cryptographically valid partial_sigs for PUBKEY_A/PUBKEY_B over
        // WITNESS_SCRIPT above — finalizePsbt now verifies every candidate
        // signature instead of trusting whatever bytes are attached to a matching
        // pubkey, so a fixture claiming "both signatures finalize" needs sigs that
        // actually verify. Reused as-is by the garbage-script tests below, where
        // parsing fails before signature verification is ever reached, so their
        // exact content doesn't matter there.
        private val DUMMY_SIG_A = realMultisigSig(PRIVATE_KEY_A)
        private val DUMMY_SIG_B = realMultisigSig(PRIVATE_KEY_B)

        private fun multisigScript(threshold: Int, pubkeys: List<ByteArray>): ByteArray {
            var out = byteArrayOf((0x50 + threshold).toByte())
            for (pk in pubkeys) out += byteArrayOf(0x21) + pk
            out += byteArrayOf((0x50 + pubkeys.size).toByte(), 0xAE.toByte())
            return out
        }

        private fun realMultisigSig(privateKey: ByteArray): ByteArray {
            val scriptCode = writeCompactSize(WITNESS_SCRIPT.size.toLong()) + WITNESS_SCRIPT
            val sighash = computeSegwitSighash(UNSIGNED_TX, 0, scriptCode, AMOUNT, 1)
            return signEcdsaDer(privateKey, sighash) + byteArrayOf(0x01)
        }

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
            return Psbt(UNSIGNED_TX, PsbtMap(emptyList()), listOf(inputMap), listOf(PsbtMap(emptyList())))
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
        val unrelatedScriptPubKey = "002031cac084d8f475b03993ffed425ddd0d2fd31b0bcd4395ee0d57ed42ead8ecdd"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
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
        val p2wpkhProgram = "0014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        // Sig is attributed to PUBKEY_A, whose hash160 is NOT the UTXO's program.
        val psbt = psbtWith(inputMapWith(null, listOf(PUBKEY_A to DUMMY_SIG_A), scriptPubKey = p2wpkhProgram))
        assertNull(finalizePsbt(psbt).inputs[0].finalScriptWitness())
    }

    @Test
    fun `a P2WPKH partial sig for the right pubkey still finalizes`() {
        val pubkey = Secp256k1.publicKeyFromPrivateKey(PRIVATE_KEY_P2WPKH)
        val p2wpkhProgram = byteArrayOf(0x00, 0x14) + hash160(pubkey)
        val scriptCode = byteArrayOf(0x19, 0x76.toByte(), 0xa9.toByte(), 0x14) +
            hash160(pubkey) + byteArrayOf(0x88.toByte(), 0xac.toByte())
        val sighash = computeSegwitSighash(UNSIGNED_TX, 0, scriptCode, AMOUNT, 1)
        val sig = signEcdsaDer(PRIVATE_KEY_P2WPKH, sighash) + byteArrayOf(0x01)
        val psbt = psbtWith(inputMapWith(null, listOf(pubkey to sig), scriptPubKey = p2wpkhProgram))
        val witness = finalizePsbt(psbt).inputs[0].finalScriptWitness()
        assertNotNull(witness)
        // witness = 02 <len sig> <sig> <len pubkey> <pubkey>
        assertEquals("02", witness!!.copyOfRange(0, 1).toHex())
    }
}
