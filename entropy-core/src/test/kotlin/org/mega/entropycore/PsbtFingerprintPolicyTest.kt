package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for a real Sparrow PSBT signing failure: a Native SegWit
 * P2WPKH PSBT whose PSBT_IN_BIP32_DERIVATION carried the well-known 00000000
 * "unrecorded fingerprint" placeholder — legal per BIP174 (the field is
 * advisory metadata a coordinator writes, not itself proof of anything) but
 * treated by signPsbt as a plain mismatch, so a device that genuinely held
 * the exact matching key at the exact matching path, spending a UTXO its
 * derived pubkey is cryptographically bound to, still could not sign.
 *
 * Covers the FingerprintMatchStatus/FingerprintTrustPolicy split (see
 * PsbtSigning.kt): single-seed Advanced Mode may explicitly opt into
 * treating an unrecorded-fingerprint-but-pubkey-matching derivation as
 * signable (only after the caller has shown the required warning and the
 * user has explicitly confirmed — see PsbtSignResultScreen); the saved-
 * vault/cosigner flow (signPsbtForCosigner) never does, regardless of
 * policy, because it always signs with FingerprintTrustPolicy.STRICT.
 */
class PsbtFingerprintPolicyTest {

    companion object {
        // Real, independently-verified P2WPKH single-sig fixture — same as
        // PsbtSigningP2wpkhTest/PsbtWorkflowTest/PsbtNonWitnessUtxoWitnessCompatTest
        // (m/84'/0'/0'/0/0 of the standard test mnemonic).
        private val TEST_WORDS = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        private const val TEST_PASSPHRASE = ""
        private val DERIVATION_PATH = listOf(2147483732L, 2147483648L, 2147483648L, 0L, 0L)
        private const val MASTER_FINGERPRINT_HEX = "73c5da0a"
        private const val WRONG_FINGERPRINT_HEX = "deadbeef"
        private const val UNKNOWN_FINGERPRINT_HEX = "00000000"
        private const val EXPECTED_PUBKEY_HEX = "0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c"
        private const val WRONG_PUBKEY_HEX = "03de55d1e1dac805e3f8a58c1fbf9b94c02f3dbaafe127fefca4995f26f82083bd"
        private const val SCRIPT_PUBKEY_HEX = "0014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2"
        private const val WITNESS_UTXO_AMOUNT = 199909013L
        private const val UNSIGNED_TX_HEX = "0200000001279a2323a5dfb51fc45f220fa58b0fc13e1e3342792a85d7e36cd6333b5cbc390000000000ffffffff01a05aea0b000000001976a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac00000000"

        // Second P2WSH-multisig fixture, mirroring CosignerPsbtSigningTest.kt.
        private val TEST_WORDS_B = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote".split(" ")
        private val MULTISIG_DERIVATION_PATH = listOf(2147483696L, 2147483648L, 2147483648L, 2147483650L, 0L, 0L)

        private fun String.hexToBytes(): ByteArray {
            require(length % 2 == 0)
            return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
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
        private fun witnessUtxoValue(): ByteArray {
            val scriptPubKey = SCRIPT_PUBKEY_HEX.hexToBytes()
            return WITNESS_UTXO_AMOUNT.toUInt64LE() + shortCompactSize(scriptPubKey.size) + scriptPubKey
        }
        private fun bip32DerivationValue(fingerprintHex: String, path: List<Long> = DERIVATION_PATH): ByteArray {
            val fingerprint = fingerprintHex.hexToBytes()
            val pathBytes = path.fold(ByteArray(0)) { acc, element -> acc + element.toUInt32LE() }
            return fingerprint + pathBytes
        }
        private fun buildMatchingInputMap(fingerprintHex: String, pubkeyHex: String = EXPECTED_PUBKEY_HEX): PsbtMap {
            val pubkeyBytes = pubkeyHex.hexToBytes()
            return PsbtMap(
                entries = listOf(
                    PsbtKeyValue(keyType = 0x01, keyData = ByteArray(0), value = witnessUtxoValue()),
                    PsbtKeyValue(keyType = 0x06, keyData = pubkeyBytes, value = bip32DerivationValue(fingerprintHex)),
                ),
            )
        }
        private fun buildPsbt(inputs: List<PsbtMap>): Psbt {
            val unsignedTx = parseTransaction(UNSIGNED_TX_HEX.hexToBytes())
            return Psbt(
                unsignedTx = unsignedTx,
                global = PsbtMap(listOf(PsbtKeyValue(keyType = 0x00, keyData = ByteArray(0), value = UNSIGNED_TX_HEX.hexToBytes()))),
                inputs = inputs,
                outputs = unsignedTx.outputs.map { PsbtMap(emptyList()) },
            )
        }
        private fun buildPsbtBytes(inputs: List<PsbtMap>): ByteArray = serializePsbt(buildPsbt(inputs))
        private fun testMasterKey(): Bip32ExtendedPrivateKey =
            bip32MasterKeyFromSeed(deriveSeed(TEST_WORDS, TEST_PASSPHRASE).bytes)

        private fun masterKeyFor(words: List<String>): Bip32ExtendedPrivateKey =
            bip32MasterKeyFromSeed(deriveSeed(words, "").bytes)
        private fun childKeyFor(master: Bip32ExtendedPrivateKey, path: List<Long>): Bip32ExtendedPrivateKey {
            var child = master
            for (rawIndex in path) {
                val hardened = rawIndex >= HARDENED_OFFSET
                val index = if (hardened) rawIndex - HARDENED_OFFSET else rawIndex
                child = child.deriveChild(index, hardened)
            }
            return child
        }

        /** A real 2-of-2 P2WSH multisig PSBT — one input, both cosigners'
         * bip32_derivation entries present, no signatures yet — mirroring
         * CosignerPsbtSigningTest.kt's own fixture exactly. [fingerprintForB]
         * lets a test substitute the unrecorded-fingerprint placeholder for
         * cosigner B specifically. */
        private fun buildTwoCosignerPsbtBytes(fingerprintForB: ByteArray? = null): ByteArray {
            val masterA = masterKeyFor(TEST_WORDS)
            val masterB = masterKeyFor(TEST_WORDS_B)
            val pubkeyA = childKeyFor(masterA, MULTISIG_DERIVATION_PATH).compressedPublicKey()
            val pubkeyB = childKeyFor(masterB, MULTISIG_DERIVATION_PATH).compressedPublicKey()
            val sortedPubkeys = sortPublicKeysBip67(listOf(pubkeyA, pubkeyB))
            val witnessScript = buildMultisigWitnessScript(threshold = 2, sortedPublicKeys = sortedPubkeys)
            val scriptPubKey = byteArrayOf(0x00, 0x20) + sha256(witnessScript)
            val witnessUtxoValueBytes = WITNESS_UTXO_AMOUNT.toUInt64LE() + shortCompactSize(scriptPubKey.size) + scriptPubKey
            val inputMap = PsbtMap(
                entries = listOf(
                    PsbtKeyValue(keyType = 0x01, keyData = ByteArray(0), value = witnessUtxoValueBytes),
                    PsbtKeyValue(keyType = 0x05, keyData = ByteArray(0), value = witnessScript),
                    PsbtKeyValue(
                        keyType = 0x06, keyData = pubkeyA,
                        value = bip32DerivationValue(masterA.fingerprint().toHex(), MULTISIG_DERIVATION_PATH),
                    ),
                    PsbtKeyValue(
                        keyType = 0x06, keyData = pubkeyB,
                        value = (fingerprintForB ?: masterB.fingerprint()) +
                            MULTISIG_DERIVATION_PATH.fold(ByteArray(0)) { acc, e -> acc + e.toUInt32LE() },
                    ),
                ),
            )
            val unsignedTx = parseTransaction(UNSIGNED_TX_HEX.hexToBytes())
            val psbt = Psbt(
                unsignedTx = unsignedTx,
                global = PsbtMap(listOf(PsbtKeyValue(keyType = 0x00, keyData = ByteArray(0), value = serializeTransaction(unsignedTx)))),
                inputs = listOf(inputMap),
                outputs = unsignedTx.outputs.map { PsbtMap(emptyList()) },
            )
            return serializePsbt(psbt)
        }
    }

    // --- classifyFingerprintMatch: direct unit coverage ---

    @Test
    fun `classifyFingerprintMatch returns VERIFIED_MATCH for this device's real fingerprint`() {
        val derivation = PsbtBip32Derivation(
            pubkey = EXPECTED_PUBKEY_HEX.hexToBytes(),
            masterFingerprint = MASTER_FINGERPRINT_HEX.hexToBytes(),
            path = DERIVATION_PATH,
        )
        assertEquals(FingerprintMatchStatus.VERIFIED_MATCH, classifyFingerprintMatch(derivation, testMasterKey()))
    }

    @Test
    fun `classifyFingerprintMatch returns MISMATCH for a well-formed but different fingerprint`() {
        val derivation = PsbtBip32Derivation(
            pubkey = EXPECTED_PUBKEY_HEX.hexToBytes(),
            masterFingerprint = WRONG_FINGERPRINT_HEX.hexToBytes(),
            path = DERIVATION_PATH,
        )
        assertEquals(FingerprintMatchStatus.MISMATCH, classifyFingerprintMatch(derivation, testMasterKey()))
    }

    @Test
    fun `classifyFingerprintMatch returns UNKNOWN_FINGERPRINT_PUBKEY_MATCH for 00000000 plus an exact derived pubkey`() {
        val derivation = PsbtBip32Derivation(
            pubkey = EXPECTED_PUBKEY_HEX.hexToBytes(),
            masterFingerprint = UNKNOWN_FINGERPRINT_HEX.hexToBytes(),
            path = DERIVATION_PATH,
        )
        assertEquals(FingerprintMatchStatus.UNKNOWN_FINGERPRINT_PUBKEY_MATCH, classifyFingerprintMatch(derivation, testMasterKey()))
    }

    @Test
    fun `classifyFingerprintMatch returns MISMATCH for 00000000 with a pubkey that does not derive to match`() {
        val derivation = PsbtBip32Derivation(
            pubkey = WRONG_PUBKEY_HEX.hexToBytes(),
            masterFingerprint = UNKNOWN_FINGERPRINT_HEX.hexToBytes(),
            path = DERIVATION_PATH,
        )
        assertEquals(FingerprintMatchStatus.MISMATCH, classifyFingerprintMatch(derivation, testMasterKey()))
    }

    @Test
    fun `classifyFingerprintMatch returns MALFORMED for a fingerprint that is not exactly 4 bytes`() {
        val tooShort = PsbtBip32Derivation(pubkey = EXPECTED_PUBKEY_HEX.hexToBytes(), masterFingerprint = ByteArray(3), path = DERIVATION_PATH)
        val tooLong = PsbtBip32Derivation(pubkey = EXPECTED_PUBKEY_HEX.hexToBytes(), masterFingerprint = ByteArray(5), path = DERIVATION_PATH)
        assertEquals(FingerprintMatchStatus.MALFORMED, classifyFingerprintMatch(tooShort, testMasterKey()))
        assertEquals(FingerprintMatchStatus.MALFORMED, classifyFingerprintMatch(tooLong, testMasterKey()))
    }

    // --- signPsbt / signAndFinalizePsbt policy: single-seed P2WPKH ---

    @Test
    fun `a correct nonzero fingerprint signs under the default STRICT policy`() {
        val psbt = buildPsbt(listOf(buildMatchingInputMap(MASTER_FINGERPRINT_HEX)))
        val signed = signPsbt(psbt, testMasterKey())
        assertEquals(1, signed.inputs[0].partialSigs().size)
    }

    @Test
    fun `a wrong nonzero fingerprint refuses under STRICT`() {
        val psbt = buildPsbt(listOf(buildMatchingInputMap(WRONG_FINGERPRINT_HEX)))
        val signed = signPsbt(psbt, testMasterKey())
        assertTrue(signed.inputs[0].partialSigs().isEmpty())
    }

    @Test
    fun `a wrong nonzero fingerprint refuses even under the liberal policy`() {
        // ALLOW_UNKNOWN_FINGERPRINT_WITH_KEY_MATCH only ever applies to the
        // 00000000 placeholder — a well-formed WRONG fingerprint is a plain
        // MISMATCH under any policy.
        val psbt = buildPsbt(listOf(buildMatchingInputMap(WRONG_FINGERPRINT_HEX)))
        val signed = signPsbt(psbt, testMasterKey(), FingerprintTrustPolicy.ALLOW_UNKNOWN_FINGERPRINT_WITH_KEY_MATCH)
        assertTrue(signed.inputs[0].partialSigs().isEmpty())
    }

    @Test
    fun `00000000 fingerprint with exact derived pubkey match refuses under default STRICT policy`() {
        // Reproduces the reported failure: fingerprint 00000000, but the
        // derivation path/pubkey/UTXO all genuinely match this device's key.
        val psbt = buildPsbt(listOf(buildMatchingInputMap(UNKNOWN_FINGERPRINT_HEX)))
        val signed = signPsbt(psbt, testMasterKey())
        assertTrue(signed.inputs[0].partialSigs().isEmpty())
    }

    @Test
    fun `00000000 fingerprint with exact derived pubkey match signs under the explicit liberal policy`() {
        val psbt = buildPsbt(listOf(buildMatchingInputMap(UNKNOWN_FINGERPRINT_HEX)))
        val signed = signPsbt(psbt, testMasterKey(), FingerprintTrustPolicy.ALLOW_UNKNOWN_FINGERPRINT_WITH_KEY_MATCH)
        val partialSigs = signed.inputs[0].partialSigs()
        assertEquals(1, partialSigs.size)
        assertEquals(EXPECTED_PUBKEY_HEX, partialSigs[0].pubkey.toHex())
    }

    @Test
    fun `00000000 fingerprint with a WRONG derived pubkey refuses under the liberal policy too`() {
        val psbt = buildPsbt(listOf(buildMatchingInputMap(UNKNOWN_FINGERPRINT_HEX, pubkeyHex = WRONG_PUBKEY_HEX)))
        val signed = signPsbt(psbt, testMasterKey(), FingerprintTrustPolicy.ALLOW_UNKNOWN_FINGERPRINT_WITH_KEY_MATCH)
        assertTrue(signed.inputs[0].partialSigs().isEmpty())
    }

    @Test
    fun `signAndFinalizePsbt fully finalizes the 00000000-fingerprint P2WPKH input only under the liberal policy`() {
        val psbtBytes = buildPsbtBytes(listOf(buildMatchingInputMap(UNKNOWN_FINGERPRINT_HEX)))

        val strictResult = signAndFinalizePsbt(psbtBytes, TEST_WORDS, TEST_PASSPHRASE)
        assertFalse(isPsbtFullyFinalized(strictResult))

        val liberalResult = signAndFinalizePsbt(
            psbtBytes, TEST_WORDS, TEST_PASSPHRASE, FingerprintTrustPolicy.ALLOW_UNKNOWN_FINGERPRINT_WITH_KEY_MATCH,
        )
        assertTrue(isPsbtFullyFinalized(liberalResult))
    }

    // --- Saved-vault/cosigner flow: 00000000 must refuse regardless ---

    @Test
    fun `signPsbtForCosigner never signs a 00000000-fingerprint input even when the derived pubkey matches`() {
        // Cosigner A's device identity is genuinely correct (signPsbtForCosigner's
        // own device-identity gate passes), but the PSBT's per-input derivation
        // for A carries the unrecorded-fingerprint placeholder. The saved-vault
        // flow must still refuse to count this as A's signature — it always
        // signs with FingerprintTrustPolicy.STRICT, never the liberal policy.
        val masterA = masterKeyFor(TEST_WORDS)
        val psbtBytes = buildTwoCosignerPsbtBytes(fingerprintForB = null).let {
            // Rebuild with cosigner A's OWN derivation carrying the placeholder instead.
            val masterB = masterKeyFor(TEST_WORDS_B)
            val pubkeyA = childKeyFor(masterA, MULTISIG_DERIVATION_PATH).compressedPublicKey()
            val pubkeyB = childKeyFor(masterB, MULTISIG_DERIVATION_PATH).compressedPublicKey()
            val sortedPubkeys = sortPublicKeysBip67(listOf(pubkeyA, pubkeyB))
            val witnessScript = buildMultisigWitnessScript(threshold = 2, sortedPublicKeys = sortedPubkeys)
            val scriptPubKey = byteArrayOf(0x00, 0x20) + sha256(witnessScript)
            val witnessUtxoValueBytes = WITNESS_UTXO_AMOUNT.toUInt64LE() + shortCompactSize(scriptPubKey.size) + scriptPubKey
            val pathBytes = MULTISIG_DERIVATION_PATH.fold(ByteArray(0)) { acc, e -> acc + e.toUInt32LE() }
            val inputMap = PsbtMap(
                entries = listOf(
                    PsbtKeyValue(keyType = 0x01, keyData = ByteArray(0), value = witnessUtxoValueBytes),
                    PsbtKeyValue(keyType = 0x05, keyData = ByteArray(0), value = witnessScript),
                    PsbtKeyValue(keyType = 0x06, keyData = pubkeyA, value = ByteArray(4) + pathBytes), // 00000000 for A
                    PsbtKeyValue(keyType = 0x06, keyData = pubkeyB, value = masterB.fingerprint() + pathBytes),
                ),
            )
            val unsignedTx = parseTransaction(UNSIGNED_TX_HEX.hexToBytes())
            val psbt = Psbt(
                unsignedTx = unsignedTx,
                global = PsbtMap(listOf(PsbtKeyValue(keyType = 0x00, keyData = ByteArray(0), value = serializeTransaction(unsignedTx)))),
                inputs = listOf(inputMap),
                outputs = unsignedTx.outputs.map { PsbtMap(emptyList()) },
            )
            serializePsbt(psbt)
        }

        val expectedA = masterKeyFingerprint(TEST_WORDS, "")
        val result = signPsbtForCosigner(psbtBytes, expectedA, TEST_WORDS, "")
        assertTrue(result is SignForCosignerResult.Signed)
        val signedBytes = (result as SignForCosignerResult.Signed).psbtBytes
        // Device identity was confirmed (Signed, not FingerprintMismatch), but
        // the per-input 00000000 fingerprint must still block the actual signature.
        assertTrue(parsePsbt(signedBytes).inputs[0].partialSigs().isEmpty())
    }

    @Test
    fun `existing 2-of-2 multisig behavior with real fingerprints is unchanged`() {
        val psbtBytes = buildTwoCosignerPsbtBytes()
        val expectedA = masterKeyFingerprint(TEST_WORDS, "")
        val expectedB = masterKeyFingerprint(TEST_WORDS_B, "")
        val resultA = signPsbtForCosigner(psbtBytes, expectedA, TEST_WORDS, "")
        assertTrue(resultA is SignForCosignerResult.Signed)
        val afterA = (resultA as SignForCosignerResult.Signed).psbtBytes
        assertEquals(1, parsePsbt(afterA).inputs[0].partialSigs().size)
        assertFalse(isPsbtFullyFinalized(afterA))

        val resultB = signPsbtForCosigner(afterA, expectedB, TEST_WORDS_B, "")
        assertTrue(resultB is SignForCosignerResult.Signed)
        val afterBoth = (resultB as SignForCosignerResult.Signed).psbtBytes
        assertTrue(isPsbtFullyFinalized(afterBoth))
    }

    // --- diagnosePsbtInputSigning: the two policy-facing prediction flags ---

    @Test
    fun `diagnostics - correct nonzero fingerprint sets wouldAddSignature, not the unverified flag`() {
        val psbt = buildPsbt(listOf(buildMatchingInputMap(MASTER_FINGERPRINT_HEX)))
        val d = diagnosePsbtInputSigning(psbt, testMasterKey())[0]
        assertTrue(d.wouldAddSignature)
        assertFalse(d.wouldAddSignatureWithUnverifiedFingerprint)
        assertEquals(FingerprintMatchStatus.VERIFIED_MATCH, d.keys[0].matchStatus)
    }

    @Test
    fun `diagnostics - 00000000 fingerprint with matching pubkey sets ONLY the unverified flag`() {
        val psbt = buildPsbt(listOf(buildMatchingInputMap(UNKNOWN_FINGERPRINT_HEX)))
        val d = diagnosePsbtInputSigning(psbt, testMasterKey())[0]
        assertFalse(d.wouldAddSignature)
        assertTrue(d.wouldAddSignatureWithUnverifiedFingerprint)
        assertEquals(FingerprintMatchStatus.UNKNOWN_FINGERPRINT_PUBKEY_MATCH, d.keys[0].matchStatus)
    }

    @Test
    fun `diagnostics - 00000000 fingerprint with a wrong pubkey sets neither flag`() {
        val psbt = buildPsbt(listOf(buildMatchingInputMap(UNKNOWN_FINGERPRINT_HEX, pubkeyHex = WRONG_PUBKEY_HEX)))
        val d = diagnosePsbtInputSigning(psbt, testMasterKey())[0]
        assertFalse(d.wouldAddSignature)
        assertFalse(d.wouldAddSignatureWithUnverifiedFingerprint)
        assertEquals(FingerprintMatchStatus.MISMATCH, d.keys[0].matchStatus)
    }

    // --- computePsbtSummary: hasUnverifiedOriginFingerprint (key-free signal) ---

    @Test
    fun `computePsbtSummary reports hasUnverifiedOriginFingerprint true for a 00000000-fingerprint derivation`() {
        val psbtBytes = buildPsbtBytes(listOf(buildMatchingInputMap(UNKNOWN_FINGERPRINT_HEX)))
        val summary = computePsbtSummary(psbtBytes)
        assertTrue(summary.hasUnverifiedOriginFingerprint)
    }

    @Test
    fun `computePsbtSummary reports hasUnverifiedOriginFingerprint false when every fingerprint is well-formed`() {
        val psbtBytes = buildPsbtBytes(listOf(buildMatchingInputMap(MASTER_FINGERPRINT_HEX)))
        val summary = computePsbtSummary(psbtBytes)
        assertFalse(summary.hasUnverifiedOriginFingerprint)
    }

    @Test
    fun `computePsbtSummary deviceCanSignAnyInput stays false for a 00000000 fingerprint even with the right device fingerprint supplied`() {
        // deviceCanSignAnyInput must never silently treat 00000000 as verified —
        // it's a key-free, fingerprint-only check, and 00000000 never equals a
        // real device fingerprint string.
        val psbtBytes = buildPsbtBytes(listOf(buildMatchingInputMap(UNKNOWN_FINGERPRINT_HEX)))
        val summary = computePsbtSummary(psbtBytes, deviceMasterFingerprint = MASTER_FINGERPRINT_HEX)
        assertEquals(false, summary.deviceCanSignAnyInput)
        assertTrue(summary.hasUnverifiedOriginFingerprint)
    }
}
