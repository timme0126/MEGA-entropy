package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the v0.1.9 audit hardening of signPsbt:
 *  - any requested sighash type other than SIGHASH_ALL aborts signing entirely
 *  - a witnessScript the spent UTXO does not actually commit to is never signed
 *  - a P2WPKH input whose UTXO doesn't pay to the derived pubkey is never signed
 *  - an already-finalized input is never re-signed
 * Fixtures mirror PsbtSigningTest.kt (same real tx, same mnemonic 73c5da0a).
 */
class PsbtSigningHardeningTest {

    companion object {
        private val TEST_WORDS = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        private const val MASTER_FINGERPRINT_HEX = "73c5da0a"
        private const val EXPECTED_PUBKEY_HEX = "03dc1953c2756c7c58d4f48ca1bbba767f414fd236bf4d662b67721ac626c514e0"
        private const val WITNESS_SCRIPT_HEX = "522103b1341ccba7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd462103de55d1e1dac805e3f8a58c1fbf9b94c02f3dbaafe127fefca4995f26f82083bd52ae"
        private const val SCRIPT_PUBKEY_HEX = "0020771fd18ad459666dd49f3d564e3dbc42f4c84774e360ada16816a8ed488d5681"
        private const val P2WPKH_PUBKEY_HEX = "0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c"
        private const val P2WPKH_SCRIPT_PUBKEY_HEX = "0014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2"
        private const val WITNESS_UTXO_AMOUNT = 199909013L
        private const val UNSIGNED_TX_HEX = "0200000001279a2323a5dfb51fc45f220fa58b0fc13e1e3342792a85d7e36cd6333b5cbc390000000000ffffffff01a05aea0b000000001976a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac00000000"
        private val MULTISIG_DERIVATION_PATH = listOf(2147483696L, 2147483648L, 2147483648L, 2147483650L, 0L, 0L)
        private val P2WPKH_DERIVATION_PATH = listOf(2147483732L, 2147483648L, 2147483648L, 0L, 0L)

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
        private fun shortCompactSize(len: Int): ByteArray { require(len < 0xFD); return byteArrayOf(len.toByte()) }

        private fun witnessUtxoEntry(scriptPubKeyHex: String): PsbtKeyValue {
            val spk = scriptPubKeyHex.hexToBytes()
            return PsbtKeyValue(0x01, ByteArray(0), WITNESS_UTXO_AMOUNT.toUInt64LE() + shortCompactSize(spk.size) + spk)
        }

        private fun bip32DerivationEntry(pubkeyHex: String, path: List<Long>): PsbtKeyValue {
            val value = MASTER_FINGERPRINT_HEX.hexToBytes() +
                path.fold(ByteArray(0)) { acc, el -> acc + el.toUInt32LE() }
            return PsbtKeyValue(0x06, pubkeyHex.hexToBytes(), value)
        }

        private fun sighashTypeEntry(sighashType: Long): PsbtKeyValue =
            PsbtKeyValue(0x03, ByteArray(0), sighashType.toUInt32LE())

        private fun multisigInputMap(extra: List<PsbtKeyValue> = emptyList(), scriptPubKeyHex: String = SCRIPT_PUBKEY_HEX): PsbtMap =
            PsbtMap(
                listOf(
                    witnessUtxoEntry(scriptPubKeyHex),
                    PsbtKeyValue(0x05, ByteArray(0), WITNESS_SCRIPT_HEX.hexToBytes()),
                    bip32DerivationEntry(EXPECTED_PUBKEY_HEX, MULTISIG_DERIVATION_PATH),
                ) + extra,
            )

        private fun p2wpkhInputMap(extra: List<PsbtKeyValue> = emptyList(), scriptPubKeyHex: String = P2WPKH_SCRIPT_PUBKEY_HEX): PsbtMap =
            PsbtMap(
                listOf(
                    witnessUtxoEntry(scriptPubKeyHex),
                    bip32DerivationEntry(P2WPKH_PUBKEY_HEX, P2WPKH_DERIVATION_PATH),
                ) + extra,
            )

        private fun buildPsbt(inputMap: PsbtMap): Psbt {
            val unsignedTx = parseTransaction(UNSIGNED_TX_HEX.hexToBytes())
            return Psbt(unsignedTx, PsbtMap(emptyList()), listOf(inputMap), unsignedTx.outputs.map { PsbtMap(emptyList()) })
        }

        private fun testMasterKey(): Bip32ExtendedPrivateKey =
            bip32MasterKeyFromSeed(deriveSeed(TEST_WORDS, "").bytes)
    }

