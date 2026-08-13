package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the v0.1.9 audit additions to computePsbtSummary: per-input sighash
 * surfacing (hasUnsupportedSighashType), negative-fee detection
 * (feeIsNegative), and network inference from derivation-path coin types.
 */
class PsbtSummaryHardeningTest {

    companion object {
        private const val UNSIGNED_TX_HEX = "0200000001279a2323a5dfb51fc45f220fa58b0fc13e1e3342792a85d7e36cd6333b5cbc390000000000ffffffff01a05aea0b000000001976a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac00000000"
        private const val WITNESS_UTXO_AMOUNT = 199909013L
        private val PUBKEY = "0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c".hexToBytes()
        private val P2WPKH_SCRIPT_PUBKEY = "0014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2".hexToBytes()

        private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
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

        private fun derivationEntry(coinTypeHardened: Long): PsbtKeyValue {
            val path = listOf(HARDENED_OFFSET + 84L, coinTypeHardened, HARDENED_OFFSET, 0L, 0L)
            val value = "73c5da0a".hexToBytes() + path.fold(ByteArray(0)) { acc, el -> acc + el.toUInt32LE() }
            return PsbtKeyValue(0x06, PUBKEY, value)
        }

        private fun buildPsbtBytes(
            inputEntries: List<PsbtKeyValue>,
            outputValueSats: Long? = null,
        ): ByteArray {
            val baseTx = parseTransaction(UNSIGNED_TX_HEX.hexToBytes())
            val tx = if (outputValueSats == null) baseTx else baseTx.copy(
                outputs = listOf(baseTx.outputs[0].copy(valueSats = outputValueSats)),
            )
            val psbt = Psbt(
                unsignedTx = tx,
                global = PsbtMap(listOf(PsbtKeyValue(0x00, ByteArray(0), serializeTransaction(tx)))),
                inputs = listOf(PsbtMap(inputEntries)),
                outputs = tx.outputs.map { PsbtMap(emptyList()) },
            )
            return serializePsbt(psbt)
        }

        private fun witnessUtxoEntry(): PsbtKeyValue =
            PsbtKeyValue(0x01, ByteArray(0), WITNESS_UTXO_AMOUNT.toUInt64LE() + byteArrayOf(P2WPKH_SCRIPT_PUBKEY.size.toByte()) + P2WPKH_SCRIPT_PUBKEY)
    }

    @Test
    fun `sighashType is surfaced per input and flags unsupported values`() {
        val supported = buildPsbtBytes(listOf(witnessUtxoEntry(), PsbtKeyValue(0x03, ByteArray(0), 1L.toUInt32LE())))
        assertFalse(computePsbtSummary(supported).hasUnsupportedSighashType)
        assertEquals(1L, computePsbtSummary(supported).inputs[0].sighashType)

        val unsupported = buildPsbtBytes(listOf(witnessUtxoEntry(), PsbtKeyValue(0x03, ByteArray(0), 3L.toUInt32LE())))
        val summary = computePsbtSummary(unsupported)
        assertTrue(summary.hasUnsupportedSighashType)
        assertEquals(3L, summary.inputs[0].sighashType)
    }

    @Test
    fun `absent sighash is neither surfaced nor flagged`() {
        val summary = computePsbtSummary(buildPsbtBytes(listOf(witnessUtxoEntry())))
        assertNull(summary.inputs[0].sighashType)
        assertFalse(summary.hasUnsupportedSighashType)
    }

    @Test
    fun `outputs exceeding inputs is detected as a negative fee`() {
        val tooExpensive = buildPsbtBytes(listOf(witnessUtxoEntry()), outputValueSats = WITNESS_UTXO_AMOUNT + 1)
        val summary = computePsbtSummary(tooExpensive)
        assertTrue(summary.feeIsNegative)
        assertNull(summary.estimatedFeeRateSatsPerVByte)

        val fine = computePsbtSummary(buildPsbtBytes(listOf(witnessUtxoEntry())))
        assertFalse(fine.feeIsNegative)
    }

    @Test
    fun `network is inferred mainnet from a 0-coin-type derivation path`() {
        val summary = computePsbtSummary(buildPsbtBytes(listOf(witnessUtxoEntry(), derivationEntry(HARDENED_OFFSET + 0L))))
        assertEquals(WalletNetwork.MAINNET, summary.network)
        assertTrue(summary.networkWasInferred)
        // Address decoding now happens despite no caller-supplied network.
        assertTrue(summary.outputs[0].address == null) // fixture output is P2PKH — not decodable by this app, hex fallback
    }

    @Test
    fun `network is inferred testnet from a 1-coin-type derivation path`() {
        val summary = computePsbtSummary(buildPsbtBytes(listOf(witnessUtxoEntry(), derivationEntry(HARDENED_OFFSET + 1L))))
        assertEquals(WalletNetwork.TESTNET, summary.network)
        assertTrue(summary.networkWasInferred)
    }

    @Test
    fun `network stays Unknown when no derivation paths exist`() {
        val summary = computePsbtSummary(buildPsbtBytes(listOf(witnessUtxoEntry())))
        assertNull(summary.network)
        assertFalse(summary.networkWasInferred)
    }

    @Test
    fun `a caller-supplied network always wins over inference`() {
        val summary = computePsbtSummary(
            buildPsbtBytes(listOf(witnessUtxoEntry(), derivationEntry(HARDENED_OFFSET + 1L))),
            knownNetwork = WalletNetwork.MAINNET,
        )
        assertEquals(WalletNetwork.MAINNET, summary.network)
        assertFalse(summary.networkWasInferred)
    }
}
