package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers signPsbt's P2WPKH (single-sig, BIP84) branch, added alongside the
 * original P2WSH-only behavior already covered by PsbtSigningTest. Every
 * hex value below was independently computed in Python by chaining the
 * same already-verified pieces PsbtSigningTest.kt used (BIP32 derivation
 * reproducing this codebase's known master fingerprint 73c5da0a for the
 * standard test mnemonic, RFC6979 signing cross-checked against the
 * independent Python `ecdsa` library including verifying the resulting
 * signature against the derived pubkey), plus this codebase's own hash160.
 */
class PsbtSigningP2wpkhTest {

    companion object {
        private val TEST_WORDS = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        private const val TEST_PASSPHRASE = ""

        // 84'/0'/0'/0/0 (raw uint32 path elements, hardened bit included where applicable).
        private val DERIVATION_PATH = listOf(2147483732L, 2147483648L, 2147483648L, 0L, 0L)
        private const val MASTER_FINGERPRINT_HEX = "73c5da0a"
        private const val DIFFERENT_FINGERPRINT_HEX = "00000000"

        private const val EXPECTED_PUBKEY_HEX = "0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c"
        private const val SCRIPT_PUBKEY_HEX = "0014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2"
        private const val WITNESS_UTXO_AMOUNT = 199909013L
        private const val EXPECTED_PARTIAL_SIG_HEX = "3045022100ec7501838a5b3d24e0ed7ced2ca9ca22fb198ef5751b5a5352e8928fd8763cec0220346ff4f1d0c9e96f5d598f6f72a8e1120b22f4442757b8c72edfdda9c3738dde01"

        private const val UNSIGNED_TX_HEX = "0200000001279a2323a5dfb51fc45f220fa58b0fc13e1e3342792a85d7e36cd6333b5cbc390000000000ffffffff01a05aea0b000000001976a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac00000000"

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
        private fun bip32DerivationValue(fingerprintHex: String): ByteArray {
            val fingerprint = fingerprintHex.hexToBytes()
            val pathBytes = DERIVATION_PATH.fold(ByteArray(0)) { acc, element -> acc + element.toUInt32LE() }
            return fingerprint + pathBytes
        }
        private fun buildMatchingInputMap(fingerprintHex: String = MASTER_FINGERPRINT_HEX): PsbtMap {
            val pubkeyBytes = EXPECTED_PUBKEY_HEX.hexToBytes()
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
                global = PsbtMap(emptyList()),
                inputs = inputs,
                outputs = unsignedTx.outputs.map { PsbtMap(emptyList()) },
            )
        }
        private fun testMasterKey(): Bip32ExtendedPrivateKey =
            bip32MasterKeyFromSeed(deriveSeed(TEST_WORDS, TEST_PASSPHRASE).bytes)
    }

    @Test
    fun `signPsbt adds a correct partial signature for a P2WPKH input this device's key matches`() {
        val psbt = buildPsbt(listOf(buildMatchingInputMap()))
        val signed = signPsbt(psbt, testMasterKey())

        val partialSigs = signed.inputs[0].partialSigs()
        assertEquals(1, partialSigs.size)
        assertEquals(EXPECTED_PUBKEY_HEX, partialSigs[0].pubkey.toHex())
        assertEquals(EXPECTED_PARTIAL_SIG_HEX, partialSigs[0].signature.toHex())
    }

    @Test
    fun `signPsbt does not sign a P2WPKH input whose bip32_derivation names a different fingerprint`() {
        val psbt = buildPsbt(listOf(buildMatchingInputMap(DIFFERENT_FINGERPRINT_HEX)))
        val signed = signPsbt(psbt, testMasterKey())

        assertTrue(signed.inputs[0].partialSigs().isEmpty())
    }

    @Test
    fun `signPsbt does not sign a P2WPKH input that already has a matching partial_sig`() {
        val pubkeyBytes = EXPECTED_PUBKEY_HEX.hexToBytes()
        val existingSig = byteArrayOf(0x00)
        val map = PsbtMap(
            entries = buildMatchingInputMap().entries + PsbtKeyValue(keyType = 0x02, keyData = pubkeyBytes, value = existingSig),
        )
        val psbt = buildPsbt(listOf(map))
        val signed = signPsbt(psbt, testMasterKey())

        val partialSigs = signed.inputs[0].partialSigs()
        assertEquals(1, partialSigs.size)
        assertEquals(existingSig.toHex(), partialSigs[0].signature.toHex())
    }
}
