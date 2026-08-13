package org.mega.entropycore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers verifyVaultChangeOutput: cryptographic verification that a PSBT
 * output really is change back to a known vault — versus the PSBT's own
 * (coordinator-controlled, forgeable) metadata claims. Fixtures derive two
 * real cosigner keys from the two standard test mnemonics at m/48'/0'/0'/2'.
 */
class VaultChangeVerificationTest {

    companion object {
        private val WORDS_A = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        private val WORDS_B = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote".split(" ")

        private val COSIGNER_A = deriveMultisigCosignerAccountKeys(
            WORDS_A, "", MultisigScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0,
        )
        private val COSIGNER_B = deriveMultisigCosignerAccountKeys(
            WORDS_B, "", MultisigScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0,
        )
        private val COSIGNERS = listOf(
            MultisigCosignerOrigin(COSIGNER_A.masterFingerprint, COSIGNER_A.derivationPath, COSIGNER_A.extendedPublicKey),
            MultisigCosignerOrigin(COSIGNER_B.masterFingerprint, COSIGNER_B.derivationPath, COSIGNER_B.extendedPublicKey),
        )
        private const val THRESHOLD = 2
        private val NETWORK = WalletNetwork.MAINNET

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
        private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private fun Long.toUInt32LE(): ByteArray = byteArrayOf(
            (this and 0xFF).toByte(), ((this shr 8) and 0xFF).toByte(),
            ((this shr 16) and 0xFF).toByte(), ((this shr 24) and 0xFF).toByte(),
        )

        /** The vault's P2WSH scriptPubKey at [chain]/[index], plus the output-map
         * derivations a coordinator would attach for that output. */
        private fun vaultOutput(chain: Long, index: Long): Pair<ByteArray, PsbtMap> {
            val entries = mutableListOf<PsbtKeyValue>()
            val pubkeys = mutableListOf<ByteArray>()
            listOf(COSIGNER_A, COSIGNER_B).forEach { cosigner ->
                val accountKey = parseExtendedPublicKey(cosigner.extendedPublicKey)
                val pubkey = accountKey.deriveChild(chain).deriveChild(index).publicKey
                pubkeys += pubkey
                val path = listOf(
                    HARDENED_OFFSET + 48L, HARDENED_OFFSET + 0L, HARDENED_OFFSET + 0L, HARDENED_OFFSET + 2L, chain, index,
                )
                val value = cosigner.masterFingerprint.hexToBytes() +
                    path.fold(ByteArray(0)) { acc, el -> acc + el.toUInt32LE() }
                entries += PsbtKeyValue(0x02, pubkey, value) // PSBT_OUT_BIP32_DERIVATION
            }
            val witnessScript = buildMultisigWitnessScript(THRESHOLD, sortPublicKeysBip67(pubkeys))
            val scriptPubKey = byteArrayOf(0x00, 0x20) + sha256(witnessScript)
            return scriptPubKey to PsbtMap(entries)
        }
    }

    @Test
    fun `a genuine change output (chain 1) verifies`() {
        val (scriptPubKey, outputMap) = vaultOutput(chain = 1, index = 0)
        assertTrue(verifyVaultChangeOutput(scriptPubKey, outputMap, THRESHOLD, COSIGNERS, NETWORK))
    }

    @Test
    fun `a genuine change output at a later index verifies`() {
        val (scriptPubKey, outputMap) = vaultOutput(chain = 1, index = 7)
        assertTrue(verifyVaultChangeOutput(scriptPubKey, outputMap, THRESHOLD, COSIGNERS, NETWORK))
    }

    @Test
    fun `a receive-chain (chain 0) output is NOT change`() {
        val (scriptPubKey, outputMap) = vaultOutput(chain = 0, index = 0)
        assertFalse(verifyVaultChangeOutput(scriptPubKey, outputMap, THRESHOLD, COSIGNERS, NETWORK))
    }

    @Test
    fun `an output whose scriptPubKey merely carries matching fingerprints but pays elsewhere is NOT change`() {
        // The coordinator-claims-its-change attack: take the real change
        // output's derivation metadata but point the label at an unrelated
        // scriptPubKey (an address the attacker controls).
        val (_, outputMap) = vaultOutput(chain = 1, index = 0)
        val attackerScriptPubKey = "0014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2".hexToBytes() // some P2WPKH
        assertFalse(verifyVaultChangeOutput(attackerScriptPubKey, outputMap, THRESHOLD, COSIGNERS, NETWORK))
    }

    @Test
    fun `a wrong-network vault never verifies a mainnet-shaped output`() {
        val (scriptPubKey, outputMap) = vaultOutput(chain = 1, index = 0)
        assertFalse(verifyVaultChangeOutput(scriptPubKey, outputMap, THRESHOLD, COSIGNERS, WalletNetwork.TESTNET))
    }

    @Test
    fun `an output missing one cosigner's derivation is NOT verified change`() {
        val (scriptPubKey, fullMap) = vaultOutput(chain = 1, index = 0)
        val onlyA = PsbtMap(fullMap.entries.filter {
            it.value.copyOfRange(0, 4).toHex() == COSIGNER_A.masterFingerprint
        })
        assertFalse(verifyVaultChangeOutput(scriptPubKey, onlyA, THRESHOLD, COSIGNERS, NETWORK))
    }

    @Test
    fun `an output with no derivations at all is NOT verified change`() {
        val (scriptPubKey, _) = vaultOutput(chain = 1, index = 0)
        assertFalse(verifyVaultChangeOutput(scriptPubKey, PsbtMap(emptyList()), THRESHOLD, COSIGNERS, NETWORK))
    }

    @Test
    fun `a tampered pubkey in a derivation is NOT verified change`() {
        val (scriptPubKey, outputMap) = vaultOutput(chain = 1, index = 0)
        val tampered = PsbtMap(
            outputMap.entries.mapIndexed { i, entry ->
                if (i == 0) entry.copy(keyData = PUBKEY_TAMPERED) else entry
            },
        )
        assertFalse(verifyVaultChangeOutput(scriptPubKey, tampered, THRESHOLD, COSIGNERS, NETWORK))
    }

    @Test
    fun `a wrong threshold is NOT verified change`() {
        val (scriptPubKey, outputMap) = vaultOutput(chain = 1, index = 0)
        // A 1-of-2 script pays to a different address than the 2-of-2 vault.
        assertFalse(verifyVaultChangeOutput(scriptPubKey, outputMap, 1, COSIGNERS, NETWORK))
    }

    private val PUBKEY_TAMPERED = "02b1341ccba7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd46".hexToBytes()
}
