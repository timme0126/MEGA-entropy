package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for strict witness_utxo (finding #2) and sighash_type
 * (finding #3) parsing: witness_utxo's value must be consumed EXACTLY
 * (amount + compact-size script length + script bytes, no trailing bytes),
 * PSBT_IN_SIGHASH_TYPE must be exactly 4 bytes, and witness_utxo amounts
 * are subject to the same MAX_MONEY bound as any other amount (finding #1).
 */
class PsbtWitnessUtxoAndSighashHardeningTest {

    companion object {
        private val SCRIPT = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0x11 }
        private const val AMOUNT = 50_000L

        private fun Long.toUInt64LE(): ByteArray = byteArrayOf(
            (this and 0xFF).toByte(), ((this shr 8) and 0xFF).toByte(),
            ((this shr 16) and 0xFF).toByte(), ((this shr 24) and 0xFF).toByte(),
            ((this shr 32) and 0xFF).toByte(), ((this shr 40) and 0xFF).toByte(),
            ((this shr 48) and 0xFF).toByte(), ((this shr 56) and 0xFF).toByte(),
        )

        private fun witnessUtxoValue(amountSats: Long, script: ByteArray, trailingBytes: ByteArray = ByteArray(0)): ByteArray =
            amountSats.toUInt64LE() + byteArrayOf(script.size.toByte()) + script + trailingBytes

        private fun inputMapWithWitnessUtxo(value: ByteArray): PsbtMap =
            PsbtMap(listOf(PsbtKeyValue(0x01, ByteArray(0), value)))

        private fun inputMapWithSighashType(value: ByteArray): PsbtMap =
            PsbtMap(listOf(PsbtKeyValue(0x03, ByteArray(0), value)))
    }

    // --- witness_utxo exact consumption (finding #2) ---

    @Test
    fun `a witness_utxo value consumed exactly parses normally`() {
        val inputMap = inputMapWithWitnessUtxo(witnessUtxoValue(AMOUNT, SCRIPT))
        val wu = inputMap.witnessUtxo()
        assertEquals(AMOUNT, wu?.valueSats)
        assertTrue(wu!!.scriptPubKey.contentEquals(SCRIPT))
    }

    @Test
    fun `a witness_utxo value with one trailing byte after the script is rejected`() {
        val inputMap = inputMapWithWitnessUtxo(witnessUtxoValue(AMOUNT, SCRIPT, trailingBytes = byteArrayOf(0xAA.toByte())))
        val e = assertThrows(IllegalArgumentException::class.java) { inputMap.witnessUtxo() }
        assertTrue(e.message.orEmpty().contains("trailing byte"))
    }

    @Test
    fun `a witness_utxo value with many trailing bytes after the script is rejected`() {
        val inputMap = inputMapWithWitnessUtxo(witnessUtxoValue(AMOUNT, SCRIPT, trailingBytes = ByteArray(32) { 0x42 }))
        assertThrows(IllegalArgumentException::class.java) { inputMap.witnessUtxo() }
    }

    // --- witness_utxo amount range (finding #1, via the PSBT parsing path) ---

    @Test
    fun `a witness_utxo amount just above MAX_MONEY is rejected`() {
        val inputMap = inputMapWithWitnessUtxo(witnessUtxoValue(MAX_MONEY_SATS + 1, SCRIPT))
        val e = assertThrows(IllegalArgumentException::class.java) { inputMap.witnessUtxo() }
        assertTrue(e.message.orEmpty().contains("outside the valid range"))
    }

    @Test
    fun `a witness_utxo amount with the sign bit set is rejected`() {
        val inputMap = inputMapWithWitnessUtxo(witnessUtxoValue(-1L, SCRIPT))
        assertThrows(IllegalArgumentException::class.java) { inputMap.witnessUtxo() }
    }

    @Test
    fun `the maximum valid witness_utxo amount is accepted`() {
        val inputMap = inputMapWithWitnessUtxo(witnessUtxoValue(MAX_MONEY_SATS, SCRIPT))
        assertEquals(MAX_MONEY_SATS, inputMap.witnessUtxo()?.valueSats)
    }

    // --- sighash_type exact length (finding #3) ---

    @Test
    fun `a 4-byte sighash_type parses normally`() {
        val inputMap = inputMapWithSighashType(byteArrayOf(0x01, 0x00, 0x00, 0x00))
        assertEquals(1L, inputMap.sighashType())
    }

    @Test
    fun `an empty (0-byte) sighash_type value is rejected`() {
        val inputMap = inputMapWithSighashType(ByteArray(0))
        val e = assertThrows(IllegalArgumentException::class.java) { inputMap.sighashType() }
        assertTrue(e.message.orEmpty().contains("exactly 4 bytes"))
    }

    @Test
    fun `a 5-byte (oversized) sighash_type value is rejected`() {
        val inputMap = inputMapWithSighashType(byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00))
        val e = assertThrows(IllegalArgumentException::class.java) { inputMap.sighashType() }
        assertTrue(e.message.orEmpty().contains("exactly 4 bytes"))
    }

    @Test
    fun `a 3-byte (truncated) sighash_type value is rejected`() {
        val inputMap = inputMapWithSighashType(byteArrayOf(0x01, 0x00, 0x00))
        assertThrows(IllegalArgumentException::class.java) { inputMap.sighashType() }
    }

    @Test
    fun `a missing sighash_type entry returns null, not an exception`() {
        val inputMap = PsbtMap(emptyList())
        assertNull(inputMap.sighashType())
    }
}
