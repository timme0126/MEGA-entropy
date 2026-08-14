package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for a real Sparrow PSBT signing failure: PsbtMap.nonWitnessUtxo()
 * used a legacy-only transaction parser, so a non_witness_utxo blob referencing an
 * ancestor transaction that is ITSELF SegWit-serialized (marker 0x00, flag 0x01, one
 * witness stack per input) was misparsed — the marker byte read as a zero input
 * count and the flag byte as a one-output count, producing garbage that failed a
 * downstream sanity check with a misleading message. This is the norm, not an edge
 * case: any node's raw-transaction lookup returns the witness-inclusive form
 * whenever the referenced ancestor transaction itself carries witness data,
 * regardless of whether the CURRENT input being signed is itself segwit — which is
 * true for the overwhelming majority of real transactions today. Every input this
 * app signs is P2WPKH or bare P2WSH multisig, so every such input funded (even
 * indirectly) from a segwit history hit this bug. See
 * parsePreviousTransactionAllowingWitness (Transaction.kt) for the fix, and
 * PsbtMap.nonWitnessUtxo() (Psbt.kt) for where it's wired in.
 *
 * Fixture data (fingerprint, path, pubkey, scriptPubKey) matches
 * PsbtSigningP2wpkhTest/PsbtWorkflowTest: m/84'/0'/0'/0/0 of the standard test
 * mnemonic, independently verified there.
 */
class PsbtNonWitnessUtxoWitnessCompatTest {

    companion object {
        private val TEST_WORDS = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        private const val TEST_PASSPHRASE = ""
        private val DERIVATION_PATH = listOf(2147483732L, 2147483648L, 2147483648L, 0L, 0L)
        private const val MASTER_FINGERPRINT_HEX = "73c5da0a"
        private const val EXPECTED_PUBKEY_HEX = "0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c"
        private const val SCRIPT_PUBKEY_HEX = "0014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2"
        private const val UTXO_AMOUNT = 199909013L

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
        private fun compactSize(v: Int): ByteArray = byteArrayOf(v.toByte())

        private fun testMasterKey(): Bip32ExtendedPrivateKey =
            bip32MasterKeyFromSeed(deriveSeed(TEST_WORDS, TEST_PASSPHRASE).bytes)

        private fun bip32DerivationValue(fingerprintHex: String = MASTER_FINGERPRINT_HEX): ByteArray {
            val fingerprint = fingerprintHex.hexToBytes()
            val pathBytes = DERIVATION_PATH.fold(ByteArray(0)) { acc, element -> acc + element.toUInt32LE() }
            return fingerprint + pathBytes
        }

        /** Legacy serialization of a realistic 1-input, N-output ancestor
         * transaction (a zero-input ancestor is never valid on the real network,
         * and — not incidentally — collides with the marker/flag detection this
         * fixture exists to exercise correctly). */
        private fun legacyAncestorBytes(outputs: List<Pair<Long, ByteArray>>): ByteArray {
            var out = 2L.toUInt32LE()
            out += compactSize(1)
            out += ByteArray(32) { 0x77 }
            out += 0L.toUInt32LE()
            out += compactSize(0)
            out += 0xffffffffL.toUInt32LE()
            out += compactSize(outputs.size)
            for ((amount, spk) in outputs) {
                out += amount.toUInt64LE()
                out += compactSize(spk.size) + spk
            }
            out += 0L.toUInt32LE()
            return out
        }

        /** The SAME logical ancestor transaction as [legacyAncestorBytes], but
         * BIP144 witness-serialized — exactly what a real node's raw-transaction
         * lookup returns once the ancestor's own input carries witness data,
         * which is what Sparrow (and any segwit-aware coordinator) embeds into
         * non_witness_utxo. */
        private fun witnessAncestorBytes(outputs: List<Pair<Long, ByteArray>>): ByteArray {
            var out = 2L.toUInt32LE()
            out += byteArrayOf(0x00, 0x01) // marker, flag
            out += compactSize(1)
            out += ByteArray(32) { 0x77 }
            out += 0L.toUInt32LE()
            out += compactSize(0)
            out += 0xffffffffL.toUInt32LE()
            out += compactSize(outputs.size)
            for ((amount, spk) in outputs) {
                out += amount.toUInt64LE()
                out += compactSize(spk.size) + spk
            }
            out += compactSize(2) // 1 witness stack (this ancestor has 1 input), 2 items
            out += compactSize(71) + ByteArray(71) { 0x11 }
            out += compactSize(33) + ByteArray(33) { 0x22 }
            out += 0L.toUInt32LE()
            return out
        }

        private fun ancestorTxid(legacyBytes: ByteArray): ByteArray = doubleSha256(legacyBytes).reversedArray()

        private fun buildUnsignedTx(previousTxid: ByteArray, previousVout: Long): Transaction = Transaction(
            version = 2L,
            inputs = listOf(TxIn(previousTxid, previousVout, ByteArray(0), 0xffffffffL)),
            outputs = listOf(TxOut(50_000L, "76a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac".hexToBytes())),
            locktime = 0L,
        )

        private fun buildPsbt(unsignedTx: Transaction, inputMap: PsbtMap): Psbt = Psbt(
            unsignedTx = unsignedTx,
            global = PsbtMap(listOf(PsbtKeyValue(0x00, ByteArray(0), serializeTransaction(unsignedTx)))),
            inputs = listOf(inputMap),
            outputs = listOf(PsbtMap(emptyList())),
        )

        private fun buildPsbtBytes(unsignedTx: Transaction, inputMap: PsbtMap): ByteArray = serializePsbt(buildPsbt(unsignedTx, inputMap))

        private fun witnessUtxoValue(amount: Long, scriptPubKey: ByteArray): ByteArray =
            amount.toUInt64LE() + compactSize(scriptPubKey.size) + scriptPubKey
    }

