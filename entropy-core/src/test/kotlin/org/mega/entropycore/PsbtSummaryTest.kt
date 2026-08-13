package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers computePsbtSummary's honesty guarantees (Unknown rather than a
 * guess whenever a value genuinely can't be determined) and its actual
 * calculations (totals, fee, threshold, change detection, device-can-sign/
 * will-finalize prediction) against real, directly-constructed PSBTs — not
 * hand-typed hex blobs, so every asserted amount/count is self-evident from
 * the test itself. Fixture shape matches CosignerPsbtSigningTest.kt's own
 * proven 2-of-2 P2WSH multisig construction.
 */
class PsbtSummaryTest {

    companion object {
        private val TEST_WORDS_A = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        private val TEST_WORDS_B = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote".split(" ")
        private val DERIVATION_PATH = listOf(2147483696L, 2147483648L, 2147483648L, 2147483650L, 0L, 0L)

        private fun masterKeyFor(words: List<String>): Bip32ExtendedPrivateKey =
            bip32MasterKeyFromSeed(deriveSeed(words, "").bytes)

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

        private fun compactSize(len: Int): ByteArray {
            require(len < 0xFD)
            return byteArrayOf(len.toByte())
        }

        private fun bip32DerivationValue(fingerprint: ByteArray, path: List<Long> = DERIVATION_PATH): ByteArray =
            fingerprint + path.fold(ByteArray(0)) { acc, element -> acc + element.toUInt32LE() }

        private fun witnessUtxoValue(amountSats: Long, scriptPubKey: ByteArray): ByteArray =
            amountSats.toUInt64LE() + compactSize(scriptPubKey.size) + scriptPubKey

        private fun p2wpkhScriptPubKey(hash20: ByteArray = ByteArray(20)): ByteArray =
            byteArrayOf(0x00, 0x14) + hash20

        private fun p2wshScriptPubKey(witnessScript: ByteArray): ByteArray =
            byteArrayOf(0x00, 0x20) + sha256(witnessScript)

        /** A real 2-of-2 witness script from two actually-derived cosigner keys. */
        private fun twoCosignerFixture(): Quad {
            val masterA = masterKeyFor(TEST_WORDS_A)
            val masterB = masterKeyFor(TEST_WORDS_B)
            val pubkeyA = childKeyFor(masterA).compressedPublicKey()
            val pubkeyB = childKeyFor(masterB).compressedPublicKey()
            val sorted = sortPublicKeysBip67(listOf(pubkeyA, pubkeyB))
            val witnessScript = buildMultisigWitnessScript(2, sorted)
            return Quad(masterA, masterB, pubkeyA, pubkeyB, witnessScript)
        }

        private data class Quad(
            val masterA: Bip32ExtendedPrivateKey,
            val masterB: Bip32ExtendedPrivateKey,
            val pubkeyA: ByteArray,
            val pubkeyB: ByteArray,
            val witnessScript: ByteArray,
        )

        /** A real, cryptographically valid partial_sig for [master]'s derived child key over [witnessScript] at input 0. */
        private fun realCosignerSig(
            master: Bip32ExtendedPrivateKey,
            witnessScript: ByteArray,
            unsignedTx: Transaction,
            amountSats: Long,
        ): ByteArray {
            val child = childKeyFor(master)
            val scriptCode = compactSize(witnessScript.size) + witnessScript
            val sighash = computeSegwitSighash(unsignedTx, 0, scriptCode, amountSats, 1)
            return signEcdsaDer(child.privateKey, sighash) + byteArrayOf(0x01)
        }

        private fun unsignedTxWith(outputs: List<Pair<Long, ByteArray>>, inputCount: Int = 1): Transaction {
            val dummyPrevTxid = ByteArray(32)
            return Transaction(
                version = 2L,
                inputs = (0 until inputCount).map { i ->
                    TxIn(previousTxid = dummyPrevTxid, previousVout = i.toLong(), scriptSig = ByteArray(0), sequence = 0xffffffffL)
                },
                outputs = outputs.map { (amount, spk) -> TxOut(amount, spk) },
                locktime = 0L,
            )
        }

        private fun buildPsbtBytes(unsignedTx: Transaction, inputs: List<PsbtMap>, outputs: List<PsbtMap>): ByteArray {
            val psbt = Psbt(
                unsignedTx = unsignedTx,
                global = PsbtMap(listOf(PsbtKeyValue(keyType = 0x00, keyData = ByteArray(0), value = serializeTransaction(unsignedTx)))),
                inputs = inputs,
                outputs = outputs,
            )
            return serializePsbt(psbt)
        }
    }

