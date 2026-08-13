package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PsbtSigningTest {

    companion object {
        private val TEST_WORDS = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        private const val TEST_PASSPHRASE = ""
        private const val EXPECTED_PUBKEY_HEX = "03dc1953c2756c7c58d4f48ca1bbba767f414fd236bf4d662b67721ac626c514e0"
        private const val EXPECTED_PARTIAL_SIG_HEX = "304402203e3e7df5ed761e3606f257b0b0c8e018062060bf5ca55955f9c983a0a2c8daca02202bd1f0d1f2192c6c16146ca2c7c4a62d303420aca86a9f2459a31a4258c4405b01"
        private const val MASTER_FINGERPRINT_HEX = "73c5da0a"
        private const val DIFFERENT_FINGERPRINT_HEX = "00000000"
        private const val WITNESS_SCRIPT_HEX = "522103b1341ccba7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd462103de55d1e1dac805e3f8a58c1fbf9b94c02f3dbaafe127fefca4995f26f82083bd52ae"
        private const val SCRIPT_PUBKEY_HEX = "a9146345200f68d189e1adc0df1c4d16ea8f14c0dbeb87"
        private const val WITNESS_UTXO_AMOUNT = 199909013L
        // Raw uint32 path elements (each already includes the 0x80000000
        // hardened bit where applicable) for 48'/0'/0'/2'/0/0.
        private val DERIVATION_PATH = listOf(2147483696L, 2147483648L, 2147483648L, 2147483650L, 0L, 0L)

        // The real BIP174-derived unsigned tx PsbtTest.kt/SegwitSighashTest.kt
        // already use — exactly one input, one output.
        private const val UNSIGNED_TX_HEX = "0200000001279a2323a5dfb51fc45f220fa58b0fc13e1e3342792a85d7e36cd6333b5cbc390000000000ffffffff01a05aea0b000000001976a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac00000000"

        private fun String.hexToBytes(): ByteArray {
            require(length % 2 == 0)
            return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun Long.toUInt32LE(): ByteArray = byteArrayOf(
            (this and 0xFF).toByte(),
            ((this shr 8) and 0xFF).toByte(),
            ((this shr 16) and 0xFF).toByte(),
            ((this shr 24) and 0xFF).toByte(),
        )

        private fun Long.toUInt64LE(): ByteArray = byteArrayOf(
            (this and 0xFF).toByte(),
            ((this shr 8) and 0xFF).toByte(),
            ((this shr 16) and 0xFF).toByte(),
            ((this shr 24) and 0xFF).toByte(),
            ((this shr 32) and 0xFF).toByte(),
            ((this shr 40) and 0xFF).toByte(),
            ((this shr 48) and 0xFF).toByte(),
            ((this shr 56) and 0xFF).toByte(),
        )

        // scriptPubKey here is always well under 253 bytes, so only the
        // single-byte compact-size form is needed for these fixtures.
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
            val witnessScriptBytes = WITNESS_SCRIPT_HEX.hexToBytes()
            return PsbtMap(
                entries = listOf(
                    PsbtKeyValue(keyType = 0x01, keyData = ByteArray(0), value = witnessUtxoValue()),
                    PsbtKeyValue(keyType = 0x05, keyData = ByteArray(0), value = witnessScriptBytes),
                    PsbtKeyValue(keyType = 0x06, keyData = pubkeyBytes, value = bip32DerivationValue(fingerprintHex)),
                ),
            )
        }

        private fun buildPsbt(inputs: List<PsbtMap>, unsignedTxHex: String = UNSIGNED_TX_HEX): Psbt {
            val unsignedTx = parseTransaction(unsignedTxHex.hexToBytes())
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
    fun `signPsbt adds a correct partial signature for an input this device's key matches`() {
        val psbt = buildPsbt(listOf(buildMatchingInputMap()))
        val signed = signPsbt(psbt, testMasterKey())

        val partialSigs = signed.inputs[0].partialSigs()
        assertEquals(1, partialSigs.size)
        assertEquals(EXPECTED_PUBKEY_HEX, partialSigs[0].pubkey.toHex())
        assertEquals(EXPECTED_PARTIAL_SIG_HEX, partialSigs[0].signature.toHex())
    }

    @Test
    fun `signPsbt preserves every other entry in the signed input's map`() {
        val psbt = buildPsbt(listOf(buildMatchingInputMap()))
        val signed = signPsbt(psbt, testMasterKey())

        // TxOut/PsbtBip32Derivation both carry ByteArray fields, and a Kotlin
        // data class's generated equals() compares those by REFERENCE, not
        // content — comparing via hex strings instead avoids that trap.
        assertEquals(psbt.inputs[0].witnessUtxo()?.valueSats, signed.inputs[0].witnessUtxo()?.valueSats)
        assertEquals(psbt.inputs[0].witnessUtxo()?.scriptPubKey?.toHex(), signed.inputs[0].witnessUtxo()?.scriptPubKey?.toHex())
        assertEquals(psbt.inputs[0].witnessScript()?.toHex(), signed.inputs[0].witnessScript()?.toHex())
        assertEquals(
            psbt.inputs[0].bip32Derivations().map { it.pubkey.toHex() to it.masterFingerprint.toHex() },
            signed.inputs[0].bip32Derivations().map { it.pubkey.toHex() to it.masterFingerprint.toHex() },
        )
    }

    @Test
    fun `signPsbt does not sign an input whose bip32_derivation names a different fingerprint`() {
        val psbt = buildPsbt(listOf(buildMatchingInputMap(DIFFERENT_FINGERPRINT_HEX)))
        val signed = signPsbt(psbt, testMasterKey())

        assertTrue(signed.inputs[0].partialSigs().isEmpty())
    }

    @Test
    fun `signPsbt does not sign an input that already has a partial_sig for the matching pubkey`() {
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

    @Test
    fun `signPsbt leaves an input with no witness_script unchanged`() {
        val pubkeyBytes = EXPECTED_PUBKEY_HEX.hexToBytes()
        val map = PsbtMap(
            entries = listOf(
                PsbtKeyValue(keyType = 0x01, keyData = ByteArray(0), value = witnessUtxoValue()),
                PsbtKeyValue(keyType = 0x06, keyData = pubkeyBytes, value = bip32DerivationValue(MASTER_FINGERPRINT_HEX)),
            ),
        )
        val psbt = buildPsbt(listOf(map))
        val signed = signPsbt(psbt, testMasterKey())

        assertTrue(signed.inputs[0].partialSigs().isEmpty())
        assertEquals(psbt.inputs[0].entries.size, signed.inputs[0].entries.size)
    }

    @Test
    fun `signPsbt with multiple inputs only signs the one this device's key matches`() {
        // Needs its own 2-input unsigned tx — computeSegwitSighash indexes
        // unsignedTx.inputs[inputIndex] directly, so it must have as many
        // inputs as the Psbt being signed.
        val singleInputTx = parseTransaction(UNSIGNED_TX_HEX.hexToBytes())
        val twoInputTx = singleInputTx.copy(inputs = singleInputTx.inputs + singleInputTx.inputs[0].copy(previousVout = 1L))

        val map0 = buildMatchingInputMap()
        val map1 = buildMatchingInputMap(DIFFERENT_FINGERPRINT_HEX)
        val psbt = Psbt(
            unsignedTx = twoInputTx,
            global = PsbtMap(emptyList()),
            inputs = listOf(map0, map1),
            outputs = twoInputTx.outputs.map { PsbtMap(emptyList()) },
        )
        val signed = signPsbt(psbt, testMasterKey())

        assertEquals(1, signed.inputs[0].partialSigs().size)
        assertTrue(signed.inputs[1].partialSigs().isEmpty())
    }

    @Test
    fun `signPsbt does not mutate its input Psbt`() {
        val psbt = buildPsbt(listOf(buildMatchingInputMap()))
        signPsbt(psbt, testMasterKey())

        assertTrue(psbt.inputs[0].partialSigs().isEmpty())
    }
}