    @Test
    fun `sighash NONE request aborts signing entirely`() {
        val psbt = buildPsbt(multisigInputMap(extra = listOf(sighashTypeEntry(2))))
        assertThrows(IllegalArgumentException::class.java) { signPsbt(psbt, testMasterKey()) }
    }

    @Test
    fun `sighash SINGLE request aborts signing entirely`() {
        val psbt = buildPsbt(multisigInputMap(extra = listOf(sighashTypeEntry(3))))
        assertThrows(IllegalArgumentException::class.java) { signPsbt(psbt, testMasterKey()) }
    }

    @Test
    fun `sighash ALL plus ANYONECANPAY request aborts signing entirely`() {
        val psbt = buildPsbt(multisigInputMap(extra = listOf(sighashTypeEntry(0x81))))
        assertThrows(IllegalArgumentException::class.java) { signPsbt(psbt, testMasterKey()) }
    }

    @Test
    fun `an out-of-range sighash value aborts signing entirely`() {
        // 0x0100 fits the 4-byte LE wire field (unlike wider values) and is
        // still not a defined or supported sighash type.
        val psbt = buildPsbt(multisigInputMap(extra = listOf(sighashTypeEntry(0x0100L))))
        assertThrows(IllegalArgumentException::class.java) { signPsbt(psbt, testMasterKey()) }
    }

    @Test
    fun `explicit SIGHASH_ALL still signs fine`() {
        val psbt = buildPsbt(multisigInputMap(extra = listOf(sighashTypeEntry(1))))
        val signed = signPsbt(psbt, testMasterKey())
        assertEquals(1, signed.inputs[0].partialSigs().size)
    }

    @Test
    fun `a witnessScript the UTXO does not commit to is not signed`() {
        // UTXO scriptPubKey commits to a DIFFERENT script than the witnessScript
        // the input carries — the P2SH-wrapped form from the original BIP174
        // vector is exactly such a mismatch for bare-P2WSH signing.
        val p2shWrapped = "a9146345200f68d189e1adc0df1c4d16ea8f14c0dbeb87"
        val psbt = buildPsbt(multisigInputMap(scriptPubKeyHex = p2shWrapped))
        val signed = signPsbt(psbt, testMasterKey())
        assertTrue(signed.inputs[0].partialSigs().isEmpty())
    }

    @Test
    fun `a P2WPKH input paying a different pubkey hash is not signed`() {
        // scriptPubKey's program is hash160 of an unrelated pubkey (the
        // multisig fixture pubkey), not the one this device would sign with.
        val wrongProgram = "0014" + hash160(EXPECTED_PUBKEY_HEX.hexToBytes()).joinToString("") { "%02x".format(it) }
        val psbt = buildPsbt(p2wpkhInputMap(scriptPubKeyHex = wrongProgram))
        val signed = signPsbt(psbt, testMasterKey())
        assertTrue(signed.inputs[0].partialSigs().isEmpty())
    }

    @Test
    fun `a correctly bound P2WPKH input still signs fine`() {
        val psbt = buildPsbt(p2wpkhInputMap())
        val signed = signPsbt(psbt, testMasterKey())
        assertEquals(1, signed.inputs[0].partialSigs().size)
    }

    @Test
    fun `an already-finalized input is never re-signed`() {
        val finalized = PsbtMap(
            listOf(
                witnessUtxoEntry(SCRIPT_PUBKEY_HEX),
                PsbtKeyValue(0x08, ByteArray(0), byteArrayOf(0x02, 0x01, 0x02)), // final_scriptwitness (dummy content)
                bip32DerivationEntry(EXPECTED_PUBKEY_HEX, MULTISIG_DERIVATION_PATH),
            ),
        )
        val psbt = buildPsbt(finalized)
        val signed = signPsbt(psbt, testMasterKey())
        assertTrue(signed.inputs[0].partialSigs().isEmpty())
        assertEquals(3, signed.inputs[0].entries.size)
    }
}