    @Test
    fun `signs a Native SegWit input whose non_witness_utxo ancestor is itself SegWit-serialized`() {
        val scriptPubKey = SCRIPT_PUBKEY_HEX.hexToBytes()
        val witnessAncestor = witnessAncestorBytes(listOf(UTXO_AMOUNT to scriptPubKey))
        val legacyAncestor = legacyAncestorBytes(listOf(UTXO_AMOUNT to scriptPubKey))
        val txid = ancestorTxid(legacyAncestor)
        val unsignedTx = buildUnsignedTx(txid, 0L)
        val inputMap = PsbtMap(
            listOf(
                PsbtKeyValue(0x00, ByteArray(0), witnessAncestor),
                PsbtKeyValue(0x06, EXPECTED_PUBKEY_HEX.hexToBytes(), bip32DerivationValue()),
            ),
        )
        val signed = signAndFinalizePsbt(buildPsbtBytes(unsignedTx, inputMap), TEST_WORDS, TEST_PASSPHRASE)
        assertTrue(isPsbtFullyFinalized(signed))
    }

    @Test
    fun `signs when witness_utxo and a SegWit-serialized non_witness_utxo are both present and agree`() {
        // The real Sparrow shape: both UTXO representations included together.
        val scriptPubKey = SCRIPT_PUBKEY_HEX.hexToBytes()
        val witnessAncestor = witnessAncestorBytes(listOf(UTXO_AMOUNT to scriptPubKey))
        val legacyAncestor = legacyAncestorBytes(listOf(UTXO_AMOUNT to scriptPubKey))
        val txid = ancestorTxid(legacyAncestor)
        val unsignedTx = buildUnsignedTx(txid, 0L)
        val inputMap = PsbtMap(
            listOf(
                PsbtKeyValue(0x00, ByteArray(0), witnessAncestor),
                PsbtKeyValue(0x01, ByteArray(0), witnessUtxoValue(UTXO_AMOUNT, scriptPubKey)),
                PsbtKeyValue(0x06, EXPECTED_PUBKEY_HEX.hexToBytes(), bip32DerivationValue()),
            ),
        )
        val signed = signAndFinalizePsbt(buildPsbtBytes(unsignedTx, inputMap), TEST_WORDS, TEST_PASSPHRASE)
        assertTrue(isPsbtFullyFinalized(signed))
    }

    @Test
    fun `still signs when the non_witness_utxo ancestor is plain legacy with no witness data`() {
        val scriptPubKey = SCRIPT_PUBKEY_HEX.hexToBytes()
        val legacyAncestor = legacyAncestorBytes(listOf(UTXO_AMOUNT to scriptPubKey))
        val txid = ancestorTxid(legacyAncestor)
        val unsignedTx = buildUnsignedTx(txid, 0L)
        val inputMap = PsbtMap(
            listOf(
                PsbtKeyValue(0x00, ByteArray(0), legacyAncestor),
                PsbtKeyValue(0x06, EXPECTED_PUBKEY_HEX.hexToBytes(), bip32DerivationValue()),
            ),
        )
        val signed = signAndFinalizePsbt(buildPsbtBytes(unsignedTx, inputMap), TEST_WORDS, TEST_PASSPHRASE)
        assertTrue(isPsbtFullyFinalized(signed))
    }

