package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests-first pass for two planned changes to the multisig parsing
 * surface: full output-descriptor parsing (parseMultisigDescriptor,
 * currently a TODO() stub) and accepting h/H hardened notation in
 * parseCosignerDescriptorFragment (currently still rejected). Every test
 * in this file is EXPECTED TO FAIL until both are implemented — that's
 * the point of writing them first.
 *
 * Local fixtures rather than reusing MultisigDerivationTest's private
 * vals: those are private to that class and not visible from this
 * separate one.
 */
class MultisigDescriptorParsingTest {

    private val testMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        .split(" ")

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
    fun `parseMultisigDescriptor round-trips a 2-of-3 descriptor built by buildMultisigWallet`() {
        val wallet = buildMultisigWallet(2, listOf(cosignerA, cosignerB, cosignerC), WalletNetwork.MAINNET)
        val parsed = parseMultisigDescriptor(wallet.descriptor)
        assertEquals(2, parsed.threshold)
        assertEquals(3, parsed.cosigners.size)
        assertTrue("Parsed cosigners must match the original three", parsed.cosigners.toSet() == setOf(cosignerA, cosignerB, cosignerC))
    }

    @Test
    fun `parseMultisigDescriptor parses a 2-of-2 descriptor`() {
        val wallet = buildMultisigWallet(2, listOf(cosignerA, cosignerB), WalletNetwork.MAINNET)
        val parsed = parseMultisigDescriptor(wallet.descriptor)
        assertEquals(2, parsed.threshold)
        assertEquals(2, parsed.cosigners.size)
    }

    @Test
    fun `parseMultisigDescriptor rejects malformed descriptor wrappers`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseMultisigDescriptor("multi(2,[751e76e8/48'/0'/0'/2']${cosignerA.extendedPublicKey}/<0;1>/*)")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseMultisigDescriptor("wsh(sortedmulti(2,[751e76e8/48'/0'/0'/2']${cosignerA.extendedPublicKey}/<0;1>/*)")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseMultisigDescriptor("sortedmulti(2,[751e76e8/48'/0'/0'/2']${cosignerA.extendedPublicKey}/<0;1>/*)")
        }
    }

    @Test
    fun `parseMultisigDescriptor rejects a descriptor containing a real zpub`() {
        // Uses a REAL, correctly-base58check-encoded zpub (derived the same way
        // MultisigDerivationTest's own zpub-rejection test does) — a fake string
        // that merely starts with the letters "zpub" would fail base58check
        // decoding before ever reaching the SLIP-132 version-byte check this
        // test is actually meant to exercise, which would make the test pass
        // for the wrong reason.
        val zpub = deriveWalletAccountKeys(testMnemonic, "", WalletScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0)
            .extendedPublicKey
        val descriptor = "wsh(sortedmulti(2,[751e76e8/48'/0'/0'/2']$zpub/<0;1>/*," +
            "[06afd46b/48'/0'/0'/2']${cosignerB.extendedPublicKey}/<0;1>/*))"
        assertThrows(IllegalArgumentException::class.java) { parseMultisigDescriptor(descriptor) }
    }

    @Test
    fun `parseMultisigDescriptor rejects descriptors with unsupported text between valid cosigners`() {
        val descriptor = "wsh(sortedmulti(2,[751e76e8/48'/0'/0'/2']${cosignerA.extendedPublicKey}/<0;1>/*," +
            "garbage," +
            "[06afd46b/48'/0'/0'/2']${cosignerB.extendedPublicKey}/<0;1>/*))"
        assertThrows(IllegalArgumentException::class.java) { parseMultisigDescriptor(descriptor) }
    }

    @Test
    fun `parseMultisigDescriptor rejects a descriptor with threshold exceeding cosigner count`() {
        val descriptor = "wsh(sortedmulti(5,[751e76e8/48'/0'/0'/2']${cosignerA.extendedPublicKey}/<0;1>/*," +
            "[06afd46b/48'/0'/0'/2']${cosignerB.extendedPublicKey}/<0;1>/*))"
        assertThrows(IllegalArgumentException::class.java) { parseMultisigDescriptor(descriptor) }
    }

    @Test
    fun `parseCosignerDescriptorFragment accepts h-H notation and normalizes to apostrophes`() {
        val fragment = "[751e76e8/48h/0h/0h/2h]${cosignerA.extendedPublicKey}"
        val origin = parseCosignerDescriptorFragment(fragment)
        assertEquals("m/48'/0'/0'/2'", origin.derivationPath)
    }

    @Test
    fun `parseCosignerDescriptorFragment normalizes mixed h-H and apostrophe notation`() {
        val fragment = "[751e76e8/48'/0h/0'/2H]${cosignerA.extendedPublicKey}"
        val origin = parseCosignerDescriptorFragment(fragment)
        assertEquals("m/48'/0'/0'/2'", origin.derivationPath)
    }

    @Test
    fun `parseCosignerDescriptorFragment produces equal MultisigCosignerOrigin for apostrophe vs h-H notation`() {
        val apostropheFragment = "[751e76e8/48'/0'/0'/2']${cosignerA.extendedPublicKey}"
        val hNotationFragment = "[751e76e8/48h/0h/0h/2h]${cosignerA.extendedPublicKey}"
        assertEquals(parseCosignerDescriptorFragment(apostropheFragment), parseCosignerDescriptorFragment(hNotationFragment))
    }

    @Test
    fun `parseCosignerDescriptorFragment still rejects invalid paths even with h-H notation`() {
        // 48h/0h/0h/1h — h/H notation throughout, but wrong script-type
        // component (1h instead of 2h/2') — proves h/H acceptance didn't
        // accidentally bypass the other structural checks.
        val fragment = "[751e76e8/48h/0h/0h/1h]${cosignerA.extendedPublicKey}"
        assertThrows(IllegalArgumentException::class.java) { parseCosignerDescriptorFragment(fragment) }
    }

    @Test
    fun `single cosigner fragment accepts multipath suffix`() {
        val fragment = "[${cosignerA.masterFingerprint}/48'/0'/0'/2']${cosignerA.extendedPublicKey}/<0;1>/*"

        val parsed = parseCosignerDescriptorFragment(fragment)

        assertEquals(cosignerA.masterFingerprint, parsed.masterFingerprint)
        assertEquals(cosignerA.derivationPath, parsed.derivationPath)
        assertEquals(cosignerA.extendedPublicKey, parsed.extendedPublicKey)
    }

}
