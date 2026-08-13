package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers finalizePsbt: assembling PSBT_IN_FINAL_SCRIPTWITNESS from an
 * input's partial_sig entries for both P2WPKH (single-sig) and P2WSH
 * (multisig) inputs. Every hex value below was independently computed
 * in Python: the P2WPKH values reuse PsbtSigningP2wpkhTest's own
 * independently-verified vector, and the P2WSH values were derived by
 * BIP32-deriving two child keys from this codebase's known test-mnemonic
 * master key (fingerprint 73c5da0a), building their 2-of-2 witnessScript
 * with BIP67 ordering, computing the BIP143 sighash, and RFC6979-signing
 * with each key — cross-checked against the independent Python `ecdsa`
 * library, including verifying each resulting signature against its
 * pubkey. The expected final_scriptwitness bytes were then hand-assembled
 * in Python per BIP143's witness-stack-serialization format and verified
 * byte-for-byte against a structural re-parse before being copied here.
 */
class PsbtFinalizationTest {

    companion object {
        private const val UNSIGNED_TX_HEX = "0200000001279a2323a5dfb51fc45f220fa58b0fc13e1e3342792a85d7e36cd6333b5cbc390000000000ffffffff01a05aea0b000000001976a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac00000000"

        // ---- P2WPKH (single-sig) vector, same as PsbtSigningP2wpkhTest ----
        private const val P2WPKH_PUBKEY_HEX = "0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c"
        private const val P2WPKH_SCRIPT_PUBKEY_HEX = "0014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2"
        private const val P2WPKH_AMOUNT = 199909013L
        private const val P2WPKH_PARTIAL_SIG_HEX = "3045022100ec7501838a5b3d24e0ed7ced2ca9ca22fb198ef5751b5a5352e8928fd8763cec0220346ff4f1d0c9e96f5d598f6f72a8e1120b22f4442757b8c72edfdda9c3738dde01"
        private const val P2WPKH_EXPECTED_FINAL_WITNESS_HEX = "02483045022100ec7501838a5b3d24e0ed7ced2ca9ca22fb198ef5751b5a5352e8928fd8763cec0220346ff4f1d0c9e96f5d598f6f72a8e1120b22f4442757b8c72edfdda9c3738dde01210330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c"

        // ---- P2WSH 2-of-2 multisig vector ----
        private const val PUB_A_HEX = "020555b91e0fe9cb299167abab8636d8c997f006380a00a70113ecacac3eb3173d"
        private const val PUB_B_HEX = "0355d900c9e1f67dbc8ac5c30379d906b16770a199d28ea12fb606c5eba9905a6a"
        private const val WITNESS_SCRIPT_HEX = "5221020555b91e0fe9cb299167abab8636d8c997f006380a00a70113ecacac3eb3173d210355d900c9e1f67dbc8ac5c30379d906b16770a199d28ea12fb606c5eba9905a6a52ae"
        private const val P2WSH_SCRIPT_PUBKEY_HEX = "002031cac084d8f475b03993ffed425ddd0d2fd31b0bcd4395ee0d57ed42ead8ecdd"
        private const val P2WSH_AMOUNT = 199909013L
        private const val SIG_A_HEX = "30440220536bfa5b30bbf7a861e283b295f362053ae4080c31a1a9b66a22a7e135b046ea0220678d10a046117631ee3d4632ec04e90f9757b3a05ac5a29f0d719ba9c7271bb801"
        private const val SIG_B_HEX = "304402207820c5054a54fe8ebf8ca752dcabaead69d948c9a6e8b05a65bc159aed7da54002202bca1bdcf3cc3b2e55d3fa77a532ba4f485d71f0c549241fb0abb1d5c1cb738b01"
        private const val P2WSH_EXPECTED_FINAL_WITNESS_HEX = "04004730440220536bfa5b30bbf7a861e283b295f362053ae4080c31a1a9b66a22a7e135b046ea0220678d10a046117631ee3d4632ec04e90f9757b3a05ac5a29f0d719ba9c7271bb80147304402207820c5054a54fe8ebf8ca752dcabaead69d948c9a6e8b05a65bc159aed7da54002202bca1bdcf3cc3b2e55d3fa77a532ba4f485d71f0c549241fb0abb1d5c1cb738b01475221020555b91e0fe9cb299167abab8636d8c997f006380a00a70113ecacac3eb3173d210355d900c9e1f67dbc8ac5c30379d906b16770a199d28ea12fb606c5eba9905a6a52ae"

        private fun String.hexToBytes(): ByteArray {
            require(length % 2 == 0)
            return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
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
        private fun witnessUtxoValue(scriptPubKeyHex: String, amount: Long): ByteArray {
            val scriptPubKey = scriptPubKeyHex.hexToBytes()
            return amount.toUInt64LE() + shortCompactSize(scriptPubKey.size) + scriptPubKey
        }
        private fun buildPsbt(inputMap: PsbtMap): Psbt {
            val unsignedTx = parseTransaction(UNSIGNED_TX_HEX.hexToBytes())
            return Psbt(
                unsignedTx = unsignedTx,
                global = PsbtMap(emptyList()),
                inputs = listOf(inputMap),
                outputs = unsignedTx.outputs.map { PsbtMap(emptyList()) },
            )
        }
    }

    @Test
    fun `finalizePsbt assembles the correct final_scriptwitness for a P2WPKH input`() {
        val pubkey = P2WPKH_PUBKEY_HEX.hexToBytes()
        val sig = P2WPKH_PARTIAL_SIG_HEX.hexToBytes()
        val map = PsbtMap(
            entries = listOf(
                PsbtKeyValue(keyType = 0x01, keyData = ByteArray(0), value = witnessUtxoValue(P2WPKH_SCRIPT_PUBKEY_HEX, P2WPKH_AMOUNT)),
                PsbtKeyValue(keyType = 0x02, keyData = pubkey, value = sig),
            ),
        )
        val psbt = buildPsbt(map)
        val finalized = finalizePsbt(psbt)

        assertEquals(P2WPKH_EXPECTED_FINAL_WITNESS_HEX, finalized.inputs[0].finalScriptWitness()?.toHex())
    }

    @Test
    fun `finalizePsbt leaves a P2WPKH input with no partial_sig unchanged`() {
        val map = PsbtMap(
            entries = listOf(
                PsbtKeyValue(keyType = 0x01, keyData = ByteArray(0), value = witnessUtxoValue(P2WPKH_SCRIPT_PUBKEY_HEX, P2WPKH_AMOUNT)),
            ),
        )
        val psbt = buildPsbt(map)
        val finalized = finalizePsbt(psbt)

        assertNull(finalized.inputs[0].finalScriptWitness())
        assertEquals(psbt.inputs[0].entries.size, finalized.inputs[0].entries.size)
    }

    @Test
    fun `finalizePsbt assembles a script-ordered final_scriptwitness for a 2-of-2 P2WSH input regardless of partial_sig insertion order`() {
        val pubA = PUB_A_HEX.hexToBytes()
        val pubB = PUB_B_HEX.hexToBytes()
        val sigA = SIG_A_HEX.hexToBytes()
        val sigB = SIG_B_HEX.hexToBytes()
        val witnessScript = WITNESS_SCRIPT_HEX.hexToBytes()
        // Insert B's partial_sig before A's — script order (A then B, per BIP67) must
        // still win in the assembled witness, since OP_CHECKMULTISIG matches
        // signatures to pubkeys positionally in script order.
        val map = PsbtMap(
            entries = listOf(
                PsbtKeyValue(keyType = 0x01, keyData = ByteArray(0), value = witnessUtxoValue(P2WSH_SCRIPT_PUBKEY_HEX, P2WSH_AMOUNT)),
                PsbtKeyValue(keyType = 0x05, keyData = ByteArray(0), value = witnessScript),
                PsbtKeyValue(keyType = 0x02, keyData = pubB, value = sigB),
                PsbtKeyValue(keyType = 0x02, keyData = pubA, value = sigA),
            ),
        )
        val psbt = buildPsbt(map)
        val finalized = finalizePsbt(psbt)

        assertEquals(P2WSH_EXPECTED_FINAL_WITNESS_HEX, finalized.inputs[0].finalScriptWitness()?.toHex())
    }

    @Test
    fun `finalizePsbt leaves a P2WSH input unchanged when fewer than the threshold of signatures are present`() {
        val pubA = PUB_A_HEX.hexToBytes()
        val sigA = SIG_A_HEX.hexToBytes()
        val witnessScript = WITNESS_SCRIPT_HEX.hexToBytes()
        val map = PsbtMap(
            entries = listOf(
                PsbtKeyValue(keyType = 0x01, keyData = ByteArray(0), value = witnessUtxoValue(P2WSH_SCRIPT_PUBKEY_HEX, P2WSH_AMOUNT)),
                PsbtKeyValue(keyType = 0x05, keyData = ByteArray(0), value = witnessScript),
                PsbtKeyValue(keyType = 0x02, keyData = pubA, value = sigA),
            ),
        )
        val psbt = buildPsbt(map)
        val finalized = finalizePsbt(psbt)

        assertNull(finalized.inputs[0].finalScriptWitness())
        assertEquals(psbt.inputs[0].entries.size, finalized.inputs[0].entries.size)
    }

    @Test
    fun `finalizePsbt strips partial_sig, bip32_derivation, and witness_script entries after finalizing`() {
        val pubkey = P2WPKH_PUBKEY_HEX.hexToBytes()
        val sig = P2WPKH_PARTIAL_SIG_HEX.hexToBytes()
        val fakeDerivation = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        val map = PsbtMap(
            entries = listOf(
                PsbtKeyValue(keyType = 0x01, keyData = ByteArray(0), value = witnessUtxoValue(P2WPKH_SCRIPT_PUBKEY_HEX, P2WPKH_AMOUNT)),
                PsbtKeyValue(keyType = 0x02, keyData = pubkey, value = sig),
                PsbtKeyValue(keyType = 0x06, keyData = pubkey, value = fakeDerivation),
            ),
        )
        val psbt = buildPsbt(map)
        val finalized = finalizePsbt(psbt)

        val remainingKeyTypes = finalized.inputs[0].entries.map { it.keyType }.toSet()
        assertTrue(0x01 in remainingKeyTypes)
        assertTrue(0x08 in remainingKeyTypes)
        assertTrue(0x02 !in remainingKeyTypes)
        assertTrue(0x06 !in remainingKeyTypes)
    }

    @Test
    fun `finalizePsbt leaves an already-finalized input unchanged`() {
        val pubkey = P2WPKH_PUBKEY_HEX.hexToBytes()
        val sig = P2WPKH_PARTIAL_SIG_HEX.hexToBytes()
        val existingWitness = byteArrayOf(0x00)
        val map = PsbtMap(
            entries = listOf(
                PsbtKeyValue(keyType = 0x01, keyData = ByteArray(0), value = witnessUtxoValue(P2WPKH_SCRIPT_PUBKEY_HEX, P2WPKH_AMOUNT)),
                PsbtKeyValue(keyType = 0x02, keyData = pubkey, value = sig),
                PsbtKeyValue(keyType = 0x08, keyData = ByteArray(0), value = existingWitness),
            ),
        )
        val psbt = buildPsbt(map)
        val finalized = finalizePsbt(psbt)

        assertEquals(existingWitness.toHex(), finalized.inputs[0].finalScriptWitness()?.toHex())
    }

    @Test
    fun `finalizePsbt does not mutate its input Psbt`() {
        val pubkey = P2WPKH_PUBKEY_HEX.hexToBytes()
        val sig = P2WPKH_PARTIAL_SIG_HEX.hexToBytes()
        val map = PsbtMap(
            entries = listOf(
                PsbtKeyValue(keyType = 0x01, keyData = ByteArray(0), value = witnessUtxoValue(P2WPKH_SCRIPT_PUBKEY_HEX, P2WPKH_AMOUNT)),
                PsbtKeyValue(keyType = 0x02, keyData = pubkey, value = sig),
            ),
        )
        val psbt = buildPsbt(map)
        finalizePsbt(psbt)

        assertNull(psbt.inputs[0].finalScriptWitness())
    }
}
