package org.mega.entropy.bbqr

import com.sparrowwallet.hummingbird.ResultType
import com.sparrowwallet.hummingbird.URDecoder
import com.sparrowwallet.hummingbird.registry.URPSBT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mega.entropycore.Psbt
import org.mega.entropycore.PsbtKeyValue
import org.mega.entropycore.PsbtMap
import org.mega.entropycore.TxIn
import org.mega.entropycore.TxOut
import org.mega.entropycore.Transaction
import org.mega.entropycore.assembleBbqrPartsAsBytes
import org.mega.entropycore.encodeBbqr
import org.mega.entropycore.isPsbtFullyFinalized
import org.mega.entropycore.parseBbqrPart
import org.mega.entropycore.serializePsbt
import org.mega.entropycore.signAndFinalizePsbt

/**
 * Proves BBQr and Blockchain Commons UR are interchangeable transports for
 * the exact same PSBT: decoding the same underlying PSBT bytes through
 * either encoding reaches byte-identical PSBT bytes, and signing each
 * reaches a byte-identical final result. Neither the BBQr scanner nor the
 * UR scanner (PsbtScanScreen.kt) contains any fingerprint or signing
 * policy logic of its own — both simply decode to a ByteArray and hand it
 * to the SAME onScanned callback, which the app wires to the same
 * PsbtReviewScreen/PsbtSignResultScreen pipeline every entropy-core
 * fingerprint-policy test already covers. This test exercises the
 * decoding primitives PsbtScanScreen.kt itself calls (parseBbqrPart /
 * assembleBbqrPartsAsBytes for BBQr; URDecoder / URPSBT for UR), the same
 * way that screen does, to prove the transport layer itself introduces no
 * divergence.
 */
class PsbtTransportParityTest {

    companion object {
        private val DERIVATION_PATH = listOf(2147483732L, 2147483648L, 2147483648L, 0L, 0L)
        private const val MASTER_FINGERPRINT_HEX = "73c5da0a"
        private const val EXPECTED_PUBKEY_HEX = "0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c"
        private const val SCRIPT_PUBKEY_HEX = "0014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2"
        private const val WITNESS_UTXO_AMOUNT = 199909013L
        private val TEST_WORDS = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

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

        /** A real, independently signable single-input P2WPKH PSBT — same
         * fixture shape used throughout entropy-core's own PSBT tests
         * (m/84'/0'/0'/0/0 of the standard test mnemonic). */
        private fun buildSignablePsbtBytes(): ByteArray {
            val scriptPubKey = SCRIPT_PUBKEY_HEX.hexToBytes()
            val witnessUtxoValue = WITNESS_UTXO_AMOUNT.toUInt64LE() + shortCompactSize(scriptPubKey.size) + scriptPubKey
            val pathBytes = DERIVATION_PATH.fold(ByteArray(0)) { acc, e -> acc + e.toUInt32LE() }
            val inputMap = PsbtMap(
                entries = listOf(
                    PsbtKeyValue(keyType = 0x01, keyData = ByteArray(0), value = witnessUtxoValue),
                    PsbtKeyValue(
                        keyType = 0x06,
                        keyData = EXPECTED_PUBKEY_HEX.hexToBytes(),
                        value = MASTER_FINGERPRINT_HEX.hexToBytes() + pathBytes,
                    ),
                ),
            )
            val unsignedTx = Transaction(
                version = 2L,
                inputs = listOf(TxIn(ByteArray(32) { 0x11 }, 0L, ByteArray(0), 0xffffffffL)),
                outputs = listOf(TxOut(50_000L, "76a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac".hexToBytes())),
                locktime = 0L,
            )
            val txBytes = serializeTx(unsignedTx)
            val psbt = Psbt(
                unsignedTx = unsignedTx,
                global = PsbtMap(listOf(PsbtKeyValue(keyType = 0x00, keyData = ByteArray(0), value = txBytes))),
                inputs = listOf(inputMap),
                outputs = listOf(PsbtMap(emptyList())),
            )
            return serializePsbt(psbt)
        }

        // Local legacy-tx serializer (mirrors entropy-core's own internal one) —
        // entropy-core's serializeTransaction is public, so use it directly.
        private fun serializeTx(tx: Transaction): ByteArray = org.mega.entropycore.serializeTransaction(tx)

        /** Mirrors PsbtScanScreen.kt's own BBQr accumulation exactly: parse
         * each frame, feed it to the shared accumulator, assemble once complete. */
        private fun decodeViaBbqr(psbtBytes: ByteArray): ByteArray {
            val frames = encodeBbqr('P', psbtBytes)
            var parts = emptyMap<Int, org.mega.entropycore.BbqrPart>()
            for (frame in frames) {
                val part = parseBbqrPart(frame) ?: error("frame did not parse as a BBQr part")
                parts = accumulateBbqrPart(parts, part).parts
            }
            return assembleBbqrPartsAsBytes(parts.values.sortedBy { it.index })
        }

        /** Mirrors PsbtScanScreen.kt's own UR handling exactly: single-shot
         * encode (this fixture's PSBT is small enough for one part, exactly
         * like a real single-input P2WPKH spend would be), decode via
         * URDecoder.receivePart, accept "psbt" (current registry type, what
         * this app's hummingbird version and Sparrow both emit). */
        private fun decodeViaUr(psbtBytes: ByteArray): ByteArray {
            val ur = URPSBT(psbtBytes).toUR()
            val encoded = com.sparrowwallet.hummingbird.UREncoder.encode(ur)
            val decoder = URDecoder()
            decoder.receivePart(encoded)
            val result = decoder.result ?: error("UR decoding did not complete in one part")
            require(result.type == ResultType.SUCCESS) { "UR decode failed: ${result.error}" }
            require(result.ur.type == "psbt" || result.ur.type == "crypto-psbt") { "unexpected UR type ${result.ur.type}" }
            return result.ur.toBytes()
        }
    }

    @Test
    fun `BBQr and UR decode the same PSBT to byte-identical bytes`() {
        val original = buildSignablePsbtBytes()
        val viaBbqr = decodeViaBbqr(original)
        val viaUr = decodeViaUr(original)

        assertTrue(viaBbqr.contentEquals(original))
        assertTrue(viaUr.contentEquals(original))
        assertTrue(viaBbqr.contentEquals(viaUr))
    }

    @Test
    fun `BBQr and UR delivered PSBTs sign to byte-identical results`() {
        val original = buildSignablePsbtBytes()
        val viaBbqr = decodeViaBbqr(original)
        val viaUr = decodeViaUr(original)

        val signedFromBbqr = signAndFinalizePsbt(viaBbqr, TEST_WORDS, "")
        val signedFromUr = signAndFinalizePsbt(viaUr, TEST_WORDS, "")

        assertTrue(isPsbtFullyFinalized(signedFromBbqr))
        assertTrue(isPsbtFullyFinalized(signedFromUr))
        assertEquals(signedFromBbqr.toHexString(), signedFromUr.toHexString())
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