    @Test
    fun `resolveInputUtxo throws when witness_utxo and a SegWit-serialized non_witness_utxo disagree on amount`() {
        val scriptPubKey = SCRIPT_PUBKEY_HEX.hexToBytes()
        val witnessAncestor = witnessAncestorBytes(listOf(UTXO_AMOUNT to scriptPubKey))
        val legacyAncestor = legacyAncestorBytes(listOf(UTXO_AMOUNT to scriptPubKey))
        val txid = ancestorTxid(legacyAncestor)
        val unsignedTx = buildUnsignedTx(txid, 0L)
        val inputMap = PsbtMap(
            listOf(
                PsbtKeyValue(0x00, ByteArray(0), witnessAncestor),
                PsbtKeyValue(0x01, ByteArray(0), witnessUtxoValue(UTXO_AMOUNT + 1, scriptPubKey)),
            ),
        )
        val e = assertThrows(IllegalArgumentException::class.java) { resolveInputUtxo(unsignedTx, 0, inputMap) }
        assertTrue(e.message.orEmpty().contains("disagrees"))
    }

    @Test
    fun `resolveInputUtxo throws when the non_witness_utxo txid does not match the outpoint, even for a SegWit-serialized ancestor`() {
        val scriptPubKey = SCRIPT_PUBKEY_HEX.hexToBytes()
        val witnessAncestor = witnessAncestorBytes(listOf(UTXO_AMOUNT to scriptPubKey))
        // Deliberately wrong outpoint — does NOT match witnessAncestor's real txid.
        val unsignedTx = buildUnsignedTx(ByteArray(32) { 0x55 }, 0L)
        val inputMap = PsbtMap(listOf(PsbtKeyValue(0x00, ByteArray(0), witnessAncestor)))
        val e = assertThrows(IllegalArgumentException::class.java) { resolveInputUtxo(unsignedTx, 0, inputMap) }
        assertTrue(e.message.orEmpty().contains("txid does not match"))
    }

    @Test
    fun `resolveInputUtxo throws when the outpoint vout is out of range for a SegWit-serialized non_witness_utxo`() {
        val scriptPubKey = SCRIPT_PUBKEY_HEX.hexToBytes()
        val witnessAncestor = witnessAncestorBytes(listOf(UTXO_AMOUNT to scriptPubKey)) // 1 output, index 0 only
        val legacyAncestor = legacyAncestorBytes(listOf(UTXO_AMOUNT to scriptPubKey))
        val txid = ancestorTxid(legacyAncestor)
        val unsignedTx = buildUnsignedTx(txid, 1L) // no output at index 1
        val inputMap = PsbtMap(listOf(PsbtKeyValue(0x00, ByteArray(0), witnessAncestor)))
        val e = assertThrows(IllegalArgumentException::class.java) { resolveInputUtxo(unsignedTx, 0, inputMap) }
        assertTrue(e.message.orEmpty().contains("outside non_witness_utxo"))
    }

    @Test
    fun `a truncated witness stack in the non_witness_utxo ancestor is rejected, not silently misread`() {
        val scriptPubKey = SCRIPT_PUBKEY_HEX.hexToBytes()
        val fullAncestor = witnessAncestorBytes(listOf(UTXO_AMOUNT to scriptPubKey))
        val truncated = fullAncestor.copyOfRange(0, fullAncestor.size - 10)
        val inputMap = PsbtMap(listOf(PsbtKeyValue(0x00, ByteArray(0), truncated)))
        assertThrows(IllegalArgumentException::class.java) { inputMap.nonWitnessUtxo() }
    }

