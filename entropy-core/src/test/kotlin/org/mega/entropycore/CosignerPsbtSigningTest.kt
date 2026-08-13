package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CosignerPsbtSigningTest {
    companion object {
        // A standard, widely-used BIP39 test mnemonic. Its master fingerprint is
        // independently known to be 73c5da0a (used elsewhere in this test suite,
        // e.g. PsbtSigningTest.kt / PsbtWorkflowTest.kt) — assert this exact
        // value once as a sanity check that the fixture is wired correctly.
        private val TEST_WORDS_A = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        private const val EXPECTED_FINGERPRINT_A = "73c5da0a"

        // A second, DIFFERENT standard BIP39 test mnemonic — its fingerprint is
        // NOT hardcoded anywhere; it's computed at runtime via masterKeyFingerprint,
        // so the fixture never depends on an unverified external "expected" value.
        private val TEST_WORDS_B = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote".split(" ")

        // BIP48 P2WSH path 48'/0'/0'/2'/0/0, raw uint32 elements (hardened bit
        // 0x80000000 already included where applicable) — same shape used by
        // PsbtSigningTest.kt's own DERIVATION_PATH constant.
        private val DERIVATION_PATH = listOf(2147483696L, 2147483648L, 2147483648L, 2147483650L, 0L, 0L)

        private const val WITNESS_UTXO_AMOUNT = 199909013L
        // Same real single-input/single-output unsigned tx PsbtTest.kt / PsbtSigningTest.kt / PsbtWorkflowTest.kt already reuse.
        private const val UNSIGNED_TX_HEX = "0200000001279a2323a5dfb51fc45f220fa58b0fc13e1e3342792a85d7e36cd6333b5cbc390000000000ffffffff01a05aea0b000000001976a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac00000000"

        private fun masterKeyFor(words: List<String>): Bip32ExtendedPrivateKey =
            bip32MasterKeyFromSeed(deriveSeed(words, "").bytes)

        // Walks DERIVATION_PATH from a master key exactly the way PsbtSigning.kt's
        // own signPsbt does internally, to reach the same child level a real
        // vault's cosigner key would be derived at.
        private fun childKeyFor(master: Bip32ExtendedPrivateKey): Bip32ExtendedPrivateKey {
            var child = master
            for (rawIndex in DERIVATION_PATH) {
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
        private fun shortCompactSize(len: Int): ByteArray { require(len < 0xFD); return byteArrayOf(len.toByte()) }

        private fun bip32DerivationValue(fingerprint: ByteArray): ByteArray {
            val pathBytes = DERIVATION_PATH.fold(ByteArray(0)) { acc, element -> acc + element.toUInt32LE() }
            return fingerprint + pathBytes
        }

        // Builds a fresh, real 2-of-2 P2WSH multisig PSBT with ONE input carrying
        // BOTH cosigners' bip32_derivation entries (this is exactly what a real
        // wallet exporting a PSBT for a saved 2-of-2 vault would produce) and no
        // signatures yet. Called fresh per test (no shared mutable state).
        private fun buildTwoCosignerPsbtBytes(): ByteArray {
            val masterA = masterKeyFor(TEST_WORDS_A)
            val masterB = masterKeyFor(TEST_WORDS_B)
            val pubkeyA = childKeyFor(masterA).compressedPublicKey()
            val pubkeyB = childKeyFor(masterB).compressedPublicKey()
            val sortedPubkeys = sortPublicKeysBip67(listOf(pubkeyA, pubkeyB))
            val witnessScript = buildMultisigWitnessScript(threshold = 2, sortedPublicKeys = sortedPubkeys)
            val scriptPubKey = byteArrayOf(0x00, 0x20) + sha256(witnessScript)   // P2WSH: OP_0 <32-byte-hash>

            val witnessUtxoValue = WITNESS_UTXO_AMOUNT.toUInt64LE() + shortCompactSize(scriptPubKey.size) + scriptPubKey
            val inputMap = PsbtMap(
                entries = listOf(
                    PsbtKeyValue(keyType = 0x01, keyData = ByteArray(0), value = witnessUtxoValue),
                    PsbtKeyValue(keyType = 0x05, keyData = ByteArray(0), value = witnessScript),
                    PsbtKeyValue(keyType = 0x06, keyData = pubkeyA, value = bip32DerivationValue(masterA.fingerprint())),
                    PsbtKeyValue(keyType = 0x06, keyData = pubkeyB, value = bip32DerivationValue(masterB.fingerprint())),
                ),
            )
            val unsignedTx = parseTransaction(UNSIGNED_TX_HEX.hexToBytes())
            val psbt = Psbt(
                unsignedTx = unsignedTx,
                // PSBT_GLOBAL_UNSIGNED_TX (keyType 0x00) must be present in the
                // wire format for parsePsbt to read it back — serializePsbt only
                // writes psbt.global.entries as given, it does not inject this
                // automatically from psbt.unsignedTx.
                global = PsbtMap(listOf(PsbtKeyValue(keyType = 0x00, keyData = ByteArray(0), value = serializeTransaction(unsignedTx)))),
                inputs = listOf(inputMap),
                outputs = unsignedTx.outputs.map { PsbtMap(emptyList()) },
            )
            return serializePsbt(psbt)
        }

        private fun String.hexToBytes(): ByteArray {
            require(length % 2 == 0)
            return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }

    @Test
    fun `Fixture sanity`() {
        assertEquals("73c5da0a", masterKeyFingerprint(TEST_WORDS_A, ""))
    }

    @Test
    fun `Rejects an unrelated unknown claimed fingerprint`() {
        val psbtBytes = buildTwoCosignerPsbtBytes()
        val result = signPsbtForCosigner(psbtBytes, "00000000", TEST_WORDS_A, "")
        assertTrue(result is SignForCosignerResult.FingerprintMismatch)
        val mismatch = result as SignForCosignerResult.FingerprintMismatch
        assertEquals("00000000", mismatch.expectedFingerprint)
        assertEquals(masterKeyFingerprint(TEST_WORDS_A, ""), mismatch.actualFingerprint)

        val parsed = parsePsbt(psbtBytes)
        assertTrue(parsed.inputs[0].partialSigs().isEmpty())
    }

    @Test
    fun `Wrong cosigner selection claims a real but different cosigner fingerprint`() {
        val psbtBytes = buildTwoCosignerPsbtBytes()
        val expectedB = masterKeyFingerprint(TEST_WORDS_B, "")
        val actualA = masterKeyFingerprint(TEST_WORDS_A, "")
        val result = signPsbtForCosigner(psbtBytes, expectedB, TEST_WORDS_A, "")
        assertTrue(result is SignForCosignerResult.FingerprintMismatch)
        val mismatch = result as SignForCosignerResult.FingerprintMismatch
        assertEquals(expectedB, mismatch.expectedFingerprint)
        assertEquals(actualA, mismatch.actualFingerprint)
    }

    @Test
    fun `Signs only the selected cosigner input derivation leaves vault partially signed`() {
        val psbtBytes = buildTwoCosignerPsbtBytes()
        val result = signPsbtForCosigner(psbtBytes, EXPECTED_FINGERPRINT_A, TEST_WORDS_A, "")
        assertTrue(result is SignForCosignerResult.Signed)
        val signedBytes = (result as SignForCosignerResult.Signed).psbtBytes
        val parsed = parsePsbt(signedBytes)
        val partialSigs = parsed.inputs[0].partialSigs()
        assertEquals(1, partialSigs.size)
        val masterA = masterKeyFor(TEST_WORDS_A)
        val pubkeyA = childKeyFor(masterA).compressedPublicKey()
        assertEquals(pubkeyA.toHex(), partialSigs[0].pubkey.toHex())
        assertFalse(isPsbtFullyFinalized(signedBytes))
    }

    @Test
    fun `Signing with second cosigner reaches threshold and finalizes preserving first signature`() {
        val psbtBytes = buildTwoCosignerPsbtBytes()
        val resultA = signPsbtForCosigner(psbtBytes, EXPECTED_FINGERPRINT_A, TEST_WORDS_A, "")
        assertTrue(resultA is SignForCosignerResult.Signed)
        val signedABytes = (resultA as SignForCosignerResult.Signed).psbtBytes
        val parsedA = parsePsbt(signedABytes)
        val sigA = parsedA.inputs[0].partialSigs()[0]
        val sigAHex = sigA.signature.toHex()

        val masterB = masterKeyFor(TEST_WORDS_B)
        val expectedB = masterB.fingerprint().toHex()
        val resultB = signPsbtForCosigner(signedABytes, expectedB, TEST_WORDS_B, "")
        assertTrue(resultB is SignForCosignerResult.Signed)
        val signedBothBytes = (resultB as SignForCosignerResult.Signed).psbtBytes
        val parsedBoth = parsePsbt(signedBothBytes)

        // Reaching the 2-of-2 threshold finalizes this input — per BIP174,
        // finalization clears partial_sig/bip32_derivation/witness_script
        // and replaces them with just PSBT_IN_FINAL_SCRIPTWITNESS, so
        // partialSigs() is legitimately empty here. "Cosigner A's signature
        // was preserved, not dropped or overwritten by B's" is instead
        // verified by confirming A's exact signature bytes (captured before
        // finalization, above) are actually present in the final witness
        // stack finalizePsbt assembled.
        assertTrue(isPsbtFullyFinalized(signedBothBytes))
        val finalWitness = parsedBoth.inputs[0].finalScriptWitness()
        assertNotNull(finalWitness)
        assertTrue(finalWitness!!.toHex().contains(sigAHex))
        assertNotNull(extractFinalTransactionHex(signedBothBytes))
    }

    @Test
    fun `Idempotent re signing no duplicate or conflicting signatures`() {
        val psbtBytes = buildTwoCosignerPsbtBytes()
        val resultA = signPsbtForCosigner(psbtBytes, EXPECTED_FINGERPRINT_A, TEST_WORDS_A, "")
        assertTrue(resultA is SignForCosignerResult.Signed)
        val signedABytes = (resultA as SignForCosignerResult.Signed).psbtBytes
        val parsedA = parsePsbt(signedABytes)
        val sigAHex = parsedA.inputs[0].partialSigs()[0].signature.toHex()

        val resultA2 = signPsbtForCosigner(signedABytes, EXPECTED_FINGERPRINT_A, TEST_WORDS_A, "")
        assertTrue(resultA2 is SignForCosignerResult.Signed)
        val signedA2Bytes = (resultA2 as SignForCosignerResult.Signed).psbtBytes
        val parsedA2 = parsePsbt(signedA2Bytes)
        val partialSigs = parsedA2.inputs[0].partialSigs()
        assertEquals(1, partialSigs.size)
        assertEquals(sigAHex, partialSigs[0].signature.toHex())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `Malformed PSBT bytes with matching fingerprint propagates real parse failure`() {
        signPsbtForCosigner(byteArrayOf(1, 2, 3), masterKeyFingerprint(TEST_WORDS_A, ""), TEST_WORDS_A, "")
    }

    @Test
    fun `Malformed PSBT bytes with non matching fingerprint rejected via fingerprint gate`() {
        val result = signPsbtForCosigner(byteArrayOf(1, 2, 3), "00000000", TEST_WORDS_A, "")
        assertTrue(result is SignForCosignerResult.FingerprintMismatch)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `Rejects malformed claimed fingerprint string`() {
        signPsbtForCosigner(buildTwoCosignerPsbtBytes(), "not-hex!!", TEST_WORDS_A, "")
    }

    @Test
    fun `signPsbtForCosigner does not mutate the PSBT bytes array passed in`() {
        val psbtBytes = buildTwoCosignerPsbtBytes()
        val result = signPsbtForCosigner(psbtBytes, EXPECTED_FINGERPRINT_A, TEST_WORDS_A, "")
        assertTrue(result is SignForCosignerResult.Signed)
        val parsedOriginal = parsePsbt(psbtBytes)
        assertTrue(parsedOriginal.inputs[0].partialSigs().isEmpty())
    }
}
