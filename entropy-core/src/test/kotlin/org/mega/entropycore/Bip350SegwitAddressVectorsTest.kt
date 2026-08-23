package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Official BIP350 test vectors for v0-v16 native segwit addresses,
 * verbatim from github.com/bitcoin/bips/blob/master/bip-0350.mediawiki
 * (fetched and parsed directly, not via any summarization step).
 * Covers the Bech32-vs-Bech32m version-matching rule, witness version
 * range, program length rules (general 2-40 bytes and the v0-specific
 * 20/32 exact requirement), mixed-case rejection, and malformed
 * bit-group padding.
 */
class Bip350SegwitAddressVectorsTest {

    private fun hex(s: String): ByteArray {
        return ByteArray(s.length / 2) { i -> ((s[i * 2].digitToInt(16) shl 4) or s[i * 2 + 1].digitToInt(16)).toByte() }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun `valid address decodes correctly - BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3`() {
        val decoded = decodeSegwitAddress("bc", "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4")
        val script = byteArrayOf(
            (if (decoded.witnessVersion == 0) 0x00 else 0x50 + decoded.witnessVersion).toByte(),
            decoded.program.size.toByte(),
        ) + decoded.program
        assertEquals("0014751e76e8199196d454941c45d1b3a323f1433bd6", script.toHex())
        // Round trip: re-encoding then re-decoding must reproduce the exact
        // same (witnessVersion, program), independent of the original's letter case.
        val reencoded = encodeSegwitAddress("bc", decoded.witnessVersion, decoded.program)
        val redecoded = decodeSegwitAddress("bc", reencoded)
        assertEquals(decoded.witnessVersion, redecoded.witnessVersion)
        assertEquals(decoded.program.toHex(), redecoded.program.toHex())
    }

    @Test
    fun `valid address decodes correctly - tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj`() {
        val decoded = decodeSegwitAddress("tb", "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7")
        val script = byteArrayOf(
            (if (decoded.witnessVersion == 0) 0x00 else 0x50 + decoded.witnessVersion).toByte(),
            decoded.program.size.toByte(),
        ) + decoded.program
        assertEquals("00201863143c14c5166804bd19203356da136c985678cd4d27a1b8c6329604903262", script.toHex())
        // Round trip: re-encoding then re-decoding must reproduce the exact
        // same (witnessVersion, program), independent of the original's letter case.
        val reencoded = encodeSegwitAddress("tb", decoded.witnessVersion, decoded.program)
        val redecoded = decodeSegwitAddress("tb", reencoded)
        assertEquals(decoded.witnessVersion, redecoded.witnessVersion)
        assertEquals(decoded.program.toHex(), redecoded.program.toHex())
    }

    @Test
    fun `valid address decodes correctly - bc1pw508d6qejxtdg4y5r3zarvary0c5xw7kw508`() {
        val decoded = decodeSegwitAddress("bc", "bc1pw508d6qejxtdg4y5r3zarvary0c5xw7kw508d6qejxtdg4y5r3zarvary0c5xw7kt5nd6y")
        val script = byteArrayOf(
            (if (decoded.witnessVersion == 0) 0x00 else 0x50 + decoded.witnessVersion).toByte(),
            decoded.program.size.toByte(),
        ) + decoded.program
        assertEquals("5128751e76e8199196d454941c45d1b3a323f1433bd6751e76e8199196d454941c45d1b3a323f1433bd6", script.toHex())
        // Round trip: re-encoding then re-decoding must reproduce the exact
        // same (witnessVersion, program), independent of the original's letter case.
        val reencoded = encodeSegwitAddress("bc", decoded.witnessVersion, decoded.program)
        val redecoded = decodeSegwitAddress("bc", reencoded)
        assertEquals(decoded.witnessVersion, redecoded.witnessVersion)
        assertEquals(decoded.program.toHex(), redecoded.program.toHex())
    }

    @Test
    fun `valid address decodes correctly - BC1SW50QGDZ25J`() {
        val decoded = decodeSegwitAddress("bc", "BC1SW50QGDZ25J")
        val script = byteArrayOf(
            (if (decoded.witnessVersion == 0) 0x00 else 0x50 + decoded.witnessVersion).toByte(),
            decoded.program.size.toByte(),
        ) + decoded.program
        assertEquals("6002751e", script.toHex())
        // Round trip: re-encoding then re-decoding must reproduce the exact
        // same (witnessVersion, program), independent of the original's letter case.
        val reencoded = encodeSegwitAddress("bc", decoded.witnessVersion, decoded.program)
        val redecoded = decodeSegwitAddress("bc", reencoded)
        assertEquals(decoded.witnessVersion, redecoded.witnessVersion)
        assertEquals(decoded.program.toHex(), redecoded.program.toHex())
    }

    @Test
    fun `valid address decodes correctly - bc1zw508d6qejxtdg4y5r3zarvaryvaxxpcs`() {
        val decoded = decodeSegwitAddress("bc", "bc1zw508d6qejxtdg4y5r3zarvaryvaxxpcs")
        val script = byteArrayOf(
            (if (decoded.witnessVersion == 0) 0x00 else 0x50 + decoded.witnessVersion).toByte(),
            decoded.program.size.toByte(),
        ) + decoded.program
        assertEquals("5210751e76e8199196d454941c45d1b3a323", script.toHex())
        // Round trip: re-encoding then re-decoding must reproduce the exact
        // same (witnessVersion, program), independent of the original's letter case.
        val reencoded = encodeSegwitAddress("bc", decoded.witnessVersion, decoded.program)
        val redecoded = decodeSegwitAddress("bc", reencoded)
        assertEquals(decoded.witnessVersion, redecoded.witnessVersion)
        assertEquals(decoded.program.toHex(), redecoded.program.toHex())
    }

    @Test
    fun `valid address decodes correctly - tb1qqqqqp399et2xygdj5xreqhjjvcmzhxw4aywx`() {
        val decoded = decodeSegwitAddress("tb", "tb1qqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvsesrxh6hy")
        val script = byteArrayOf(
            (if (decoded.witnessVersion == 0) 0x00 else 0x50 + decoded.witnessVersion).toByte(),
            decoded.program.size.toByte(),
        ) + decoded.program
        assertEquals("0020000000c4a5cad46221b2a187905e5266362b99d5e91c6ce24d165dab93e86433", script.toHex())
        // Round trip: re-encoding then re-decoding must reproduce the exact
        // same (witnessVersion, program), independent of the original's letter case.
        val reencoded = encodeSegwitAddress("tb", decoded.witnessVersion, decoded.program)
        val redecoded = decodeSegwitAddress("tb", reencoded)
        assertEquals(decoded.witnessVersion, redecoded.witnessVersion)
        assertEquals(decoded.program.toHex(), redecoded.program.toHex())
    }

    @Test
    fun `valid address decodes correctly - tb1pqqqqp399et2xygdj5xreqhjjvcmzhxw4aywx`() {
        val decoded = decodeSegwitAddress("tb", "tb1pqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvsesf3hn0c")
        val script = byteArrayOf(
            (if (decoded.witnessVersion == 0) 0x00 else 0x50 + decoded.witnessVersion).toByte(),
            decoded.program.size.toByte(),
        ) + decoded.program
        assertEquals("5120000000c4a5cad46221b2a187905e5266362b99d5e91c6ce24d165dab93e86433", script.toHex())
        // Round trip: re-encoding then re-decoding must reproduce the exact
        // same (witnessVersion, program), independent of the original's letter case.
        val reencoded = encodeSegwitAddress("tb", decoded.witnessVersion, decoded.program)
        val redecoded = decodeSegwitAddress("tb", reencoded)
        assertEquals(decoded.witnessVersion, redecoded.witnessVersion)
        assertEquals(decoded.program.toHex(), redecoded.program.toHex())
    }

    @Test
    fun `valid address decodes correctly - bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z`() {
        val decoded = decodeSegwitAddress("bc", "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0")
        val script = byteArrayOf(
            (if (decoded.witnessVersion == 0) 0x00 else 0x50 + decoded.witnessVersion).toByte(),
            decoded.program.size.toByte(),
        ) + decoded.program
        assertEquals("512079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", script.toHex())
        // Round trip: re-encoding then re-decoding must reproduce the exact
        // same (witnessVersion, program), independent of the original's letter case.
        val reencoded = encodeSegwitAddress("bc", decoded.witnessVersion, decoded.program)
        val redecoded = decodeSegwitAddress("bc", reencoded)
        assertEquals(decoded.witnessVersion, redecoded.witnessVersion)
        assertEquals(decoded.program.toHex(), redecoded.program.toHex())
    }

    @Test
    fun `invalid address is rejected (Invalid human-readable part) - tc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z`() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeSegwitAddress("bc", "tc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vq5zuyut")
        }
    }

    @Test
    fun `invalid address is rejected (Invalid checksum (Bech32 instead of Bech32m)) - bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z`() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeSegwitAddress("bc", "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqh2y7hd")
        }
    }

    @Test
    fun `invalid address is rejected (Invalid checksum (Bech32 instead of Bech32m)) - tb1z0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z`() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeSegwitAddress("tb", "tb1z0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqglt7rf")
        }
    }

    @Test
    fun `invalid address is rejected (Invalid checksum (Bech32 instead of Bech32m)) - BC1S0XLXVLHEMJA6C4DQV22UAPCTQUPFHLXM9H8Z`() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeSegwitAddress("bc", "BC1S0XLXVLHEMJA6C4DQV22UAPCTQUPFHLXM9H8Z3K2E72Q4K9HCZ7VQ54WELL")
        }
    }

    @Test
    fun `invalid address is rejected (Invalid checksum (Bech32m instead of Bech32)) - bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kemea`() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeSegwitAddress("bc", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kemeawh")
        }
    }

    @Test
    fun `invalid address is rejected (Invalid checksum (Bech32m instead of Bech32)) - tb1q0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z`() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeSegwitAddress("tb", "tb1q0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vq24jc47")
        }
    }

    @Test
    fun `invalid address is rejected (Invalid character in checksum) - bc1p38j9r5y49hruaue7wxjce0updqjuyyx0kh56`() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeSegwitAddress("bc", "bc1p38j9r5y49hruaue7wxjce0updqjuyyx0kh56v8s25huc6995vvpql3jow4")
        }
    }

    @Test
    fun `invalid address is rejected (Invalid witness version) - BC130XLXVLHEMJA6C4DQV22UAPCTQUPFHLXM9H8Z`() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeSegwitAddress("bc", "BC130XLXVLHEMJA6C4DQV22UAPCTQUPFHLXM9H8Z3K2E72Q4K9HCZ7VQ7ZWS8R")
        }
    }

    @Test
    fun `invalid address is rejected (Invalid program length (1 byte)) - bc1pw5dgrnzv`() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeSegwitAddress("bc", "bc1pw5dgrnzv")
        }
    }

    @Test
    fun `invalid address is rejected (Invalid program length (41 bytes)) - bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z`() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeSegwitAddress("bc", "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7v8n0nx0muaewav253zgeav")
        }
    }

    @Test
    fun `invalid address is rejected (Invalid program length for witness version 0 (per BIP141)) - BC1QR508D6QEJXTDG4Y5R3ZARVARYV98GJ9P`() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeSegwitAddress("bc", "BC1QR508D6QEJXTDG4Y5R3ZARVARYV98GJ9P")
        }
    }

    @Test
    fun `invalid address is rejected (Mixed case) - tb1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z`() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeSegwitAddress("tb", "tb1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vq47Zagq")
        }
    }

    @Test
    fun `invalid address is rejected (zero padding of more than 4 bits) - bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z`() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeSegwitAddress("bc", "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7v07qwwzcrf")
        }
    }

    @Test
    fun `invalid address is rejected (Non-zero padding in 8-to-5 conversion) - tb1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z`() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeSegwitAddress("tb", "tb1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vpggkg4j")
        }
    }

    @Test
    fun `invalid address is rejected (Empty data section) - bc1gmk9yu`() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeSegwitAddress("bc", "bc1gmk9yu")
        }
    }

}