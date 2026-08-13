package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end coverage of the public PsbtWorkflow façade
 * (signAndFinalizePsbt / isPsbtFullyFinalized / extractFinalTransactionHex)
 * over the same independently-verified P2WPKH vector used by
 * PsbtSigningP2wpkhTest and PsbtFinalizationTest. The expected final
 * transaction hex was hand-assembled in Python from the unsigned tx's own
 * bytes plus the already-verified final_scriptwitness value, per BIP144's
 * witness serialization (version + 00 01 marker/flag + inputs (unchanged,
 * empty scriptSig) + outputs (unchanged) + per-input witness field +
 * locktime), then re-derived structurally (not just pasted) before being
 * used here.
 */
class PsbtWorkflowTest {

    companion object {
        private val TEST_WORDS = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        private const val TEST_PASSPHRASE = ""

        private val DERIVATION_PATH = listOf(2147483732L, 2147483648L, 2147483648L, 0L, 0L)
        private const val MASTER_FINGERPRINT_HEX = "73c5da0a"
        private const val EXPECTED_PUBKEY_HEX = "0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c"
        private const val SCRIPT_PUBKEY_HEX = "0014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2"
        private const val WITNESS_UTXO_AMOUNT = 199909013L

        private const val UNSIGNED_TX_HEX = "0200000001279a2323a5dfb51fc45f220fa58b0fc13e1e3342792a85d7e36cd6333b5cbc390000000000ffffffff01a05aea0b000000001976a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac00000000"
        private const val EXPECTED_FINAL_TX_HEX = "02000000000101279a2323a5dfb51fc45f220fa58b0fc13e1e3342792a85d7e36cd6333b5cbc390000000000ffffffff01a05aea0b000000001976a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac02483045022100ec7501838a5b3d24e0ed7ced2ca9ca22fb198ef5751b5a5352e8928fd8763cec0220346ff4f1d0c9e96f5d598f6f72a8e1120b22f4442757b8c72edfdda9c3738dde01210330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c00000000"

        private fun String.hexToBytes(): ByteArray {
            require(length % 2 == 0)
            return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
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
        private fun shortCompactSize(len: Int): ByteArray {
            require(len < 0xFD)
            return byteArrayOf(len.toByte())
        }
        private fun witnessUtxoValue(): ByteArray {
            val scriptPubKey = SCRIPT_PUBKEY_HEX.hexToBytes()
            return WITNESS_UTXO_AMOUNT.toUInt64LE() + shortCompactSize(scriptPubKey.size) + scriptPubKey
        }
        private fun bip32DerivationValue(): ByteArray {
            val fingerprint = MASTER_FINGERPRINT_HEX.hexToBytes()
            val pathBytes = DERIVATION_PATH.fold(ByteArray(0)) { acc, element -> acc + element.toUInt32LE() }
            return fingerprint + pathBytes
        }
        private fun buildUnsignedPsbtBytes(): ByteArray {
            val pubkeyBytes = EXPECTED_PUBKEY_HEX.hexToBytes()
            val inputMap = PsbtMap(
                entries = listOf(
                    PsbtKeyValue(keyType = 0x01, keyData = ByteArray(0), value = witnessUtxoValue()),
                    PsbtKeyValue(keyType = 0x06, keyData = pubkeyBytes, value = bip32DerivationValue()),
                ),
            )
            val unsignedTx = parseTransaction(UNSIGNED_TX_HEX.hexToBytes())
            val psbt = Psbt(
                unsignedTx = unsignedTx,
                global = PsbtMap(listOf(PsbtKeyValue(keyType = 0x00, keyData = ByteArray(0), value = UNSIGNED_TX_HEX.hexToBytes()))),
                inputs = listOf(inputMap),
                outputs = unsignedTx.outputs.map { PsbtMap(emptyList()) },
            )
            return serializePsbt(psbt)
        }
    }

    @Test
    fun `signAndFinalizePsbt fully finalizes a single P2WPKH input this device's key matches`() {
        val unsignedBytes = buildUnsignedPsbtBytes()
        val resultBytes = signAndFinalizePsbt(unsignedBytes, TEST_WORDS, TEST_PASSPHRASE)

        assertTrue(isPsbtFullyFinalized(resultBytes))
    }

    @Test
    fun `isPsbtFullyFinalized is false before signing`() {
        val unsignedBytes = buildUnsignedPsbtBytes()
        assertFalse(isPsbtFullyFinalized(unsignedBytes))
    }

    @Test
    fun `extractFinalTransactionHex returns null for a not-yet-finalized PSBT`() {
        val unsignedBytes = buildUnsignedPsbtBytes()
        assertNull(extractFinalTransactionHex(unsignedBytes))
    }

    @Test
    fun `extractFinalTransactionHex produces the correct BIP144 witness transaction after signing`() {
        val unsignedBytes = buildUnsignedPsbtBytes()
        val resultBytes = signAndFinalizePsbt(unsignedBytes, TEST_WORDS, TEST_PASSPHRASE)

        assertEquals(EXPECTED_FINAL_TX_HEX, extractFinalTransactionHex(resultBytes))
    }
}
