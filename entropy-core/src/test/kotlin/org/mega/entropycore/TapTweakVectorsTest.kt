package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Official BIP341 key-path-spending test vectors, verbatim from
 * github.com/bitcoin/bips/blob/master/bip-0341/wallet-test-vectors.json
 * (fetched and parsed directly with Python, never hand-transcribed or
 * passed through LLM summarization - a first WebFetch-summarized pass at
 * this same file silently corrupted hex lengths, which is exactly why
 * this file exists instead). Covers both the key-path-only case (no
 * script tree, merkleRoot = null) and several script-tree cases (this
 * device’s internal key co-exists with an unrelated script tree it
 * knows nothing about beyond the Merkle root - full script-tree/leaf
 * construction is separate, later work).
 */
class TapTweakVectorsTest {

    private fun hex(s: String): ByteArray {
        return ByteArray(s.length / 2) { i -> ((s[i * 2].digitToInt(16) shl 4) or s[i * 2 + 1].digitToInt(16)).toByte() }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun `input 0 - tweaked pubkey and privkey match the official vector`() {
        val internalPubkey = hex("d6889cb081036e0faefa3a35157ad71086b123b2b144b649798b494c300a961d")
        val merkleRoot = ByteArray(0)
        val expectedTweakedPubkey = "53a1f6e454df1aa2776a2814a721372d6258050de330b3c6d10ee8f4e0dda343"
        val expectedTweakedPrivkey = "2405b971772ad26915c8dcdf10f238753a9b837e5f8e6a86fd7c0cce5b7296d9"

        val tweaked = TapTweak.tweakPubKey(internalPubkey, merkleRoot)
        assertEquals(expectedTweakedPubkey, tweaked.outputKeyXOnly.toHex())

        val internalPrivkey = hex("6b973d88838f27366ed61c9ad6367663045cb456e28335c109e30717ae0c6baa")
        val tweakedPrivkey = TapTweak.tweakPrivateKey(internalPrivkey, internalPubkey, merkleRoot)
        assertEquals(expectedTweakedPrivkey, tweakedPrivkey.toHex())

        // End-to-end: the tweaked private key must actually produce the
        // tweaked public key via ordinary Schnorr pubkey derivation - not
        // just match the vector’s string by coincidence.
        assertEquals(expectedTweakedPubkey, Schnorr.xOnlyPubKeyFromPrivateKey(tweakedPrivkey).toHex())

        // And a signature made with the tweaked private key must verify
        // under the tweaked public key.
        val message = hex("0000000000000000000000000000000000000000000000000000000000000000")
        val auxRand = hex("1111111111111111111111111111111111111111111111111111111111111111")
        val signature = Schnorr.sign(tweakedPrivkey, message, auxRand)
        assertTrue(Schnorr.verify(hex(expectedTweakedPubkey), message, signature))
    }

    @Test
    fun `input 1 - tweaked pubkey and privkey match the official vector`() {
        val internalPubkey = hex("187791b6f712a8ea41c8ecdd0ee77fab3e85263b37e1ec18a3651926b3a6cf27")
        val merkleRoot = hex("5b75adecf53548f3ec6ad7d78383bf84cc57b55a3127c72b9a2481752dd88b21")
        val expectedTweakedPubkey = "147c9c57132f6e7ecddba9800bb0c4449251c92a1e60371ee77557b6620f3ea3"
        val expectedTweakedPrivkey = "ea260c3b10e60f6de018455cd0278f2f5b7e454be1999572789e6a9565d26080"

        val tweaked = TapTweak.tweakPubKey(internalPubkey, merkleRoot)
        assertEquals(expectedTweakedPubkey, tweaked.outputKeyXOnly.toHex())

        val internalPrivkey = hex("1e4da49f6aaf4e5cd175fe08a32bb5cb4863d963921255f33d3bc31e1343907f")
        val tweakedPrivkey = TapTweak.tweakPrivateKey(internalPrivkey, internalPubkey, merkleRoot)
        assertEquals(expectedTweakedPrivkey, tweakedPrivkey.toHex())

        // End-to-end: the tweaked private key must actually produce the
        // tweaked public key via ordinary Schnorr pubkey derivation - not
        // just match the vector’s string by coincidence.
        assertEquals(expectedTweakedPubkey, Schnorr.xOnlyPubKeyFromPrivateKey(tweakedPrivkey).toHex())

        // And a signature made with the tweaked private key must verify
        // under the tweaked public key.
        val message = hex("0000000000000000000000000000000000000000000000000000000000000000")
        val auxRand = hex("1111111111111111111111111111111111111111111111111111111111111111")
        val signature = Schnorr.sign(tweakedPrivkey, message, auxRand)
        assertTrue(Schnorr.verify(hex(expectedTweakedPubkey), message, signature))
    }

    @Test
    fun `input 3 - tweaked pubkey and privkey match the official vector`() {
        val internalPubkey = hex("93478e9488f956df2396be2ce6c5cced75f900dfa18e7dabd2428aae78451820")
        val merkleRoot = hex("c525714a7f49c28aedbbba78c005931a81c234b2f6c99a73e4d06082adc8bf2b")
        val expectedTweakedPubkey = "e4d810fd50586274face62b8a807eb9719cef49c04177cc6b76a9a4251d5450e"
        val expectedTweakedPrivkey = "97323385e57015b75b0339a549c56a948eb961555973f0951f555ae6039ef00d"

        val tweaked = TapTweak.tweakPubKey(internalPubkey, merkleRoot)
        assertEquals(expectedTweakedPubkey, tweaked.outputKeyXOnly.toHex())

        val internalPrivkey = hex("d3c7af07da2d54f7a7735d3d0fc4f0a73164db638b2f2f7c43f711f6d4aa7e64")
        val tweakedPrivkey = TapTweak.tweakPrivateKey(internalPrivkey, internalPubkey, merkleRoot)
        assertEquals(expectedTweakedPrivkey, tweakedPrivkey.toHex())

        // End-to-end: the tweaked private key must actually produce the
        // tweaked public key via ordinary Schnorr pubkey derivation - not
        // just match the vector’s string by coincidence.
        assertEquals(expectedTweakedPubkey, Schnorr.xOnlyPubKeyFromPrivateKey(tweakedPrivkey).toHex())

        // And a signature made with the tweaked private key must verify
        // under the tweaked public key.
        val message = hex("0000000000000000000000000000000000000000000000000000000000000000")
        val auxRand = hex("1111111111111111111111111111111111111111111111111111111111111111")
        val signature = Schnorr.sign(tweakedPrivkey, message, auxRand)
        assertTrue(Schnorr.verify(hex(expectedTweakedPubkey), message, signature))
    }

    @Test
    fun `input 4 - tweaked pubkey and privkey match the official vector`() {
        val internalPubkey = hex("e0dfe2300b0dd746a3f8674dfd4525623639042569d829c7f0eed9602d263e6f")
        val merkleRoot = hex("ccbd66c6f7e8fdab47b3a486f59d28262be857f30d4773f2d5ea47f7761ce0e2")
        val expectedTweakedPubkey = "91b64d5324723a985170e4dc5a0f84c041804f2cd12660fa5dec09fc21783605"
        val expectedTweakedPrivkey = "a8e7aa924f0d58854185a490e6c41f6efb7b675c0f3331b7f14b549400b4d501"

        val tweaked = TapTweak.tweakPubKey(internalPubkey, merkleRoot)
        assertEquals(expectedTweakedPubkey, tweaked.outputKeyXOnly.toHex())

        val internalPrivkey = hex("f36bb07a11e469ce941d16b63b11b9b9120a84d9d87cff2c84a8d4affb438f4e")
        val tweakedPrivkey = TapTweak.tweakPrivateKey(internalPrivkey, internalPubkey, merkleRoot)
        assertEquals(expectedTweakedPrivkey, tweakedPrivkey.toHex())

        // End-to-end: the tweaked private key must actually produce the
        // tweaked public key via ordinary Schnorr pubkey derivation - not
        // just match the vector’s string by coincidence.
        assertEquals(expectedTweakedPubkey, Schnorr.xOnlyPubKeyFromPrivateKey(tweakedPrivkey).toHex())

        // And a signature made with the tweaked private key must verify
        // under the tweaked public key.
        val message = hex("0000000000000000000000000000000000000000000000000000000000000000")
        val auxRand = hex("1111111111111111111111111111111111111111111111111111111111111111")
        val signature = Schnorr.sign(tweakedPrivkey, message, auxRand)
        assertTrue(Schnorr.verify(hex(expectedTweakedPubkey), message, signature))
    }

    @Test
    fun `input 6 - tweaked pubkey and privkey match the official vector`() {
        val internalPubkey = hex("55adf4e8967fbd2e29f20ac896e60c3b0f1d5b0efa9d34941b5958c7b0a0312d")
        val merkleRoot = hex("2f6b2c5397b6d68ca18e09a3f05161668ffe93a988582d55c6f07bd5b3329def")
        val expectedTweakedPubkey = "75169f4001aa68f15bbed28b218df1d0a62cbbcf1188c6665110c293c907b831"
        val expectedTweakedPrivkey = "241c14f2639d0d7139282aa6abde28dd8a067baa9d633e4e7230287ec2d02901"

        val tweaked = TapTweak.tweakPubKey(internalPubkey, merkleRoot)
        assertEquals(expectedTweakedPubkey, tweaked.outputKeyXOnly.toHex())

        val internalPrivkey = hex("415cfe9c15d9cea27d8104d5517c06e9de48e2f986b695e4f5ffebf230e725d8")
        val tweakedPrivkey = TapTweak.tweakPrivateKey(internalPrivkey, internalPubkey, merkleRoot)
        assertEquals(expectedTweakedPrivkey, tweakedPrivkey.toHex())

        // End-to-end: the tweaked private key must actually produce the
        // tweaked public key via ordinary Schnorr pubkey derivation - not
        // just match the vector’s string by coincidence.
        assertEquals(expectedTweakedPubkey, Schnorr.xOnlyPubKeyFromPrivateKey(tweakedPrivkey).toHex())

        // And a signature made with the tweaked private key must verify
        // under the tweaked public key.
        val message = hex("0000000000000000000000000000000000000000000000000000000000000000")
        val auxRand = hex("1111111111111111111111111111111111111111111111111111111111111111")
        val signature = Schnorr.sign(tweakedPrivkey, message, auxRand)
        assertTrue(Schnorr.verify(hex(expectedTweakedPubkey), message, signature))
    }

    @Test
    fun `input 7 - tweaked pubkey and privkey match the official vector`() {
        val internalPubkey = hex("ee4fe085983462a184015d1f782d6a5f8b9c2b60130aff050ce221ecf3786592")
        val merkleRoot = hex("6c2dc106ab816b73f9d07e3cd1ef2c8c1256f519748e0813e4edd2405d277bef")
        val expectedTweakedPubkey = "712447206d7a5238acc7ff53fbe94a3b64539ad291c7cdbc490b7577e4b17df5"
        val expectedTweakedPrivkey = "65b6000cd2bfa6b7cf736767a8955760e62b6649058cbc970b7c0871d786346b"

        val tweaked = TapTweak.tweakPubKey(internalPubkey, merkleRoot)
        assertEquals(expectedTweakedPubkey, tweaked.outputKeyXOnly.toHex())

        val internalPrivkey = hex("c7b0e81f0a9a0b0499e112279d718cca98e79a12e2f137c72ae5b213aad0d103")
        val tweakedPrivkey = TapTweak.tweakPrivateKey(internalPrivkey, internalPubkey, merkleRoot)
        assertEquals(expectedTweakedPrivkey, tweakedPrivkey.toHex())

        // End-to-end: the tweaked private key must actually produce the
        // tweaked public key via ordinary Schnorr pubkey derivation - not
        // just match the vector’s string by coincidence.
        assertEquals(expectedTweakedPubkey, Schnorr.xOnlyPubKeyFromPrivateKey(tweakedPrivkey).toHex())

        // And a signature made with the tweaked private key must verify
        // under the tweaked public key.
        val message = hex("0000000000000000000000000000000000000000000000000000000000000000")
        val auxRand = hex("1111111111111111111111111111111111111111111111111111111111111111")
        val signature = Schnorr.sign(tweakedPrivkey, message, auxRand)
        assertTrue(Schnorr.verify(hex(expectedTweakedPubkey), message, signature))
    }

    @Test
    fun `input 8 - tweaked pubkey and privkey match the official vector`() {
        val internalPubkey = hex("f9f400803e683727b14f463836e1e78e1c64417638aa066919291a225f0e8dd8")
        val merkleRoot = hex("ab179431c28d3b68fb798957faf5497d69c883c6fb1e1cd9f81483d87bac90cc")
        val expectedTweakedPubkey = "77e30a5522dd9f894c3f8b8bd4c4b2cf82ca7da8a3ea6a239655c39c050ab220"
        val expectedTweakedPrivkey = "ec18ce6af99f43815db543f47b8af5ff5df3b2cb7315c955aa4a86e8143d2bf5"

        val tweaked = TapTweak.tweakPubKey(internalPubkey, merkleRoot)
        assertEquals(expectedTweakedPubkey, tweaked.outputKeyXOnly.toHex())

        val internalPrivkey = hex("77863416be0d0665e517e1c375fd6f75839544eca553675ef7fdf4949518ebaa")
        val tweakedPrivkey = TapTweak.tweakPrivateKey(internalPrivkey, internalPubkey, merkleRoot)
        assertEquals(expectedTweakedPrivkey, tweakedPrivkey.toHex())

        // End-to-end: the tweaked private key must actually produce the
        // tweaked public key via ordinary Schnorr pubkey derivation - not
        // just match the vector’s string by coincidence.
        assertEquals(expectedTweakedPubkey, Schnorr.xOnlyPubKeyFromPrivateKey(tweakedPrivkey).toHex())

        // And a signature made with the tweaked private key must verify
        // under the tweaked public key.
        val message = hex("0000000000000000000000000000000000000000000000000000000000000000")
        val auxRand = hex("1111111111111111111111111111111111111111111111111111111111111111")
        val signature = Schnorr.sign(tweakedPrivkey, message, auxRand)
        assertTrue(Schnorr.verify(hex(expectedTweakedPubkey), message, signature))
    }

}