    // --- 1. Basic counts and totals ---

    @Test
    fun `basic counts and totals are calculated correctly`() {
        val scriptA = p2wpkhScriptPubKey()
        val scriptB = p2wpkhScriptPubKey(ByteArray(20) { 1 })
        val unsignedTx = unsignedTxWith(outputs = listOf(120_000L to scriptA, 25_000L to scriptB), inputCount = 2)
        val inputs = listOf(
            PsbtMap(listOf(PsbtKeyValue(0x01, ByteArray(0), witnessUtxoValue(100_000L, scriptA)))),
            PsbtMap(listOf(PsbtKeyValue(0x01, ByteArray(0), witnessUtxoValue(50_000L, scriptB)))),
        )
        val outputs = listOf(PsbtMap(emptyList()), PsbtMap(emptyList()))
        val summary = computePsbtSummary(buildPsbtBytes(unsignedTx, inputs, outputs))

        assertEquals(2, summary.inputCount)
        assertEquals(2, summary.outputCount)
        assertEquals(150_000L, summary.totalInputSats)
        assertEquals(145_000L, summary.totalOutputSats)
        assertEquals(5_000L, summary.feeSats)
    }

    // --- 2. Unknown total when any input amount is missing ---

    @Test
    fun `totalInputSats feeSats and fee rate are null when any input amount is unknown`() {
        val scriptA = p2wpkhScriptPubKey()
        val unsignedTx = unsignedTxWith(outputs = listOf(120_000L to scriptA), inputCount = 2)
        val inputs = listOf(
            PsbtMap(listOf(PsbtKeyValue(0x01, ByteArray(0), witnessUtxoValue(100_000L, scriptA)))),
            PsbtMap(emptyList()), // no witness_utxo at all — amount unknown
        )
        val outputs = listOf(PsbtMap(emptyList()))
        val summary = computePsbtSummary(buildPsbtBytes(unsignedTx, inputs, outputs))

        assertNull(summary.totalInputSats)
        assertNull(summary.feeSats)
        assertNull(summary.estimatedFeeRateSatsPerVByte)
    }

    // --- 3. Threshold Unknown for a plain P2WPKH-only PSBT ---

    @Test
    fun `requiredThreshold is Unknown for a plain P2WPKH-only PSBT`() {
        val script = p2wpkhScriptPubKey()
        val unsignedTx = unsignedTxWith(outputs = listOf(50_000L to script))
        val inputs = listOf(PsbtMap(listOf(PsbtKeyValue(0x01, ByteArray(0), witnessUtxoValue(60_000L, script)))))
        val outputs = listOf(PsbtMap(emptyList()))
        val summary = computePsbtSummary(buildPsbtBytes(unsignedTx, inputs, outputs))

        assertEquals(PsbtThresholdInfo.Unknown, summary.requiredThreshold)
    }

    // --- 4. Threshold Known for a real 2-of-2 multisig input ---

    @Test
    fun `requiredThreshold is Known 2 of 2 for a real two cosigner multisig input`() {
        val fixture = twoCosignerFixture()
        val scriptPubKey = p2wshScriptPubKey(fixture.witnessScript)
        val unsignedTx = unsignedTxWith(outputs = listOf(50_000L to p2wpkhScriptPubKey()))
        val inputMap = PsbtMap(
            listOf(
                PsbtKeyValue(0x01, ByteArray(0), witnessUtxoValue(60_000L, scriptPubKey)),
                PsbtKeyValue(0x05, ByteArray(0), fixture.witnessScript),
                PsbtKeyValue(0x06, fixture.pubkeyA, bip32DerivationValue(fixture.masterA.fingerprint())),
                PsbtKeyValue(0x06, fixture.pubkeyB, bip32DerivationValue(fixture.masterB.fingerprint())),
            ),
        )
        val outputs = listOf(PsbtMap(emptyList()))
        val summary = computePsbtSummary(buildPsbtBytes(unsignedTx, listOf(inputMap), outputs))

        assertEquals(PsbtThresholdInfo.Known(2, 2), summary.requiredThreshold)
    }

