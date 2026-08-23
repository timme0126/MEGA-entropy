package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * End-to-end: internal key -> TapTweak -> P2TR bech32m address, checked
 * against the same BIP341 wallet-test-vectors.json case TapTweakVectorsTest
 * uses (input 0: key-path only, no script tree) — this is the one case in
 * that file whose expected mainnet address is also given directly in the
 * BIP341 spec text's own worked example, independently cross-checked there.
 */
class TaprootAddressEndToEndTest {

    private fun hex(s: String): ByteArray {
        return ByteArray(s.length / 2) { i -> ((s[i * 2].digitToInt(16) shl 4) or s[i * 2 + 1].digitToInt(16)).toByte() }
    }

    @Test
    fun `key-path-only internal key produces the expected mainnet P2TR address`() {
        val internalPubkey = hex("d6889cb081036e0faefa3a35157ad71086b123b2b144b649798b494c300a961d")
        val tweaked = TapTweak.tweakPubKey(internalPubkey, ByteArray(0))
        val address = encodeTaprootAddress("bc", tweaked.outputKeyXOnly)
        assertEquals("bc1p2wsldez5mud2yam29q22wgfh9439spgduvct83k3pm50fcxa5dps59h4z5", address)

        val decoded = decodeSegwitAddress("bc", address)
        assertEquals(1, decoded.witnessVersion)
        assertEquals(tweaked.outputKeyXOnly.joinToString("") { "%02x".format(it.toInt() and 0xFF) }, decoded.program.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
    }
}
