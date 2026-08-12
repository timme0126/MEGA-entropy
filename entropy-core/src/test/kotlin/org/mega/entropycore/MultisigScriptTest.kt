package org.mega.entropycore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MultisigScriptTest {

    // G and 2G's own known compressed forms (same reference values already
    // asserted in Secp256k1Test) — reused here as two arbitrary, genuinely
    // distinct 33-byte public keys with a known relative BIP67 order: they
    // share the same 0x02 prefix byte, and G's second byte (0x79) is less
    // than 2G's (0xc6), so G sorts first regardless of input order.
    private val g = hexToBytes("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
    private val twoG = hexToBytes("02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5")

    @Test
    fun `sortPublicKeysBip67 orders G before 2G regardless of input order`() {
        assertArrayEquals(arrayOf(g, twoG), sortPublicKeysBip67(listOf(g, twoG)).toTypedArray())
        assertArrayEquals(arrayOf(g, twoG), sortPublicKeysBip67(listOf(twoG, g)).toTypedArray())
    }

    @Test
    fun `sortPublicKeysBip67 does not mutate its input`() {
        val input = listOf(twoG, g)
        sortPublicKeysBip67(input)
        assertEquals(listOf(twoG, g), input)
    }

    @Test
    fun `buildMultisigWitnessScript produces the exact expected bytes for a hand-verified 2-of-2 example`() {
        // OP_2 (0x52) + push33 (0x21) + G + push33 (0x21) + 2G + OP_2 (0x52) + OP_CHECKMULTISIG (0xAE)
        val expectedHex = "52" + "21" + g.toHex() + "21" + twoG.toHex() + "52" + "ae"
        val script = buildMultisigWitnessScript(2, listOf(g, twoG))
        assertEquals(expectedHex, script.toHex())
        assertEquals(1 + 2 * 34 + 2, script.size)
    }

    @Test
    fun `buildMultisigWitnessScript threshold opcode reflects a 1-of-2 wallet`() {
        val script = buildMultisigWitnessScript(1, listOf(g, twoG))
        assertEquals(0x51.toByte(), script[0]) // OP_1
        assertEquals(0x52.toByte(), script[script.size - 2]) // OP_2 (still 2 keys total)
    }

    @Test
    fun `buildMultisigWitnessScript rejects a threshold above the key count`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildMultisigWitnessScript(3, listOf(g, twoG))
        }
    }

    @Test
    fun `buildMultisigWitnessScript rejects zero or negative threshold`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildMultisigWitnessScript(0, listOf(g, twoG))
        }
    }

    @Test
    fun `buildMultisigWitnessScript rejects fewer than 2 keys`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildMultisigWitnessScript(1, listOf(g))
        }
    }

    @Test
    fun `buildMultisigWitnessScript rejects more than 15 keys`() {
        val sixteenKeys = List(16) { g }
        assertThrows(IllegalArgumentException::class.java) {
            buildMultisigWitnessScript(1, sixteenKeys)
        }
    }

    @Test
    fun `buildMultisigWitnessScript rejects a malformed public key length`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildMultisigWitnessScript(1, listOf(g, ByteArray(32)))
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((Character.digit(hex[it * 2], 16) shl 4) + Character.digit(hex[it * 2 + 1], 16)).toByte() }
}