    // --- 5. Threshold Varies across two differently-thresholded multisig inputs ---

    @Test
    fun `requiredThreshold is Varies when multisig inputs disagree`() {
        val fixture = twoCosignerFixture()
        val script2of2 = p2wshScriptPubKey(fixture.witnessScript)
        // buildMultisigWitnessScript requires at least 2 pubkeys, so a true
        // "1-of-1" script isn't constructible this way — a 1-of-2 script
        // (same two cosigners, different threshold from input0's 2-of-2)
        // is a real, valid, still-different multisig shape.
        val sortedForOneOfTwo = sortPublicKeysBip67(listOf(fixture.pubkeyA, fixture.pubkeyB))
        val witnessScript1of2 = buildMultisigWitnessScript(1, sortedForOneOfTwo)
        val script1of2 = p2wshScriptPubKey(witnessScript1of2)
        val unsignedTx = unsignedTxWith(outputs = listOf(50_000L to p2wpkhScriptPubKey()), inputCount = 2)
        val input0 = PsbtMap(
            listOf(
                PsbtKeyValue(0x01, ByteArray(0), witnessUtxoValue(60_000L, script2of2)),
                PsbtKeyValue(0x05, ByteArray(0), fixture.witnessScript),
                PsbtKeyValue(0x06, fixture.pubkeyA, bip32DerivationValue(fixture.masterA.fingerprint())),
                PsbtKeyValue(0x06, fixture.pubkeyB, bip32DerivationValue(fixture.masterB.fingerprint())),
            ),
        )
        val input1 = PsbtMap(
            listOf(
                PsbtKeyValue(0x01, ByteArray(0), witnessUtxoValue(40_000L, script1of2)),
                PsbtKeyValue(0x05, ByteArray(0), witnessScript1of2),
                PsbtKeyValue(0x06, fixture.pubkeyA, bip32DerivationValue(fixture.masterA.fingerprint())),
            ),
        )
        val outputs = listOf(PsbtMap(emptyList()))
        val summary = computePsbtSummary(buildPsbtBytes(unsignedTx, listOf(input0, input1), outputs))

        assertEquals(PsbtThresholdInfo.Varies, summary.requiredThreshold)
    }

    // --- 6. deviceCanSignAnyInput / willFinalizeIfSigned both null when no fingerprint given ---

    @Test
    fun `deviceCanSignAnyInput and willFinalizeIfSigned are null when no fingerprint is given`() {
        val fixture = twoCosignerFixture()
        val scriptPubKey = p2wshScriptPubKey(fixture.witnessScript)
        val unsignedTx = unsignedTxWith(outputs = listOf(50_000L to p2wpkhScriptPubKey()))
        val inputMap = PsbtMap(
            listOf(
                PsbtKeyValue(0x01, ByteArray(0), witnessUtxoValue(60_000L, scriptPubKey)),
                PsbtKeyValue(0x05, ByteArray(0), fixture.witnessScript),
                PsbtKeyValue(0x06, fixture.pubkeyA, bip32DerivationValue(fixture.masterA.fingerprint())),
                PsbtKeyValue(0x06, fixture.pubkeyB, bip32DerivationValue(fixture.masterB.fingerprint())),
            ),
        )
        val outputs = listOf(PsbtMap(emptyList()))
        val summary = computePsbtSummary(buildPsbtBytes(unsignedTx, listOf(inputMap), outputs))

        assertNull(summary.deviceCanSignAnyInput)
        assertNull(summary.willFinalizeIfSigned)
    }

    // --- 7. deviceCanSignAnyInput true, willFinalizeIfSigned true when this device completes a 2-of-2 ---