    @Test
    fun `trailing bytes after a SegWit-serialized non_witness_utxo are rejected`() {
        val scriptPubKey = SCRIPT_PUBKEY_HEX.hexToBytes()
        val withGarbage = witnessAncestorBytes(listOf(UTXO_AMOUNT to scriptPubKey)) + byteArrayOf(0x00)
        val inputMap = PsbtMap(listOf(PsbtKeyValue(0x00, ByteArray(0), withGarbage)))
        val e = assertThrows(IllegalArgumentException::class.java) { inputMap.nonWitnessUtxo() }
        assertTrue(e.message.orEmpty().contains("Trailing bytes"))
    }

    @Test
    fun `diagnosePsbtInputSigning predicts a signature will be added for the fixed scenario`() {
        val scriptPubKey = SCRIPT_PUBKEY_HEX.hexToBytes()
        val witnessAncestor = witnessAncestorBytes(listOf(UTXO_AMOUNT to scriptPubKey))
        val legacyAncestor = legacyAncestorBytes(listOf(UTXO_AMOUNT to scriptPubKey))
        val txid = ancestorTxid(legacyAncestor)
        val unsignedTx = buildUnsignedTx(txid, 0L)
        val inputMap = PsbtMap(
            listOf(
                PsbtKeyValue(0x00, ByteArray(0), witnessAncestor),
                PsbtKeyValue(0x06, EXPECTED_PUBKEY_HEX.hexToBytes(), bip32DerivationValue()),
            ),
        )
        val diagnostics = diagnosePsbtInputSigning(buildPsbt(unsignedTx, inputMap), testMasterKey())
        assertEquals(1, diagnostics.size)
        val d = diagnostics[0]
        assertTrue(d.hasNonWitnessUtxo)
        assertFalse(d.hasWitnessUtxo)
        assertTrue(d.utxoResolved)
        assertEquals(PsbtInputScriptKind.P2WPKH, d.scriptKind)
        assertTrue(d.wouldAddSignature)
        assertEquals(1, d.keys.size)
        assertEquals(FingerprintMatchStatus.VERIFIED_MATCH, d.keys[0].matchStatus)
    }

    @Test
    fun `diagnosePsbtInputSigning reports the fingerprint mismatch reason when a different device key is loaded`() {
        val differentWords = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote".split(" ")
        val scriptPubKey = SCRIPT_PUBKEY_HEX.hexToBytes()
        val witnessAncestor = witnessAncestorBytes(listOf(UTXO_AMOUNT to scriptPubKey))
        val legacyAncestor = legacyAncestorBytes(listOf(UTXO_AMOUNT to scriptPubKey))
        val txid = ancestorTxid(legacyAncestor)
        val unsignedTx = buildUnsignedTx(txid, 0L)
        val inputMap = PsbtMap(
            listOf(
                PsbtKeyValue(0x00, ByteArray(0), witnessAncestor),
                PsbtKeyValue(0x06, EXPECTED_PUBKEY_HEX.hexToBytes(), bip32DerivationValue()),
            ),
        )
        val differentMasterKey = bip32MasterKeyFromSeed(deriveSeed(differentWords, "").bytes)
        val diagnostics = diagnosePsbtInputSigning(buildPsbt(unsignedTx, inputMap), differentMasterKey)
        val d = diagnostics[0]
        assertTrue(d.utxoResolved)
        assertFalse(d.wouldAddSignature)
        assertEquals(FingerprintMatchStatus.MISMATCH, d.keys[0].matchStatus)
        assertEquals(MASTER_FINGERPRINT_HEX, d.keys[0].fingerprintHex)
    }

    @Test
    fun `diagnosePsbtInputSigning reports utxoResolved false for malformed non_witness_utxo instead of throwing`() {
        val scriptPubKey = SCRIPT_PUBKEY_HEX.hexToBytes()
        val fullAncestor = witnessAncestorBytes(listOf(UTXO_AMOUNT to scriptPubKey))
        val truncated = fullAncestor.copyOfRange(0, fullAncestor.size - 10)
        val unsignedTx = buildUnsignedTx(ByteArray(32) { 0x55 }, 0L)
        val inputMap = PsbtMap(listOf(PsbtKeyValue(0x00, ByteArray(0), truncated)))
        val diagnostics = diagnosePsbtInputSigning(buildPsbt(unsignedTx, inputMap), testMasterKey())
        val d = diagnostics[0]
        assertTrue(d.hasNonWitnessUtxo)
        assertFalse(d.utxoResolved)
        assertEquals(PsbtInputScriptKind.UNRESOLVED, d.scriptKind)
        assertFalse(d.wouldAddSignature)
    }
}
