package org.mega.entropycore

import org.junit.Assert.assertEquals
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
        // Three real keypairs (private key + its derived compressed secp256k1
        // pubkey) so signatures attached to these pubkeys are cryptographically
        // valid — finalizePsbt now verifies every candidate signature, so
        // fixtures need real ones rather than arbitrary placeholder bytes.
        private val PRIVATE_KEY_A = ByteArray(32) { 0x01 }
        private val PRIVATE_KEY_B = ByteArray(32) { 0x02 }
        private val PRIVATE_KEY_C = ByteArray(32) { 0x03 }
        private val PUBKEY_A = Secp256k1.publicKeyFromPrivateKey(PRIVATE_KEY_A)
        private val PUBKEY_B = Secp256k1.publicKeyFromPrivateKey(PRIVATE_KEY_B)
        private val PUBKEY_C = Secp256k1.publicKeyFromPrivateKey(PRIVATE_KEY_C)
        private const val AMOUNT = 199909013L

        private val UNSIGNED_TX = Transaction(
            version = 2,
            inputs = listOf(TxIn(ByteArray(32) { 0x11 }, 0, ByteArray(0), 0xffffffffL)),
            outputs = listOf(TxOut(1000L, byteArrayOf(0x00, 0x14) + ByteArray(20) { 0x22 })),
            locktime = 0,
        )

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

        /** A real, cryptographically valid partial_sig for [privateKey] over [witnessScript] at input 0. */
        private fun realSig(privateKey: ByteArray, witnessScript: ByteArray): ByteArray {
            val scriptCode = writeCompactSize(witnessScript.size.toLong()) + witnessScript
            val sighash = computeSegwitSighash(UNSIGNED_TX, 0, scriptCode, AMOUNT, 1)
            return signEcdsaDer(privateKey, sighash) + byteArrayOf(0x01)
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
            return Psbt(UNSIGNED_TX, PsbtMap(emptyList()), listOf(inputMap), listOf(PsbtMap(emptyList())))
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
        val sigA = realSig(PRIVATE_KEY_A, witnessScript)
        val sigB = realSig(PRIVATE_KEY_B, witnessScript)
        val inputMap = inputMapWith(witnessScript, listOf(PUBKEY_B to sigB, PUBKEY_A to sigA))
        val psbt = psbtWith(inputMap)
        val finalized = finalizePsbt(psbt)
        val witness = finalized.inputs[0].finalScriptWitness()
        assertNotNull(witness)
        val items = parseWitnessStack(witness!!)
        assertEquals(4, items.size)
        assertEquals(0, items[0].size)
        assertEquals(sigA.toHex(), items[1].toHex())
        assertEquals(sigB.toHex(), items[2].toHex())
        assertEquals(witnessScript.toHex(), items[3].toHex())
    }

    @Test
    fun `Exactly threshold signatures are used when more are available`() {
        val witnessScript = multisigScript(2, listOf(PUBKEY_A, PUBKEY_B, PUBKEY_C))
        val sigA = realSig(PRIVATE_KEY_A, witnessScript)
        val sigB = realSig(PRIVATE_KEY_B, witnessScript)
        val sigC = realSig(PRIVATE_KEY_C, witnessScript)
        val inputMap = inputMapWith(witnessScript, listOf(PUBKEY_A to sigA, PUBKEY_B to sigB, PUBKEY_C to sigC))
        val psbt = psbtWith(inputMap)
        val finalized = finalizePsbt(psbt)
        val witness = finalized.inputs[0].finalScriptWitness()
        assertNotNull(witness)
        val items = parseWitnessStack(witness!!)
        assertEquals(4, items.size)
        assertEquals(0, items[0].size)
        assertEquals(sigA.toHex(), items[1].toHex())
        assertEquals(sigB.toHex(), items[2].toHex())
        assertEquals(witnessScript.toHex(), items[3].toHex())
        assertEquals(0, items.count { it.toHex() == sigC.toHex() })
    }

    @Test
    fun `The threshold signatures chosen are the first ones in script order even when the available subset skips a key`() {
        val witnessScript = multisigScript(2, listOf(PUBKEY_A, PUBKEY_B, PUBKEY_C))
        val sigA = realSig(PRIVATE_KEY_A, witnessScript)
        val sigC = realSig(PRIVATE_KEY_C, witnessScript)
        val inputMap = inputMapWith(witnessScript, listOf(PUBKEY_C to sigC, PUBKEY_A to sigA))
        val psbt = psbtWith(inputMap)
        val finalized = finalizePsbt(psbt)
        val witness = finalized.inputs[0].finalScriptWitness()
        assertNotNull(witness)
        val items = parseWitnessStack(witness!!)
        assertEquals(4, items.size)
        assertEquals(0, items[0].size)
        assertEquals(sigA.toHex(), items[1].toHex())
        assertEquals(sigC.toHex(), items[2].toHex())
        assertEquals(witnessScript.toHex(), items[3].toHex())
    }

    @Test
    fun `Too few signatures leaves the input completely unchanged`() {
        val witnessScript = multisigScript(2, listOf(PUBKEY_A, PUBKEY_B, PUBKEY_C))
        val sigB = realSig(PRIVATE_KEY_B, witnessScript)
        val inputMap = inputMapWith(witnessScript, listOf(PUBKEY_B to sigB))
        val psbt = psbtWith(inputMap)
        val finalized = finalizePsbt(psbt)
        val witness = finalized.inputs[0].finalScriptWitness()
        assertNull(witness)
        assertEquals(inputMap.entries.size, finalized.inputs[0].entries.size)
    }

    @Test
    fun `A 1-of-2 finalizes with exactly one signature`() {
        val witnessScript = multisigScript(1, listOf(PUBKEY_A, PUBKEY_B))
        val sigA = realSig(PRIVATE_KEY_A, witnessScript)
        val sigB = realSig(PRIVATE_KEY_B, witnessScript)
        val inputMap = inputMapWith(witnessScript, listOf(PUBKEY_A to sigA, PUBKEY_B to sigB))
        val psbt = psbtWith(inputMap)
        val finalized = finalizePsbt(psbt)
        val witness = finalized.inputs[0].finalScriptWitness()
        assertNotNull(witness)
        val items = parseWitnessStack(witness!!)
        assertEquals(3, items.size)
        assertEquals(0, items[0].size)
        assertEquals(sigA.toHex(), items[1].toHex())
        assertEquals(witnessScript.toHex(), items[2].toHex())
    }
}
