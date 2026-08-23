package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Official BIP86 test vectors, verbatim from
 * github.com/bitcoin/bips/blob/master/bip-0086.mediawiki (fetched and
 * transcribed directly from the spec's own worked example, not from any
 * delegated summary): the standard all-"abandon"-ending-in-"about" BIP39
 * test mnemonic, empty passphrase, account 0, mainnet.
 *
 * Confirms end-to-end: mnemonic -> BIP86 account xpub/address (public
 * side, via deriveWalletAccountKeys) matches the spec exactly, and the
 * WIF deriveWalletReceivePrivateKey exports is genuinely the TWEAKED
 * signing key (its Schnorr x-only pubkey equals the vector's own
 * output_key) rather than the untweaked internal key.
 */
class Bip86OfficialVectorsTest {

    private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Minimal inline WIF decode (version byte + 32-byte key + optional
     * compressed-flag byte) - just enough to pull the raw private key back
     * out for this test, without needing a decodeWif in production code. */
    private fun decodeWifPrivateKey(wif: String): ByteArray {
        val payload = decodeBase58Check(wif)
        require(payload.size == 33 || payload.size == 34) { "Unexpected WIF payload length: ${payload.size}" }
        return payload.copyOfRange(1, 33)
    }

    @Test
    fun `account 0 xpub and first receive address match the BIP86 spec`() {
        val keys = deriveWalletAccountKeys(mnemonic, "", WalletScriptType.TAPROOT, WalletNetwork.MAINNET, account = 0)
        assertEquals("m/86'/0'/0'", keys.derivationPath)
        assertEquals(
            "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ",
            keys.extendedPublicKey,
        )
        assertEquals("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr", keys.firstReceiveAddress)
    }

    @Test
    fun `account 0 first receive address's exported WIF is the tweaked signing key, matching the spec's output_key`() {
        // masterFingerprint isn't given directly in BIP86's own worked
        // example text, so it's taken from deriveWalletAccountKeys' own
        // result (already checked against the spec's xpub/address in the
        // test above) rather than a hand-copied/guessed value here.
        val accountKeys = deriveWalletAccountKeys(mnemonic, "", WalletScriptType.TAPROOT, WalletNetwork.MAINNET, account = 0)
        val receiveKey = deriveWalletReceivePrivateKey(mnemonic, "", WalletScriptType.TAPROOT, WalletNetwork.MAINNET, account = 0)
        assertEquals("m/86'/0'/0'/0/0", receiveKey.derivationPath)
        assertEquals(
            "tr([${accountKeys.masterFingerprint}/86'/0'/0']${accountKeys.extendedPublicKey}/0/0)",
            receiveKey.descriptor,
        )

        val privateKey = decodeWifPrivateKey(receiveKey.wif)
        val signingPubkey = Schnorr.xOnlyPubKeyFromPrivateKey(privateKey)
        assertEquals("a60869f0dbcf1dc659c9cecbaf8050135ea9e8cdc487053f1dc6880949dc684c", signingPubkey.toHex())
    }
}
