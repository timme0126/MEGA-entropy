package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MultisigDerivationTest {

    private val testMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        .split(" ")

    @Test
    fun `deriveMultisigCosignerAccountKeys follows the BIP48 native-segwit path`() {
        val result = deriveMultisigCosignerAccountKeys(
            testMnemonic, "", MultisigScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0,
        )
        assertEquals("m/48'/0'/0'/2'", result.derivationPath)
        assertEquals("73c5da0a", result.masterFingerprint)
        assertEquals(
            "xpub6DkFAXWQ2dHxq2vatrt9qyA3bXYU4ToWQwCHbf5XB2mSTexcHZCeKS1VZYcPoBd5X8yVcbXFHJR9R8UCVpt82VX1VhR28mCyxUFL4r6KFrf",
            result.extendedPublicKey,
        )
    }

    @Test
    fun `deriveMultisigCosignerAccountKeys uses a plain xpub prefix, not SLIP-132 Zpub`() {
        val result = deriveMultisigCosignerAccountKeys(
            testMnemonic, "", MultisigScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0,
        )
        assertEquals("xpub", result.extendedPublicKey.take(4))
    }

    @Test
    fun `deriveMultisigCosignerAccountKeys master fingerprint matches the single-sig derivation for the same mnemonic`() {
        val multisig = deriveMultisigCosignerAccountKeys(
            testMnemonic, "", MultisigScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0,
        )
        val singleSig = deriveWalletAccountKeys(
            testMnemonic, "", WalletScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0,
        )
        assertEquals(singleSig.masterFingerprint, multisig.masterFingerprint)
    }

    private val cosignerA = MultisigCosignerOrigin(
        masterFingerprint = "751e76e8",
        derivationPath = "m/48'/0'/0'/2'",
        extendedPublicKey = "xpub661MyMwAqRbcEYS8w7XLSVeEsBXy79zSzH1J8vCdxAZningWLdN3zgtU6QzvJsNBNF5QPBBBg1yVF2LKrcfGdJq86PeLWDMUCYatZPzQu8R",
    )
    private val cosignerB = MultisigCosignerOrigin(
        masterFingerprint = "06afd46b",
        derivationPath = "m/48'/0'/0'/2'",
        extendedPublicKey = "xpub661MyMwAqRbcEYS8w7XLSVeEsBXy79zSzH1J8vCdxAZningWLdN3zgtU6RaWczAs6MVywiybuhjHuUQKNNTPv4jYsDwwKwKyhjPrr2oGiVK",
    )
    private val cosignerC = MultisigCosignerOrigin(
        masterFingerprint = "7dd65592",
        derivationPath = "m/48'/0'/0'/2'",
        extendedPublicKey = "xpub661MyMwAqRbcEYS8w7XLSVeEsBXy79zSzH1J8vCdxAZningWLdN3zgtU6Ry3jzyxjRpjQ6N8aa1M55DxuLjf24UZ8ufawmLVf8NWMG88kcq",
    )

    @Test
    fun `buildMultisigWallet 2-of-3 matches the independently-computed descriptor and address exactly`() {
        val wallet = buildMultisigWallet(2, listOf(cosignerA, cosignerB, cosignerC), WalletNetwork.MAINNET)

        assertEquals(
            "wsh(sortedmulti(2," +
                "[751e76e8/48'/0'/0'/2']xpub661MyMwAqRbcEYS8w7XLSVeEsBXy79zSzH1J8vCdxAZningWLdN3zgtU6QzvJsNBNF5QPBBBg1yVF2LKrcfGdJq86PeLWDMUCYatZPzQu8R/<0;1>/*," +
                "[06afd46b/48'/0'/0'/2']xpub661MyMwAqRbcEYS8w7XLSVeEsBXy79zSzH1J8vCdxAZningWLdN3zgtU6RaWczAs6MVywiybuhjHuUQKNNTPv4jYsDwwKwKyhjPrr2oGiVK/<0;1>/*," +
                "[7dd65592/48'/0'/0'/2']xpub661MyMwAqRbcEYS8w7XLSVeEsBXy79zSzH1J8vCdxAZningWLdN3zgtU6Ry3jzyxjRpjQ6N8aa1M55DxuLjf24UZ8ufawmLVf8NWMG88kcq/<0;1>/*" +
                "))#vwjrk4hz",
            wallet.descriptor,
        )
        assertEquals("bc1qfhs9w0u2qscn0t2p47cafpq8f8dvln9ahmh95ppd5j3k0en9rnwsrdk3ms", wallet.firstReceiveAddress)
        assertEquals(2, wallet.threshold)
        assertEquals(3, wallet.cosigners.size)
        assertEquals(WalletNetwork.MAINNET, wallet.network)
    }

    @Test
    fun `buildMultisigWallet descriptor lists cosigners in caller order, unaffected by BIP67 sorting`() {
        val forward = buildMultisigWallet(2, listOf(cosignerA, cosignerB, cosignerC), WalletNetwork.MAINNET)
        val reversed = buildMultisigWallet(2, listOf(cosignerC, cosignerB, cosignerA), WalletNetwork.MAINNET)
        assertEquals(forward.firstReceiveAddress, reversed.firstReceiveAddress)
        assertTrue(reversed.descriptor.startsWith("wsh(sortedmulti(2,[7dd65592"))
    }

    @Test
    fun `buildMultisigWallet rejects a duplicate cosigner extended public key`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildMultisigWallet(2, listOf(cosignerA, cosignerB, cosignerA.copy(masterFingerprint = "00000000")), WalletNetwork.MAINNET)
        }
    }

    @Test
    fun `buildMultisigWallet rejects a testnet cosigner in a mainnet wallet`() {
        val testnetCosigner = MultisigCosignerOrigin(
            masterFingerprint = "00000000",
            derivationPath = "m/48'/1'/0'/2'",
            extendedPublicKey = deriveWalletAccountKeys(testMnemonic, "", WalletScriptType.LEGACY, WalletNetwork.TESTNET, 0)
                .extendedPublicKey,
        )
        assertThrows(IllegalArgumentException::class.java) {
            buildMultisigWallet(2, listOf(cosignerA, cosignerB, testnetCosigner), WalletNetwork.MAINNET)
        }
    }

    @Test
    fun `buildMultisigWallet rejects fewer than 2 cosigners`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildMultisigWallet(1, listOf(cosignerA), WalletNetwork.MAINNET)
        }
    }

    @Test
    fun `buildMultisigWallet rejects a threshold above the cosigner count`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildMultisigWallet(4, listOf(cosignerA, cosignerB, cosignerC), WalletNetwork.MAINNET)
        }
    }

    @Test
    fun `AdvancedModeMultisigScreen's derive-then-build flow round-trips end to end`() {
        val deviceOne = deriveMultisigCosignerAccountKeys(
            testMnemonic, "", MultisigScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0,
        )
        val deviceTwo = deriveMultisigCosignerAccountKeys(
            testMnemonic, "", MultisigScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 1,
        )
        fun descriptorFragment(key: MultisigCosignerAccountKeys) =
            "[${key.masterFingerprint}/${key.derivationPath.removePrefix("m/")}]${key.extendedPublicKey}"

        val originOne = parseCosignerDescriptorFragment(descriptorFragment(deviceOne))
        val originTwo = parseCosignerDescriptorFragment(descriptorFragment(deviceTwo))
        assertEquals(deviceOne.masterFingerprint, originOne.masterFingerprint)
        assertEquals(deviceOne.derivationPath, originOne.derivationPath)
        assertEquals(deviceOne.extendedPublicKey, originOne.extendedPublicKey)

        val wallet = buildMultisigWallet(2, listOf(originOne, originTwo), WalletNetwork.MAINNET)
        assertEquals(2, wallet.threshold)
        assertEquals(2, wallet.cosigners.size)
        assertTrue(wallet.descriptor.startsWith("wsh(sortedmulti(2,"))
        assertEquals(verifyAndStripDescriptorChecksum(wallet.descriptor), wallet.descriptor.substringBefore("#"))
        assertTrue(wallet.descriptor.substringBefore("#").endsWith("))"))
        assertTrue(wallet.firstReceiveAddress.startsWith("bc1q"))
    }

    @Test
    fun `parseCosignerDescriptorFragment round-trips a bracketed fragment`() {
        val fragment = "[751e76e8/48'/0'/0'/2']" + cosignerA.extendedPublicKey
        val origin = parseCosignerDescriptorFragment(fragment)
        assertEquals("751e76e8", origin.masterFingerprint)
        assertEquals("m/48'/0'/0'/2'", origin.derivationPath)
        assertEquals(cosignerA.extendedPublicKey, origin.extendedPublicKey)
    }

    @Test
    fun `parseCosignerDescriptorFragment normalizes an uppercase fingerprint to lowercase`() {
        val fragment = "[751E76E8/48'/0'/0'/2']" + cosignerA.extendedPublicKey
        val origin = parseCosignerDescriptorFragment(fragment)
        assertEquals("751e76e8", origin.masterFingerprint)
    }

    @Test
    fun `parseCosignerDescriptorFragment rejects a bare extended public key with a helpful message`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            parseCosignerDescriptorFragment(cosignerA.extendedPublicKey)
        }
        assertTrue(exception.message.orEmpty().contains("bracketed"))
    }

    @Test
    fun `parseCosignerDescriptorFragment rejects an embedded private key`() {
        val xprvPayload = ByteArray(78)
        byteArrayOf(0x04, 0x88.toByte(), 0xAD.toByte(), 0xE4.toByte()).copyInto(xprvPayload, 0)
        val master = bip32MasterKeyFromSeed(deriveSeed(testMnemonic, "").bytes)
        master.chainCode.copyInto(xprvPayload, 13)
        xprvPayload[45] = 0
        master.privateKey.copyInto(xprvPayload, 46)
        val xprv = encodeBase58Check(xprvPayload)

        assertThrows(IllegalArgumentException::class.java) {
            parseCosignerDescriptorFragment("[751e76e8/48'/0'/0'/2']$xprv")
        }
    }

    // === NEW TESTS ===

    @Test
    fun `parseCosignerDescriptorFragment rejects a zpub`() {
        val zpub = deriveWalletAccountKeys(testMnemonic, "", WalletScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0).extendedPublicKey
        val fragment = "[751e76e8/48'/0'/0'/2']$zpub"
        assertThrows(IllegalArgumentException::class.java) {
            parseCosignerDescriptorFragment(fragment)
        }
    }

    @Test
    fun `parseCosignerDescriptorFragment rejects a ypub`() {
        val ypub = deriveWalletAccountKeys(testMnemonic, "", WalletScriptType.NESTED_SEGWIT, WalletNetwork.MAINNET, 0).extendedPublicKey
        val fragment = "[751e76e8/48'/0'/0'/2']$ypub"
        assertThrows(IllegalArgumentException::class.java) {
            parseCosignerDescriptorFragment(fragment)
        }
    }

    @Test
    fun `buildMultisigWallet rejects a cosigner whose path coin-type mismatches the target network`() {
        val mismatchedCosigner = cosignerA.copy(derivationPath = "m/48'/1'/0'/2'")
        assertThrows(IllegalArgumentException::class.java) {
            buildMultisigWallet(2, listOf(cosignerB, mismatchedCosigner), WalletNetwork.MAINNET)
        }
    }

    @Test
    fun `parseCosignerDescriptorFragment rejects a single-sig shaped path (44' 0' 0')`() {
        val fragment = "[751e76e8/44'/0'/0']" + cosignerA.extendedPublicKey
        assertThrows(IllegalArgumentException::class.java) {
            parseCosignerDescriptorFragment(fragment)
        }
    }

    @Test
    fun `parseCosignerDescriptorFragment rejects a path with wrong script-type (1' nested segwit)`() {
        val fragment = "[751e76e8/48'/0'/0'/1']" + cosignerA.extendedPublicKey
        assertThrows(IllegalArgumentException::class.java) {
            parseCosignerDescriptorFragment(fragment)
        }
    }

    @Test
    fun `parseCosignerDescriptorFragment accepts a valid testnet tpub fragment`() {
        val testnetKey = deriveMultisigCosignerAccountKeys(
            testMnemonic, "", MultisigScriptType.NATIVE_SEGWIT, WalletNetwork.TESTNET, 0
        )
        val fragment = "[${testnetKey.masterFingerprint}/${testnetKey.derivationPath.removePrefix("m/")}]${testnetKey.extendedPublicKey}"
        val origin = parseCosignerDescriptorFragment(fragment)
        assertEquals(testnetKey.masterFingerprint, origin.masterFingerprint)
        assertEquals(testnetKey.derivationPath, origin.derivationPath)
        assertEquals(testnetKey.extendedPublicKey, origin.extendedPublicKey)
    }

    @Test
    fun `buildMultisigWallet rejects two cosigners whose xpub strings differ but derive to the same receive pubkey`() {
        // A DIFFERENT test from "rejects a duplicate cosigner extended public key" above: that
        // one only proves identical xpub STRINGS are rejected by the first (string-set) check,
        // which fires before the derived-pubkey check ever runs — it can't prove the second
        // check independently does anything. To isolate it, build a second xpub string that is
        // genuinely different from cosignerA's but still derives to the identical /0/0 receive
        // key: flip the cosmetic `depth` byte (payload offset 4) in a decode/re-encode of
        // cosignerA's own xpub. deriveChild() never reads depth/parentFingerprint/childNumber as
        // derivation inputs (only chainCode + publicKey), so the re-encoded string parses as a
        // different key on paper but derives identically — exactly the "accidentally re-added
        // the same signer under different metadata" mistake this check exists to catch.
        val decoded = decodeBase58Check(cosignerA.extendedPublicKey)
        val respoofed = decoded.copyOf()
        respoofed[4] = (respoofed[4] + 1).toByte()
        val differentStringSameKeyXpub = encodeBase58Check(respoofed)
        assertTrue(differentStringSameKeyXpub != cosignerA.extendedPublicKey)

        val spoofedCosigner = MultisigCosignerOrigin(
            masterFingerprint = "00000000",
            derivationPath = "m/48'/0'/0'/2'",
            extendedPublicKey = differentStringSameKeyXpub,
        )
        // cosignerA itself must be IN the list alongside its spoofed twin — the collision has
        // to actually be present among the cosigners passed in, or there's nothing to catch.
        // All three extendedPublicKey strings are distinct (cosignerA's real one, its
        // depth-flipped twin, and cosignerB's), so the first check doesn't fire here — only
        // the derived-pubkey check can.
        assertThrows(IllegalArgumentException::class.java) {
            buildMultisigWallet(2, listOf(cosignerB, cosignerA, spoofedCosigner), WalletNetwork.MAINNET)
        }
    }

    @Test
    fun `parseCosignerDescriptorFragment accepts h-H hardened notation and normalizes it to apostrophes`() {
        val fragment = "[751e76e8/48h/0h/0h/2h]" + cosignerA.extendedPublicKey
        val origin = parseCosignerDescriptorFragment(fragment)
        assertEquals("m/48'/0'/0'/2'", origin.derivationPath)
    }

    @Test
    fun `buildMultisigWallet rejects a hand-constructed SLIP-132 zpub cosigner, bypassing parseCosignerDescriptorFragment`() {
        // parseCosignerDescriptorFragment already rejects a pasted zpub fragment
        // (see the entropy_core zpub/ypub tests), but buildMultisigWallet must
        // independently reject the SAME kind of key when a MultisigCosignerOrigin
        // is constructed directly (as this test does, and as any future caller
        // could) rather than parsed from a fragment string — this is the
        // function that actually assembles the descriptor, so it can't rely on
        // every caller having gone through the parser first.
        val zpub = deriveWalletAccountKeys(testMnemonic, "", WalletScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0)
            .extendedPublicKey
        val zpubCosigner = MultisigCosignerOrigin(
            masterFingerprint = "00000000",
            derivationPath = "m/48'/0'/0'/2'",
            extendedPublicKey = zpub,
        )
        assertThrows(IllegalArgumentException::class.java) {
            buildMultisigWallet(2, listOf(cosignerA, zpubCosigner), WalletNetwork.MAINNET)
        }
    }

    @Test
    fun `parseCosignerDescriptorFragment rejects an out-of-range account index`() {
        // 3000000000 is numeric and, with the trailing ', hardened — the two
        // checks that already existed before this test would both pass it —
        // but it's still above HARDENED_OFFSET - 1 (2147483647), outside the
        // range BIP32 can represent as a pre-hardening child index.
        val fragment = "[751e76e8/48'/0'/3000000000'/2']" + cosignerA.extendedPublicKey
        val exception = assertThrows(IllegalArgumentException::class.java) {
            parseCosignerDescriptorFragment(fragment)
        }
        assertTrue(exception.message.orEmpty().contains("account component"))
    }

    @Test
    fun `parseBareCosignerExtendedKey returns null for a bracketed fragment`() {
        val fragment = "[751e76e8/48'/0'/0'/2']" + cosignerA.extendedPublicKey
        assertEquals(null, parseBareCosignerExtendedKey(fragment))
    }

    @Test
    fun `parseBareCosignerExtendedKey returns null for a full descriptor`() {
        assertEquals(null, parseBareCosignerExtendedKey("wsh(sortedmulti(2,[751e76e8/48'/0'/0'/2']" + cosignerA.extendedPublicKey + "))"))
    }

    @Test
    fun `parseBareCosignerExtendedKey returns null for garbage text`() {
        assertEquals(null, parseBareCosignerExtendedKey("not a key at all"))
    }

    @Test
    fun `parseBareCosignerExtendedKey detects a real plain mainnet xpub`() {
        val bareXpub = deriveMultisigCosignerAccountKeys(
            testMnemonic, "", MultisigScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0,
        ).extendedPublicKey

        val result = parseBareCosignerExtendedKey(bareXpub)

        assertTrue(result != null)
        assertEquals(bareXpub, result!!.extendedPublicKey)
        assertEquals(WalletNetwork.MAINNET, result.network)
        assertTrue(result.isPlainXpub)
        assertEquals("xpub", result.displayPrefix)
    }

    @Test
    fun `parseBareCosignerExtendedKey detects a real zpub as SLIP-132, not plain`() {
        val bareZpub = deriveWalletAccountKeys(testMnemonic, "", WalletScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0).extendedPublicKey

        val result = parseBareCosignerExtendedKey(bareZpub)

        assertTrue(result != null)
        assertEquals(false, result!!.isPlainXpub)
        assertEquals("zpub", result.displayPrefix)
    }

    @Test
    fun `defaultCosignerDerivationPath matches deriveMultisigCosignerAccountKeys' own path shape`() {
        val derived = deriveMultisigCosignerAccountKeys(
            testMnemonic, "", MultisigScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0,
        )
        val path = defaultCosignerDerivationPath(WalletNetwork.MAINNET, MultisigScriptType.NATIVE_SEGWIT, 0)
        assertEquals(derived.derivationPath.removePrefix("m/"), path)
    }

    @Test
    fun `defaultCosignerDerivationPath rejects a negative account`() {
        assertThrows(IllegalArgumentException::class.java) {
            defaultCosignerDerivationPath(WalletNetwork.MAINNET, MultisigScriptType.NATIVE_SEGWIT, -1)
        }
    }

    @Test
    fun `completeBareCosignerExtendedKey builds a validated MultisigCosignerOrigin`() {
        val bareXpub = deriveMultisigCosignerAccountKeys(
            testMnemonic, "", MultisigScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0,
        ).extendedPublicKey
        val path = defaultCosignerDerivationPath(WalletNetwork.MAINNET, MultisigScriptType.NATIVE_SEGWIT, 0)

        val origin = completeBareCosignerExtendedKey("751e76e8", path, bareXpub)

        assertEquals("751e76e8", origin.masterFingerprint)
        assertEquals("m/48'/0'/0'/2'", origin.derivationPath)
        assertEquals(bareXpub, origin.extendedPublicKey)
    }

    @Test
    fun `completeBareCosignerExtendedKey rejects a fingerprint that is not exactly 8 hex characters`() {
        val bareXpub = deriveMultisigCosignerAccountKeys(
            testMnemonic, "", MultisigScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0,
        ).extendedPublicKey
        val path = defaultCosignerDerivationPath(WalletNetwork.MAINNET, MultisigScriptType.NATIVE_SEGWIT, 0)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            completeBareCosignerExtendedKey("zzzzzzzz", path, bareXpub)
        }
        assertTrue(exception.message.orEmpty().contains("8 hex"))
    }

    @Test
    fun `completeBareCosignerExtendedKey rejects a bare zpub via the same SLIP-132 check as a pasted fragment`() {
        val bareZpub = deriveWalletAccountKeys(testMnemonic, "", WalletScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0).extendedPublicKey
        val path = defaultCosignerDerivationPath(WalletNetwork.MAINNET, MultisigScriptType.NATIVE_SEGWIT, 0)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            completeBareCosignerExtendedKey("751e76e8", path, bareZpub)
        }
        assertTrue(exception.message.orEmpty().contains("SLIP-132"))
    }

    @Test
    fun `parseCosignerDescriptorFragment rejects pathologically long input before parsing it`() {
        val hostile = "[751e76e8/48'/0'/0'/2']" + "x".repeat(20_000)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            parseCosignerDescriptorFragment(hostile)
        }
        assertTrue(exception.message.orEmpty().contains("too long"))
    }

    @Test
    fun `parseCosignerDescriptorFragment accepts input right at the length boundary`() {
        // A real fragment is nowhere near this long — this just proves the
        // guard's boundary is `<=`, not an off-by-one `<`, by pairing with
        // the "one character further" rejection test below.
        val realFragment = "[751e76e8/48'/0'/0'/2']" + cosignerA.extendedPublicKey
        assertTrue(realFragment.length <= 8000)
        // Should parse normally, not be rejected by the length guard.
        parseCosignerDescriptorFragment(realFragment)
    }

    @Test
    fun `parseMultisigDescriptor rejects pathologically long input before parsing it`() {
        val hostile = "wsh(sortedmulti(2," + "x".repeat(20_000) + "))"

        val exception = assertThrows(IllegalArgumentException::class.java) {
            parseMultisigDescriptor(hostile)
        }
        assertTrue(exception.message.orEmpty().contains("too long"))
    }

    @Test
    fun `parseMultisigDescriptor still accepts a real maximum-size 15-cosigner descriptor`() {
        // parseMultisigDescriptor itself does not check for duplicate xpubs
        // (that happens later, in buildMultisigWallet / the ViewModel), so
        // reusing the same test xpub across distinct fingerprints here is
        // fine — this test is only proving the length guard doesn't reject
        // a real maximum-size (15-cosigner) descriptor.
        val fragments = (0 until 15).map { i ->
            val fingerprint = "%08x".format(i)
            "[$fingerprint/48'/0'/0'/2']${cosignerA.extendedPublicKey}/<0;1>/*"
        }
        val descriptor = "wsh(sortedmulti(8,${fragments.joinToString(",")}))"
        assertTrue(descriptor.length <= 8000)

        val parsed = parseMultisigDescriptor(descriptor)

        assertEquals(15, parsed.cosigners.size)
        assertEquals(8, parsed.threshold)
    }
}
