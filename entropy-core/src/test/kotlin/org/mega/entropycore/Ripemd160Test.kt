package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test vectors from the RIPEMD-160 algorithm authors' own published
 * reference (Dobbertin/Bosselaers/Preneel), reproduced in every major
 * crypto library's RIPEMD-160 test suite.
 */
class Ripemd160Test {

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun `empty string`() {
        assertEquals("9c1185a5c5e9fc54612808977ee8f548b2258d31", ripemd160("".toByteArray()).toHex())
    }

    @Test
    fun `single character a`() {
        assertEquals("0bdc9d2d256b3ee9daae347be6f4dc835a467ffe", ripemd160("a".toByteArray()).toHex())
    }

    @Test
    fun `abc`() {
        assertEquals("8eb208f7e05d987a9b044a8e98c6b087f15a0bfc", ripemd160("abc".toByteArray()).toHex())
    }

    @Test
    fun `message digest`() {
        assertEquals("5d0689ef49d2fae572b881b123a85ffa21595f36", ripemd160("message digest".toByteArray()).toHex())
    }

    @Test
    fun `lowercase alphabet`() {
        assertEquals(
            "f71c27109c692c1b56bbdceb5b9d2865b3708dbc",
            ripemd160("abcdefghijklmnopqrstuvwxyz".toByteArray()).toHex(),
        )
    }

    @Test
    fun `alphanumeric alphabet`() {
        assertEquals(
            "b0e20b6e3116640286ed3a87a5713079b21f5189",
            ripemd160("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toByteArray()).toHex(),
        )
    }

    @Test
    fun `eight repetitions of one to nine`() {
        assertEquals(
            "9b752e45573d4b39f4dbd3323cab82bf63326bfb",
            ripemd160("1234567890".repeat(8).toByteArray()).toHex(),
        )
    }

    @Test
    fun `one million repetitions of a`() {
        assertEquals(
            "52783243c1697bdbe16d37f97f68f08325dc1528",
            ripemd160("a".repeat(1_000_000).toByteArray()).toHex(),
        )
    }

    @Test
    fun `hash160 of empty input matches ripemd160 of sha256 of empty input`() {
        // Cross-check that hash160() composes SHA-256 then RIPEMD-160 in the
        // right order, independent of whether either digest is correct in
        // isolation.
        val expected = ripemd160(sha256(ByteArray(0)))
        assertEquals(expected.toHex(), hash160(ByteArray(0)).toHex())
    }
}