    @Test
    fun `deviceCanSignAnyInput and willFinalizeIfSigned are both true when this device completes the threshold`() {
        val fixture = twoCosignerFixture()
        val scriptPubKey = p2wshScriptPubKey(fixture.witnessScript)
        val unsignedTx = unsignedTxWith(outputs = listOf(50_000L to p2wpkhScriptPubKey()))
        // Cosigner A has already signed — only cosigner B's signature is still needed.
        // willFinalizeIfSigned now cryptographically verifies existing partial_sigs
        // (finding #6), so A's signature here must be real and valid, not a placeholder.
        val sigA = realCosignerSig(fixture.masterA, fixture.witnessScript, unsignedTx, amountSats = 60_000L)
        val inputMap = PsbtMap(
            listOf(
                PsbtKeyValue(0x01, ByteArray(0), witnessUtxoValue(60_000L, scriptPubKey)),
                PsbtKeyValue(0x05, ByteArray(0), fixture.witnessScript),
                PsbtKeyValue(0x06, fixture.pubkeyA, bip32DerivationValue(fixture.masterA.fingerprint())),
                PsbtKeyValue(0x06, fixture.pubkeyB, bip32DerivationValue(fixture.masterB.fingerprint())),
                PsbtKeyValue(0x02, fixture.pubkeyA, sigA),
            ),
        )
        val outputs = listOf(PsbtMap(emptyList()))
        val summary = computePsbtSummary(
            buildPsbtBytes(unsignedTx, listOf(inputMap), outputs),
            deviceMasterFingerprint = masterKeyFingerprint(TEST_WORDS_B, ""),
        )

        assertTrue(summary.deviceCanSignAnyInput == true)
        assertTrue(summary.willFinalizeIfSigned == true)
    }

    // --- 7b. willFinalizeIfSigned is false, not true, when an existing signature is present but invalid ---

    @Test
    fun `willFinalizeIfSigned is false when an existing partial_sig meets the count but fails verification`() {
        // Same shape as test 7 (device B would complete the 2-of-2), except
        // cosigner A's "signature" is malformed bytes rather than real ones.
        // A naive count-based prediction would see 2 candidates for a 2-of-2
        // and predict true; the actual finalizer can never produce a valid
        // witness from this PSBT, so the summary must not claim it can either
        // (finding #6 — the UI's "ready to broadcast" prediction must track
        // the real finalizer, not a signature count).
        val fixture = twoCosignerFixture()
        val scriptPubKey = p2wshScriptPubKey(fixture.witnessScript)
        val unsignedTx = unsignedTxWith(outputs = listOf(50_000L to p2wpkhScriptPubKey()))
        val garbageSigForA = byteArrayOf(0x30, 0x44, 0x01) + ByteArray(68) { 0x11 } + byteArrayOf(0x01)
        val inputMap = PsbtMap(
            listOf(
                PsbtKeyValue(0x01, ByteArray(0), witnessUtxoValue(60_000L, scriptPubKey)),
                PsbtKeyValue(0x05, ByteArray(0), fixture.witnessScript),
                PsbtKeyValue(0x06, fixture.pubkeyA, bip32DerivationValue(fixture.masterA.fingerprint())),
                PsbtKeyValue(0x06, fixture.pubkeyB, bip32DerivationValue(fixture.masterB.fingerprint())),
                PsbtKeyValue(0x02, fixture.pubkeyA, garbageSigForA),
            ),
        )
        val outputs = listOf(PsbtMap(emptyList()))
        val summary = computePsbtSummary(
            buildPsbtBytes(unsignedTx, listOf(inputMap), outputs),
            deviceMasterFingerprint = masterKeyFingerprint(TEST_WORDS_B, ""),
        )

        assertTrue(summary.deviceCanSignAnyInput == true)
        assertFalse(summary.willFinalizeIfSigned == true)
    }

    // --- 8. deviceCanSignAnyInput false when the fingerprint matches nothing ---

    @Test
    fun `deviceCanSignAnyInput is false and willFinalizeIfSigned is null when fingerprint matches nothing`() {
        val fixture = twoCosignerFixture()
        val scriptPubKey = p2wshScriptPubKey(fixture.witnessScript)
        val unsignedTx = unsignedTxWith(outputs = listOf(50_000L to p2wpkhScriptPubKey()))
        val inputMap = PsbtMap(
            listOf(
                PsbtKeyValue(0x01, ByteArray(0), witnessUtxoValue(60_000L, scriptPubKey)),
                PsbtKeyValue(0x05, ByteArray(0), fixture.witnessScript),
                PsbtKeyValue(0x06, fixture.pubkeyA, bip32DerivationValue(fixture.masterA.fingerprint())),
                PsbtKeyValue(0x06, fixture.pubkeyB, bip32DerivationValue(fixture.masterB.fingerprint())),
            ),
        )
        val outputs = listOf(PsbtMap(emptyList()))
        val summary = computePsbtSummary(
            buildPsbtBytes(unsignedTx, listOf(inputMap), outputs),
            deviceMasterFingerprint = "00000000",
        )

        assertTrue(summary.deviceCanSignAnyInput == false)
        assertNull(summary.willFinalizeIfSigned)
    }

