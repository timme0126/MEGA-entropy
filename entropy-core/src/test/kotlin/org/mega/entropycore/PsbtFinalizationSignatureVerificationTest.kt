package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Regression tests for finding #5 — finalizePsbt (via isValidPartialSig)
 * cryptographically verifies every candidate partial_sig before counting it
 * toward a threshold or emitting it in finalScriptWitness. Malformed DER,
 * high-S, wrong-pubkey, and wrong-sighash-byte signatures must never count,
 * even when enough of them are PRESENT to superficially satisfy a
 * threshold — only cryptographically valid ones may.
 */
class PsbtFinalizationSignatureVerificationTest {

    companion object {
        private val PRIVATE_KEY_A = ByteArray(32) { 0x01 }
        private val PRIVATE_KEY_B = ByteArray(32) { 0x02 }
        private val PRIVATE_KEY_C = ByteArray(32) { 0x03 }
        private val PUBKEY_A = Secp256k1.publicKeyFromPrivateKey(PRIVATE_KEY_A)
        private val PUBKEY_B = Secp256k1.publicKeyFromPrivateKey(PRIVATE_KEY_B)
        private val PUBKEY_C = Secp256k1.publicKeyFromPrivateKey(PRIVATE_KEY_C)
        private const val AMOUNT = 250_000L
        private val CURVE_N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

        private val UNSIGNED_TX = Transaction(
            version = 2,
            inputs = listOf(TxIn(ByteArray(32) { 0x11 }, 0, ByteArray(0), 0xffffffffL)),
            outputs = listOf(TxOut(1000L, byteArrayOf(0x00, 0x14) + ByteArray(20) { 0x22 })),
            locktime = 0,
        )

        private fun multisigScript(threshold: Int, pubkeys: List<ByteArray>): ByteArray {
            var out = byteArrayOf((0x50 + threshold).toByte())
            for (pk in pubkeys) out += byteArrayOf(0x21) + pk
            out += byteArrayOf((0x50 + pubkeys.size).toByte(), 0xAE.toByte())
            return out
        }

        private fun scriptCodeFor(witnessScript: ByteArray) = writeCompactSize(witnessScript.size.toLong()) + witnessScript

        private fun sighashFor(witnessScript: ByteArray) =
            computeSegwitSighash(UNSIGNED_TX, 0, scriptCodeFor(witnessScript), AMOUNT, 1)

        private fun realSig(privateKey: ByteArray, witnessScript: ByteArray): ByteArray =
            signEcdsaDer(privateKey, sighashFor(witnessScript)) + byteArrayOf(0x01)

        private fun Long.toUInt64LE(): ByteArray = byteArrayOf(
            (this and 0xFF).toByte(), ((this shr 8) and 0xFF).toByte(),
            ((this shr 16) and 0xFF).toByte(), ((this shr 24) and 0xFF).toByte(),
            ((this shr 32) and 0xFF).toByte(), ((this shr 40) and 0xFF).toByte(),
            ((this shr 48) and 0xFF).toByte(), ((this shr 56) and 0xFF).toByte(),
        )

        private fun p2wshScriptPubKey(witnessScript: ByteArray) = byteArrayOf(0x00, 0x20) + sha256(witnessScript)

        private fun witnessUtxoEntry(scriptPubKey: ByteArray) =
            PsbtKeyValue(0x01, ByteArray(0), AMOUNT.toUInt64LE() + byteArrayOf(scriptPubKey.size.toByte()) + scriptPubKey)

        private fun psbtWithMultisigInput(witnessScript: ByteArray, sigs: List<Pair<ByteArray, ByteArray>>): Psbt {
            val entries = mutableListOf(witnessUtxoEntry(p2wshScriptPubKey(witnessScript)))
            entries += PsbtKeyValue(0x05, ByteArray(0), witnessScript)
            sigs.forEach { (pubkey, sig) -> entries += PsbtKeyValue(0x02, pubkey, sig) }
            return Psbt(UNSIGNED_TX, PsbtMap(emptyList()), listOf(PsbtMap(entries)), listOf(PsbtMap(emptyList())))
        }

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

        private fun derInt(value: BigInteger): ByteArray {
            val bytes = value.toByteArray() // BigInteger's own encoding already matches DER INTEGER's minimal form
            return byteArrayOf(0x02, bytes.size.toByte()) + bytes
        }
    }

    // --- isValidPartialSig direct unit coverage ---

    @Test
    fun `isValidPartialSig accepts a genuinely valid signature`() {
        val witnessScript = multisigScript(2, listOf(PUBKEY_A, PUBKEY_B))
        val sig = realSig(PRIVATE_KEY_A, witnessScript)
        assertTrue(isValidPartialSig(UNSIGNED_TX, 0, scriptCodeFor(witnessScript), AMOUNT, PUBKEY_A, sig))
    }

    @Test
    fun `isValidPartialSig rejects malformed DER bytes`() {
        val witnessScript = multisigScript(2, listOf(PUBKEY_A, PUBKEY_B))
        val garbage = byteArrayOf(0x30, 0x44, 0x01) + ByteArray(68) { 0x11 } + byteArrayOf(0x01)
        assertFalse(isValidPartialSig(UNSIGNED_TX, 0, scriptCodeFor(witnessScript), AMOUNT, PUBKEY_A, garbage))
    }

