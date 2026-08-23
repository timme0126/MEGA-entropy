package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Official BIP341 key-path signing test vectors, verbatim from
 * github.com/bitcoin/bips/blob/master/bip-0341/wallet-test-vectors.json
 * (fetched and parsed directly with Python — see TapTweakVectorsTest.kt's
 * own doc for why). Covers input 3 (hash_type 1, SIGHASH_ALL, 65-byte
 * witness with trailing hashtype byte) and input 4 (hash_type 0,
 * SIGHASH_DEFAULT, 64-byte witness with no trailing byte) — the only two
 * cases in that file matching computeTaprootKeyPathSighash's supported
 * scope (see its own doc for why SINGLE/NONE/ANYONECANPAY aren't
 * implemented).
 */
class TaprootSighashVectorsTest {

    private fun hex(s: String): ByteArray {
        return ByteArray(s.length / 2) { i -> ((s[i * 2].digitToInt(16) shl 4) or s[i * 2 + 1].digitToInt(16)).toByte() }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private val rawUnsignedTx = hex(
        "02000000097de20cbff686da83a54981d2b9bab3586f4ca7e48f57f5b55963115f3b334e9c010000000000000000d7b7cab5" +
            "7b1393ace2d064f4d4a2cb8af6def61273e127517d44759b6dafdd990000000000fffffffff8e1f583384333689228c5d" +
            "28eac13366be082dc57441760d957275419a418420000000000fffffffff0689180aa63b30cb162a73c6d2a38b7eeda2" +
            "a83ece74310fda0843ad604853b0100000000feffffffaa5202bdf6d8ccd2ee0f0202afbbb7461d9264a25e5bfd3c5a5" +
            "2ee1239e0ba6c0000000000feffffff956149bdc66faa968eb2be2d2faa29718acbfe3941215893a2a3446d32acd0500" +
            "00000000000000000e664b9773b88c09c32cb70a2a3e4da0ced63b7ba3b22f848531bbb1d5d5f4c94010000000000000" +
            "000e9aa6b8e6c9de67619e6a3924ae25696bb7b694bb677a632a74ef7eadfd4eabf0000000000ffffffffa778eb6a263" +
            "dc090464cd125c466b5a99667720b1c110468831d058aa1b82af10100000000ffffffff0200ca9a3b00000000197" +
            "6a91406afd46bcdfd22ef94ac122aa11f241244a37ecc88ac807840cb0000000020ac9a87f5594be208f8532db38cff" +
            "670c450ed2fea8fcdefcc9a663f78bab962b0065cd1d",
    )

    private val spentOutputs = listOf(
        TxOut(420000000L, hex("512053a1f6e454df1aa2776a2814a721372d6258050de330b3c6d10ee8f4e0dda343")),
        TxOut(462000000L, hex("5120147c9c57132f6e7ecddba9800bb0c4449251c92a1e60371ee77557b6620f3ea3")),
        TxOut(294000000L, hex("76a914751e76e8199196d454941c45d1b3a323f1433bd688ac")),
        TxOut(504000000L, hex("5120e4d810fd50586274face62b8a807eb9719cef49c04177cc6b76a9a4251d5450e")),
        TxOut(630000000L, hex("512091b64d5324723a985170e4dc5a0f84c041804f2cd12660fa5dec09fc21783605")),
        TxOut(378000000L, hex("00147dd65592d0ab2fe0d0257d571abf032cd9db93dc")),
        TxOut(672000000L, hex("512075169f4001aa68f15bbed28b218df1d0a62cbbcf1188c6665110c293c907b831")),
        TxOut(546000000L, hex("5120712447206d7a5238acc7ff53fbe94a3b64539ad291c7cdbc490b7577e4b17df5")),
        TxOut(588000000L, hex("512077e30a5522dd9f894c3f8b8bd4c4b2cf82ca7da8a3ea6a239655c39c050ab220")),
    )

    private val unsignedTx = parseTransaction(rawUnsignedTx)

    @Test
    fun `input 3 (SIGHASH_ALL) sigMsg, sigHash, and full signature match the official vector`() {
        val internalPrivkey = hex("d3c7af07da2d54f7a7735d3d0fc4f0a73164db638b2f2f7c43f711f6d4aa7e64")
        val merkleRoot = hex("c525714a7f49c28aedbbba78c005931a81c234b2f6c99a73e4d06082adc8bf2b")
        val expectedSigMsg = "0001020000000065cd1de3b33bb4ef3a52ad1fffb555c0d82828eb22737036eaeb02a235d82b909c4c3f58a" +
            "6964a4f5f8f0b642ded0a8a553be7622a719da71d1f5befcefcdee8e0fde623ad0f61ad2bca5ba6a7693f50fce988e17c37" +
            "80bf2b1e720cfbb38fbdd52e2118959c7221ab5ce9e26c3cd67b22c24f8baa54bac281d8e6b05e400e6c3a957ea2e6dab7" +
            "c1f0dcd297c8d61647fd17d821541ea69c3cc37dcbad7f90d4eb4bc50003000000"
        val expectedSigHash = "bf013ea93474aa67815b1b6cc441d23b64fa310911d991e713cd34c7f5d46669"
        val expectedWitnessSig = "ff45f742a876139946a149ab4d9185574b98dc919d2eb6754f8abaa59d18b025637a3aa043b9181773955" +
            "4f4ed2026cf8022dbd83e351ce1fabc272841d2510a01"

        assertSighashAndSignature(
            txinIndex = 3,
            internalPrivkey = internalPrivkey,
            merkleRoot = merkleRoot,
            hashType = 1,
            expectedSigMsg = expectedSigMsg,
            expectedSigHash = expectedSigHash,
            expectedWitnessSig = expectedWitnessSig,
        )
    }

    @Test
    fun `input 4 (SIGHASH_DEFAULT) sigMsg, sigHash, and full signature match the official vector`() {
        val internalPrivkey = hex("f36bb07a11e469ce941d16b63b11b9b9120a84d9d87cff2c84a8d4affb438f4e")
        val merkleRoot = hex("ccbd66c6f7e8fdab47b3a486f59d28262be857f30d4773f2d5ea47f7761ce0e2")
        val expectedSigMsg = "0000020000000065cd1de3b33bb4ef3a52ad1fffb555c0d82828eb22737036eaeb02a235d82b909c4c3f58a" +
            "6964a4f5f8f0b642ded0a8a553be7622a719da71d1f5befcefcdee8e0fde623ad0f61ad2bca5ba6a7693f50fce988e17c37" +
            "80bf2b1e720cfbb38fbdd52e2118959c7221ab5ce9e26c3cd67b22c24f8baa54bac281d8e6b05e400e6c3a957ea2e6dab7" +
            "c1f0dcd297c8d61647fd17d821541ea69c3cc37dcbad7f90d4eb4bc50004000000"
        val expectedSigHash = "4f900a0bae3f1446fd48490c2958b5a023228f01661cda3496a11da502a7f7ef"
        val expectedWitnessSig = "b4010dd48a617db09926f729e79c33ae0b4e94b79f04a1ae93ede6315eb3669de185a17d2b0ac9ee09fd4c" +
            "64b678a0b61a0a86fa888a273c8511be83bfd6810f"

        assertSighashAndSignature(
            txinIndex = 4,
            internalPrivkey = internalPrivkey,
            merkleRoot = merkleRoot,
            hashType = 0,
            expectedSigMsg = expectedSigMsg,
            expectedSigHash = expectedSigHash,
            expectedWitnessSig = expectedWitnessSig,
        )
    }

    private fun assertSighashAndSignature(
        txinIndex: Int,
        internalPrivkey: ByteArray,
        merkleRoot: ByteArray,
        hashType: Int,
        expectedSigMsg: String,
        expectedSigHash: String,
        expectedWitnessSig: String,
    ) {
        // The sigMsg itself isn't returned by computeTaprootKeyPathSighash
        // (only the final hash is) - recompute it here with the exact same
        // field-by-field logic, independently, so a mismatch is attributable
        // to a specific field rather than just "the hash is wrong somewhere."
        val shaPrevouts = sha256(unsignedTx.inputs.fold(ByteArray(0)) { acc, inp -> acc + inp.previousTxid + writeUInt32LE(inp.previousVout) })
        val shaAmounts = sha256(spentOutputs.fold(ByteArray(0)) { acc, out -> acc + writeUInt64LE(out.valueSats) })
        val shaScriptPubkeys = sha256(spentOutputs.fold(ByteArray(0)) { acc, out -> acc + writeCompactSize(out.scriptPubKey.size.toLong()) + out.scriptPubKey })
        val shaSequences = sha256(unsignedTx.inputs.fold(ByteArray(0)) { acc, inp -> acc + writeUInt32LE(inp.sequence) })
        val shaOutputs = sha256(unsignedTx.outputs.fold(ByteArray(0)) { acc, out -> acc + writeUInt64LE(out.valueSats) + writeCompactSize(out.scriptPubKey.size.toLong()) + out.scriptPubKey })
        // The vector's own "sigMsg" field is actually "0x00 (sighash epoch)
        // || SigMsg(...)" - BIP341's SigMsg() function itself does not
        // include that leading epoch byte (it's prepended separately in the
        // hash_TapSighash(0x00 || SigMsg(...)) step); this reconstruction
        // matches the vector field's own (slightly loosely named) meaning.
        val sigMsg = byteArrayOf(0) + byteArrayOf(hashType.toByte()) + writeUInt32LE(unsignedTx.version) + writeUInt32LE(unsignedTx.locktime) +
            shaPrevouts + shaAmounts + shaScriptPubkeys + shaSequences + shaOutputs +
            byteArrayOf(0) + writeUInt32LE(txinIndex.toLong())
        assertEquals(expectedSigMsg, sigMsg.toHex())

        val sigHash = computeTaprootKeyPathSighash(unsignedTx, txinIndex, spentOutputs, hashType)
        assertEquals(expectedSigHash, sigHash.toHex())

        val tweakedPrivkey = TapTweak.tweakPrivateKey(internalPrivkey, Schnorr.xOnlyPubKeyFromPrivateKey(internalPrivkey), merkleRoot)
        val auxRand = hex("00".repeat(32))
        val signature = Schnorr.sign(tweakedPrivkey, sigHash, auxRand)
        // BIP340 signing is itself deterministic given (key, message, auxRand)
        // but the OFFICIAL vector's witness signature was produced with
        // whatever auxRand the test-vector generator actually used, which
        // isn't given in this JSON - so this test does NOT assert byte
        // equality with expectedWitnessSig's signature bytes (that would
        // only pass if it happens to reuse the same auxRand this test
        // picked). Instead it verifies MEGA's own signature under the
        // vector's own tweaked pubkey, and separately confirms the
        // OFFICIAL signature also verifies - two independent checks that
        // both land on the same sighash this test just proved is correct.
        val outputPubkey = TapTweak.tweakPubKey(Schnorr.xOnlyPubKeyFromPrivateKey(internalPrivkey), merkleRoot).outputKeyXOnly
        assertTrue(Schnorr.verify(outputPubkey, sigHash, signature))

        val officialWitness = hex(expectedWitnessSig)
        val officialSig = if (hashType == 0) officialWitness else officialWitness.copyOfRange(0, 64)
        assertTrue(Schnorr.verify(outputPubkey, sigHash, officialSig))
        if (hashType != 0) {
            assertEquals(hashType, officialWitness[64].toInt())
            assertEquals(65, officialWitness.size)
        } else {
            assertEquals(64, officialWitness.size)
        }
    }
}