    // --- 9. willFinalizeIfSigned is null (not false) when another input's threshold can't be determined ---

    @Test
    fun `willFinalizeIfSigned is null not false when a second input's threshold is unparseable`() {
        val fixture = twoCosignerFixture()
        val scriptPubKey = p2wshScriptPubKey(fixture.witnessScript)
        // Input 0: a real 2-of-2 multisig this device (A) can sign.
        val input0 = PsbtMap(
            listOf(
                PsbtKeyValue(0x01, ByteArray(0), witnessUtxoValue(60_000L, scriptPubKey)),
                PsbtKeyValue(0x05, ByteArray(0), fixture.witnessScript),
                PsbtKeyValue(0x06, fixture.pubkeyA, bip32DerivationValue(fixture.masterA.fingerprint())),
                PsbtKeyValue(0x06, fixture.pubkeyB, bip32DerivationValue(fixture.masterB.fingerprint())),
            ),
        )
        // Input 1: P2WSH with a witness_script that is NOT a valid bare-multisig
        // shape but still carries a bip32_derivation for cosigner A — so
        // deviceCanSignAnyInput is true (via input 0), but this input's own
        // threshold genuinely cannot be determined. The first byte (0x01) is
        // deliberately outside the OP_1..OP_16 range (0x51..0x60) the
        // bare-multisig template requires as its opening opcode.
        val nonStandardWitnessScript = byteArrayOf(0x01, 0x02, 0x03)
        val input1 = PsbtMap(
            listOf(
                PsbtKeyValue(0x01, ByteArray(0), witnessUtxoValue(40_000L, p2wshScriptPubKey(nonStandardWitnessScript))),
                PsbtKeyValue(0x05, ByteArray(0), nonStandardWitnessScript),
                PsbtKeyValue(0x06, fixture.pubkeyA, bip32DerivationValue(fixture.masterA.fingerprint())),
            ),
        )
        val unsignedTx = unsignedTxWith(outputs = listOf(50_000L to p2wpkhScriptPubKey()), inputCount = 2)
        val outputs = listOf(PsbtMap(emptyList()))
        val summary = computePsbtSummary(
            buildPsbtBytes(unsignedTx, listOf(input0, input1), outputs),
            deviceMasterFingerprint = masterKeyFingerprint(TEST_WORDS_A, ""),
        )

        assertTrue(summary.deviceCanSignAnyInput == true)
        assertNull(summary.willFinalizeIfSigned)
    }

    // --- 10. Address decoding for P2WPKH with a known network, and null without one ---

    @Test
    fun `output address is decoded only when network is known`() {
        val script = p2wpkhScriptPubKey(ByteArray(20) { 7 })
        val unsignedTx = unsignedTxWith(outputs = listOf(30_000L to script))
        val outputs = listOf(PsbtMap(emptyList()))
        val bytes = buildPsbtBytes(unsignedTx, listOf(PsbtMap(emptyList())), outputs)

        val withNetwork = computePsbtSummary(bytes, knownNetwork = WalletNetwork.TESTNET)
        assertTrue(withNetwork.outputs[0].address?.startsWith("tb1") == true)
        assertFalse(withNetwork.outputs[0].scriptPubKeyHex.isBlank())

        val withoutNetwork = computePsbtSummary(bytes, knownNetwork = null)
        assertNull(withoutNetwork.outputs[0].address)
        assertFalse(withoutNetwork.outputs[0].scriptPubKeyHex.isBlank())
    }

    // --- 11. isLikelyChange true only when output derivation matches an input's fingerprint ---