    @Test
    fun `isValidPartialSig rejects a high-S signature`() {
        // (r, s) and (r, N-s) are both mathematically valid ECDSA signatures for
        // the same message/key — BIP62/BIP146 canonicalize on low-S specifically
        // so a signer can't be handed two different "valid" signatures for the
        // same intent. isValidPartialSig must reject the high-S form.
        val witnessScript = multisigScript(2, listOf(PUBKEY_A, PUBKEY_B))
        val raw = signEcdsaRaw(PRIVATE_KEY_A, sighashFor(witnessScript))
        val highS = CURVE_N - BigInteger(1, raw.s)
        val content = derInt(BigInteger(1, raw.r)) + derInt(highS)
        val highSDer = byteArrayOf(0x30, content.size.toByte()) + content
        val sigWithSighashByte = highSDer + byteArrayOf(0x01)
        assertFalse(isValidPartialSig(UNSIGNED_TX, 0, scriptCodeFor(witnessScript), AMOUNT, PUBKEY_A, sigWithSighashByte))
    }

    @Test
    fun `isValidPartialSig rejects a signature verified against the wrong pubkey`() {
        val witnessScript = multisigScript(2, listOf(PUBKEY_A, PUBKEY_B))
        val sigForA = realSig(PRIVATE_KEY_A, witnessScript)
        assertFalse(isValidPartialSig(UNSIGNED_TX, 0, scriptCodeFor(witnessScript), AMOUNT, PUBKEY_B, sigForA))
    }

    @Test
    fun `isValidPartialSig rejects a non-SIGHASH_ALL sighash byte even with an otherwise-valid signature`() {
        val witnessScript = multisigScript(2, listOf(PUBKEY_A, PUBKEY_B))
        val sig = realSig(PRIVATE_KEY_A, witnessScript)
        val relabeledAsSighashNone = sig.copyOfRange(0, sig.size - 1) + byteArrayOf(0x02)
        assertFalse(isValidPartialSig(UNSIGNED_TX, 0, scriptCodeFor(witnessScript), AMOUNT, PUBKEY_A, relabeledAsSighashNone))
    }

    @Test
    fun `isValidPartialSig rejects an empty signature without throwing`() {
        val witnessScript = multisigScript(2, listOf(PUBKEY_A, PUBKEY_B))
        assertFalse(isValidPartialSig(UNSIGNED_TX, 0, scriptCodeFor(witnessScript), AMOUNT, PUBKEY_A, ByteArray(0)))
    }

    // --- End-to-end: finalizePsbt only counts cryptographically valid signatures ---

    @Test
    fun `a 2-of-3 finalizes using only the valid signatures when one candidate is garbage`() {
        val witnessScript = multisigScript(2, listOf(PUBKEY_A, PUBKEY_B, PUBKEY_C))
        val sigA = realSig(PRIVATE_KEY_A, witnessScript)
        val sigC = realSig(PRIVATE_KEY_C, witnessScript)
        val garbageForB = byteArrayOf(0x30, 0x44, 0x01) + ByteArray(68) { 0x22 } + byteArrayOf(0x01)
        val psbt = psbtWithMultisigInput(witnessScript, listOf(PUBKEY_A to sigA, PUBKEY_B to garbageForB, PUBKEY_C to sigC))
        val witness = finalizePsbt(psbt).inputs[0].finalScriptWitness()
        assertNotNull(witness)
        val items = parseWitnessStack(witness!!)
        assertEquals(4, items.size) // OP_0 placeholder + 2 sigs + witnessScript
        val chosenSigs = items.drop(1).dropLast(1)
        assertTrue(chosenSigs.any { it.contentEquals(sigA) })
        assertTrue(chosenSigs.any { it.contentEquals(sigC) })
        assertTrue(items.none { it.contentEquals(garbageForB) })
    }

    @Test
    fun `a 2-of-3 does NOT finalize when only one of three attached candidates is actually valid`() {
        // Three partial_sig entries are PRESENT — a naive count-based check
        // would see 3 >= threshold 2 and finalize — but only one is
        // cryptographically valid, so finalization must stay closed.
        val witnessScript = multisigScript(2, listOf(PUBKEY_A, PUBKEY_B, PUBKEY_C))
        val sigA = realSig(PRIVATE_KEY_A, witnessScript)
        val garbageForB = byteArrayOf(0x30, 0x44, 0x01) + ByteArray(68) { 0x33 } + byteArrayOf(0x01)
        val garbageForC = byteArrayOf(0x30, 0x44, 0x01) + ByteArray(68) { 0x44 } + byteArrayOf(0x01)
        val psbt = psbtWithMultisigInput(
            witnessScript,
            listOf(PUBKEY_A to sigA, PUBKEY_B to garbageForB, PUBKEY_C to garbageForC),
        )
        assertNull(finalizePsbt(psbt).inputs[0].finalScriptWitness())
    }

    @Test
    fun `a 2-of-3 does NOT finalize when a real signature is attributed to the wrong pubkey slot`() {
        val witnessScript = multisigScript(2, listOf(PUBKEY_A, PUBKEY_B, PUBKEY_C))
        val sigA = realSig(PRIVATE_KEY_A, witnessScript)
        // sigA is only valid FOR PUBKEY_A — reusing it under PUBKEY_B's slot must not count as B's signature.
        val psbt = psbtWithMultisigInput(witnessScript, listOf(PUBKEY_A to sigA, PUBKEY_B to sigA))
        assertNull(finalizePsbt(psbt).inputs[0].finalScriptWitness())
    }
}
