package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the MAX_MONEY satoshi-amount bound (finding #1):
 * every amount read off the wire — transaction outputs here, PSBT
 * witness_utxo amounts in PsbtHardeningTest — must reject values outside
 * 0..MAX_MONEY_SATS, including the case where an attacker-controlled 8-byte
 * little-endian field's high bit sets the Kotlin Long negative. Also covers
 * the checked (overflow-detecting) arithmetic used to total/subtract
 * already-validated amounts.
 */
class TransactionAmountValidationTest {

    companion object {
        private val DUMMY_SPK = byteArrayOf(0x00, 0x14) + ByteArray(20)

        private fun txWithOneOutput(valueSats: Long): Transaction = Transaction(
            version = 2,
            inputs = listOf(TxIn(ByteArray(32), 0, ByteArray(0), 0xffffffffL)),
            outputs = listOf(TxOut(valueSats, DUMMY_SPK)),
            locktime = 0,
        )

        /** Builds a Transaction with an (possibly invalid) output amount, serializes it to
         * wire bytes exactly as any encoder would, then parses those bytes back —
         * exercising parseTransaction's validation the same way a scanned/loaded PSBT does. */
        private fun roundTripParse(valueSats: Long): Transaction =
            parseTransaction(serializeTransaction(txWithOneOutput(valueSats)))
    }

    @Test
    fun `a zero-amount output is accepted`() {
        val parsed = roundTripParse(0L)
        assertEquals(0L, parsed.outputs[0].valueSats)
    }

    @Test
    fun `the maximum valid amount (MAX_MONEY) is accepted`() {
        val parsed = roundTripParse(MAX_MONEY_SATS)
        assertEquals(MAX_MONEY_SATS, parsed.outputs[0].valueSats)
    }

    @Test
    fun `one satoshi above MAX_MONEY is rejected`() {
        val e = assertThrows(IllegalArgumentException::class.java) { roundTripParse(MAX_MONEY_SATS + 1) }
        assertTrue(e.message.orEmpty().contains("outside the valid range"))
    }

    @Test
    fun `an encoded uint64 with the sign bit set (reads back negative) is rejected`() {
        // 0xFFFFFFFFFFFFFFFF on the wire — the maximal attacker-controlled
        // 8-byte value — round-trips through Kotlin's signed Long as -1.
        assertThrows(IllegalArgumentException::class.java) { roundTripParse(-1L) }
    }

    @Test
    fun `an encoded uint64 of exactly Long MIN_VALUE (bit 63 only) is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { roundTripParse(Long.MIN_VALUE) }
    }

    @Test
    fun `checkedSumSats correctly totals a normal list of amounts`() {
        assertEquals(300L, checkedSumSats(listOf(100L, 100L, 100L), "test total"))
    }

    @Test
    fun `checkedSumSats throws instead of silently wrapping on overflow`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            checkedSumSats(listOf(Long.MAX_VALUE - 10, 20L), "test total")
        }
        assertTrue(e.message.orEmpty().contains("overflows"))
    }

    @Test
    fun `checkedSubtractSats correctly computes a normal fee`() {
        assertEquals(500L, checkedSubtractSats(10_000L, 9_500L, "test fee"))
    }

    @Test
    fun `checkedSubtractSats throws instead of silently wrapping on overflow`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            checkedSubtractSats(Long.MIN_VALUE, 1L, "test fee")
        }
        assertTrue(e.message.orEmpty().contains("overflows"))
    }
}