    @Test
    fun `isLikelyChange is true only for an output whose derivation matches an input fingerprint`() {
        val fixture = twoCosignerFixture()
        val inputScript = p2wpkhScriptPubKey()
        val inputMap = PsbtMap(
            listOf(
                PsbtKeyValue(0x01, ByteArray(0), witnessUtxoValue(100_000L, inputScript)),
                PsbtKeyValue(0x06, fixture.pubkeyA, bip32DerivationValue(fixture.masterA.fingerprint())),
            ),
        )
        val changeScript = p2wpkhScriptPubKey(ByteArray(20) { 2 })
        val externalScript = p2wpkhScriptPubKey(ByteArray(20) { 3 })
        val unsignedTx = unsignedTxWith(outputs = listOf(50_000L to changeScript, 40_000L to externalScript))
        val changeOutputMap = PsbtMap(
            listOf(PsbtKeyValue(0x02, fixture.pubkeyA, bip32DerivationValue(fixture.masterA.fingerprint()))),
        )
        val externalOutputMap = PsbtMap(emptyList())
        val summary = computePsbtSummary(buildPsbtBytes(unsignedTx, listOf(inputMap), listOf(changeOutputMap, externalOutputMap)))

        assertTrue(summary.outputs[0].isLikelyChange)
        assertFalse(summary.outputs[1].isLikelyChange)
    }

    // --- 12. Fee rate is a positive, finite estimate when fee is known ---

    @Test
    fun `estimated fee rate is a positive finite number when fee is known`() {
        val scriptA = p2wpkhScriptPubKey()
        val unsignedTx = unsignedTxWith(outputs = listOf(120_000L to scriptA))
        val inputs = listOf(PsbtMap(listOf(PsbtKeyValue(0x01, ByteArray(0), witnessUtxoValue(150_000L, scriptA)))))
        val outputs = listOf(PsbtMap(emptyList()))
        val summary = computePsbtSummary(buildPsbtBytes(unsignedTx, inputs, outputs))

        val rate = summary.estimatedFeeRateSatsPerVByte
        assertTrue(rate != null && rate > 0.0 && rate.isFinite())
    }

    // --- 13. Existing signature count and isAlreadyPartiallySigned are honest before this device signs ---

    @Test
    fun `existingSignatureCount and isAlreadyPartiallySigned reflect only signatures already present`() {
        val fixture = twoCosignerFixture()
        val scriptPubKey = p2wshScriptPubKey(fixture.witnessScript)
        val unsignedTx = unsignedTxWith(outputs = listOf(50_000L to p2wpkhScriptPubKey()))
        val outputs = listOf(PsbtMap(emptyList()))

        val signedInput = PsbtMap(
            listOf(
                PsbtKeyValue(0x01, ByteArray(0), witnessUtxoValue(60_000L, scriptPubKey)),
                PsbtKeyValue(0x05, ByteArray(0), fixture.witnessScript),
                PsbtKeyValue(0x06, fixture.pubkeyA, bip32DerivationValue(fixture.masterA.fingerprint())),
                PsbtKeyValue(0x02, fixture.pubkeyA, byteArrayOf(0x30, 0x00)),
            ),
        )
        val withSig = computePsbtSummary(buildPsbtBytes(unsignedTx, listOf(signedInput), outputs))
        assertEquals(1, withSig.existingSignatureCount)
        assertTrue(withSig.isAlreadyPartiallySigned)

        val unsignedInput = PsbtMap(
            listOf(
                PsbtKeyValue(0x01, ByteArray(0), witnessUtxoValue(60_000L, scriptPubKey)),
                PsbtKeyValue(0x05, ByteArray(0), fixture.witnessScript),
                PsbtKeyValue(0x06, fixture.pubkeyA, bip32DerivationValue(fixture.masterA.fingerprint())),
            ),
        )
        val withoutSig = computePsbtSummary(buildPsbtBytes(unsignedTx, listOf(unsignedInput), outputs))
        assertEquals(0, withoutSig.existingSignatureCount)
        assertFalse(withoutSig.isAlreadyPartiallySigned)
    }

    // --- 14. Malformed PSBT bytes propagate a real parse failure ---

    @Test(expected = IllegalArgumentException::class)
    fun `computePsbtSummary propagates a real parse failure for malformed bytes`() {
        computePsbtSummary(byteArrayOf(1, 2, 3))
    }
}
