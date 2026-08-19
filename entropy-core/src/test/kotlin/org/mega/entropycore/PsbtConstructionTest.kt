package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reuses the same independently-verified P2WPKH test vector as
 * PsbtWorkflowTest/WalletDerivationTest (the well-known all-"abandon"
 * 12-word mnemonic, m/84'/0'/0'/0/0 mainnet) rather than inventing a new
 * one — that vector's pubkey/fingerprint/address/final-tx-hex were
 * already cross-checked against an independent from-scratch
 * implementation (see WalletDerivationTest's own doc comment), so
 * reusing it here proves buildUnsignedPsbt's output leads to the SAME
 * hand-verified final transaction, not just an internally-consistent one.
 */
class PsbtConstructionTest {

    companion object {
        private val TEST_WORDS = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        private const val TEST_PASSPHRASE = ""

        private val DERIVATION_PATH = listOf(2147483732L, 2147483648L, 2147483648L, 0L, 0L)
        private const val MASTER_FINGERPRINT_HEX = "73c5da0a"
        private const val EXPECTED_PUBKEY_HEX = "0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c"
        private const val SCRIPT_PUBKEY_HEX = "0014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2"
        private const val WITNESS_UTXO_AMOUNT = 199909013L
        private const val EXPECTED_ADDRESS = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        private const val ACCOUNT_ZPUB =
            "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

        private const val UNSIGNED_TX_HEX = "0200000001279a2323a5dfb51fc45f220fa58b0fc13e1e3342792a85d7e36cd6333b5cbc390000000000ffffffff01a05aea0b000000001976a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac00000000"
        private const val EXPECTED_FINAL_TX_HEX = "02000000000101279a2323a5dfb51fc45f220fa58b0fc13e1e3342792a85d7e36cd6333b5cbc390000000000ffffffff01a05aea0b000000001976a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac02483045022100ec7501838a5b3d24e0ed7ced2ca9ca22fb198ef5751b5a5352e8928fd8763cec0220346ff4f1d0c9e96f5d598f6f72a8e1120b22f4442757b8c72edfdda9c3738dde01210330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c00000000"

        private fun String.hexToBytes(): ByteArray {
            require(length % 2 == 0)
            return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }

    // -------------------------------------------------------------------
    // deriveWalletAddress / deriveAddressFromExtendedPublicKey
    // -------------------------------------------------------------------

    @Test
    fun `deriveWalletAddress at chain 0 index 0 matches the independently-verified first receive address`() {
        val derived = deriveWalletAddress(TEST_WORDS, TEST_PASSPHRASE, WalletNetwork.MAINNET, account = 0, chain = 0, index = 0)

        assertEquals(EXPECTED_ADDRESS, derived.address)
        assertEquals(SCRIPT_PUBKEY_HEX, derived.scriptPubKey.toHex())
        assertEquals(EXPECTED_PUBKEY_HEX, derived.derivation.pubkey.toHex())
        assertEquals(MASTER_FINGERPRINT_HEX, derived.derivation.masterFingerprint.toHex())
        assertEquals(DERIVATION_PATH, derived.derivation.path)
    }

    @Test
    fun `deriveWalletAddress at a later index produces a different address on the same account`() {
        val first = deriveWalletAddress(TEST_WORDS, TEST_PASSPHRASE, WalletNetwork.MAINNET, account = 0, chain = 0, index = 0)
        val third = deriveWalletAddress(TEST_WORDS, TEST_PASSPHRASE, WalletNetwork.MAINNET, account = 0, chain = 0, index = 3)

        assertTrue(first.address != third.address)
        assertEquals(listOf(2147483732L, 2147483648L, 2147483648L, 0L, 3L), third.derivation.path)
    }

    @Test
    fun `deriveWalletAddress on the change chain uses chain element 1`() {
        val change = deriveWalletAddress(TEST_WORDS, TEST_PASSPHRASE, WalletNetwork.MAINNET, account = 0, chain = 1, index = 0)
        assertEquals(listOf(2147483732L, 2147483648L, 2147483648L, 1L, 0L), change.derivation.path)
    }

    @Test
    fun `deriveAddressFromExtendedPublicKey agrees with the private-key derivation at the same index`() {
        val fromXpub = deriveAddressFromExtendedPublicKey(ACCOUNT_ZPUB, WalletNetwork.MAINNET, chain = 0, index = 0)

        assertEquals(EXPECTED_ADDRESS, fromXpub.address)
        assertEquals(SCRIPT_PUBKEY_HEX, fromXpub.scriptPubKey.toHex())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `deriveAddressFromExtendedPublicKey rejects a network mismatch`() {
        // ACCOUNT_ZPUB is a mainnet key; asking for testnet must fail closed
        // rather than silently deriving a mainnet address under a testnet label.
        deriveAddressFromExtendedPublicKey(ACCOUNT_ZPUB, WalletNetwork.TESTNET, chain = 0, index = 0)
    }

    // -------------------------------------------------------------------
    // buildUnsignedPsbt
    // -------------------------------------------------------------------

    @Test
    fun `buildUnsignedPsbt reproduces the independently-verified unsigned transaction bytes`() {
        val fixtureTx = parseTransaction(UNSIGNED_TX_HEX.hexToBytes())
        val displayTxid = fixtureTx.inputs.single().previousTxid.reversedArray().toHex()

        val builtBytes = buildUnsignedPsbt(
            inputs = listOf(
                PsbtInputPlan(
                    txid = displayTxid,
                    vout = fixtureTx.inputs.single().previousVout,
                    amountSats = WITNESS_UTXO_AMOUNT,
                    scriptPubKey = SCRIPT_PUBKEY_HEX.hexToBytes(),
                    derivation = PsbtBip32Derivation(
                        pubkey = EXPECTED_PUBKEY_HEX.hexToBytes(),
                        masterFingerprint = MASTER_FINGERPRINT_HEX.hexToBytes(),
                        path = DERIVATION_PATH,
                    ),
                ),
            ),
            outputs = listOf(
                PsbtOutputPlan(
                    amountSats = fixtureTx.outputs.single().valueSats,
                    scriptPubKey = fixtureTx.outputs.single().scriptPubKey,
                ),
            ),
            rbf = false,
        )

        val builtUnsignedTx = parsePsbt(builtBytes).unsignedTx
        assertTrue(serializeTransaction(builtUnsignedTx).contentEquals(UNSIGNED_TX_HEX.hexToBytes()))
    }

    @Test
    fun `a built PSBT signs and finalizes to the exact independently-verified final transaction`() {
        val fixtureTx = parseTransaction(UNSIGNED_TX_HEX.hexToBytes())
        val displayTxid = fixtureTx.inputs.single().previousTxid.reversedArray().toHex()

        val builtBytes = buildUnsignedPsbt(
            inputs = listOf(
                PsbtInputPlan(
                    txid = displayTxid,
                    vout = fixtureTx.inputs.single().previousVout,
                    amountSats = WITNESS_UTXO_AMOUNT,
                    scriptPubKey = SCRIPT_PUBKEY_HEX.hexToBytes(),
                    derivation = PsbtBip32Derivation(
                        pubkey = EXPECTED_PUBKEY_HEX.hexToBytes(),
                        masterFingerprint = MASTER_FINGERPRINT_HEX.hexToBytes(),
                        path = DERIVATION_PATH,
                    ),
                ),
            ),
            outputs = listOf(
                PsbtOutputPlan(
                    amountSats = fixtureTx.outputs.single().valueSats,
                    scriptPubKey = fixtureTx.outputs.single().scriptPubKey,
                ),
            ),
            rbf = false,
        )

        val signed = signAndFinalizePsbt(builtBytes, TEST_WORDS, TEST_PASSPHRASE)
        assertTrue(isPsbtFullyFinalized(signed))
        assertEquals(EXPECTED_FINAL_TX_HEX, extractFinalTransactionHex(signed))
    }

    @Test
    fun `RBF signals with sequence 0xFFFFFFFD, non-RBF with 0xFFFFFFFF`() {
        val input = PsbtInputPlan(
            txid = "00".repeat(32),
            vout = 0L,
            amountSats = 100_000L,
            scriptPubKey = SCRIPT_PUBKEY_HEX.hexToBytes(),
            derivation = PsbtBip32Derivation(EXPECTED_PUBKEY_HEX.hexToBytes(), MASTER_FINGERPRINT_HEX.hexToBytes(), DERIVATION_PATH),
        )
        val output = PsbtOutputPlan(amountSats = 90_000L, scriptPubKey = SCRIPT_PUBKEY_HEX.hexToBytes())

        val rbfTx = parsePsbt(buildUnsignedPsbt(listOf(input), listOf(output), rbf = true)).unsignedTx
        val finalTx = parsePsbt(buildUnsignedPsbt(listOf(input), listOf(output), rbf = false)).unsignedTx

        assertEquals(0xFFFFFFFDL, rbfTx.inputs.single().sequence)
        assertEquals(0xFFFFFFFFL, finalTx.inputs.single().sequence)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildUnsignedPsbt rejects spending the same outpoint twice`() {
        val input = PsbtInputPlan(
            txid = "11".repeat(32),
            vout = 0L,
            amountSats = 100_000L,
            scriptPubKey = SCRIPT_PUBKEY_HEX.hexToBytes(),
            derivation = PsbtBip32Derivation(EXPECTED_PUBKEY_HEX.hexToBytes(), MASTER_FINGERPRINT_HEX.hexToBytes(), DERIVATION_PATH),
        )
        val output = PsbtOutputPlan(amountSats = 90_000L, scriptPubKey = SCRIPT_PUBKEY_HEX.hexToBytes())

        buildUnsignedPsbt(listOf(input, input), listOf(output), rbf = false)
    }

    @Test
    fun `output-level bip32 derivation on a change output is recognized as change by PsbtSummary`() {
        val changeDerivation = deriveWalletAddress(TEST_WORDS, TEST_PASSPHRASE, WalletNetwork.MAINNET, account = 0, chain = 1, index = 0)
        val sourceInput = deriveWalletAddress(TEST_WORDS, TEST_PASSPHRASE, WalletNetwork.MAINNET, account = 0, chain = 0, index = 0)

        val builtBytes = buildUnsignedPsbt(
            inputs = listOf(
                PsbtInputPlan(
                    txid = "22".repeat(32),
                    vout = 0L,
                    amountSats = 100_000L,
                    scriptPubKey = sourceInput.scriptPubKey,
                    derivation = sourceInput.derivation,
                ),
            ),
            outputs = listOf(
                PsbtOutputPlan(amountSats = 50_000L, scriptPubKey = ByteArray(22) { 0 }, changeDerivation = null),
                PsbtOutputPlan(amountSats = 49_000L, scriptPubKey = changeDerivation.scriptPubKey, changeDerivation = changeDerivation.derivation),
            ),
            rbf = false,
        )

        val summary = computePsbtSummary(builtBytes, knownNetwork = WalletNetwork.MAINNET)
        assertTrue(summary.outputs[0].isLikelyChange == false)
        assertTrue(summary.outputs[1].isLikelyChange)
    }

    // -------------------------------------------------------------------
    // estimateSplitTransactionFeeSats — must agree with PsbtSummary's own
    // post-hoc estimate on the identical transaction shape.
    // -------------------------------------------------------------------

    @Test
    fun `estimateSplitTransactionFeeSats targets the same rate PsbtSummary reports for the resulting transaction`() {
        val sourceInput = deriveWalletAddress(TEST_WORDS, TEST_PASSPHRASE, WalletNetwork.MAINNET, account = 0, chain = 0, index = 0)
        val targetFeeRate = 5.0

        // The split planner picks a fee BEFORE the transaction exists, using
        // only input/output counts. Fund the built transaction with exactly
        // that fee, then ask PsbtSummary (which measures the ACTUAL built
        // transaction's bytes) what rate that produced — since both sides
        // share estimateTransactionVBytes, and this transaction's shape
        // (1 dummy-length input, 2 fixed 22-byte outputs, non-RBF sequence)
        // exactly matches what estimateSplitTransactionFeeSats assumed, the
        // measured rate should reproduce the targeted one almost exactly.
        val fee = estimateSplitTransactionFeeSats(inputCount = 1, outputCount = 2, feeRateSatsPerVByte = targetFeeRate)

        val builtBytes = buildUnsignedPsbt(
            inputs = listOf(
                PsbtInputPlan(
                    txid = "33".repeat(32),
                    vout = 0L,
                    amountSats = 100_000L,
                    scriptPubKey = sourceInput.scriptPubKey,
                    derivation = sourceInput.derivation,
                ),
            ),
            outputs = listOf(
                PsbtOutputPlan(amountSats = 50_000L - fee, scriptPubKey = ByteArray(22) { 0 }),
                PsbtOutputPlan(amountSats = 50_000L, scriptPubKey = ByteArray(22) { 0 }),
            ),
            rbf = false,
        )

        val summary = computePsbtSummary(builtBytes, knownNetwork = WalletNetwork.MAINNET)
        assertTrue(Math.abs(summary.estimatedFeeRateSatsPerVByte!! - targetFeeRate) < 0.05)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
