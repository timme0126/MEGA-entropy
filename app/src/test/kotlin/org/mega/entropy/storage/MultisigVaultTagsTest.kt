package org.mega.entropy.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mega.entropycore.MultisigScriptType
import org.mega.entropycore.WalletNetwork

class MultisigVaultTagsTest {
    private val cosigner = SavedMultisigCosigner(
        label = "Alice",
        masterFingerprint = "751e76e8",
        derivationPath = "m/48'/0'/0'/2'",
        extendedPublicKey = "xpub6DkFAXWQ2dHxq2vatrt9qyA3bXYU4ToWQwCHbf5XB2mSTexcHZCeKS1VZYcPoBd5X8yVcbXFHJR9R8UCVpt82VX1VhR28mCyxUFL4r6KFrf",
        passphraseUsed = true,
    )

    private fun vault(tags: List<String>) = SavedMultisigVault(
        id = "vault-tags",
        createdAtEpochMillis = 1L,
        label = "Cold storage",
        threshold = 1,
        network = WalletNetwork.MAINNET,
        scriptType = MultisigScriptType.NATIVE_SEGWIT,
        cosigners = listOf(cosigner),
        tags = tags,
    )

    @Test
    fun `tags round trip exactly`() {
        val original = vault(listOf("cold storage", "inheritance"))
        val decoded = decodeMultisigVault(encodeMultisigVault(original))
        assertEquals(original.tags, decoded.tags)
        assertEquals(original, decoded)
    }

    @Test
    fun `a tag can contain a comma or pipe without corrupting decoding`() {
        val original = vault(listOf("has, a comma", "has | a pipe"))
        val decoded = decodeMultisigVault(encodeMultisigVault(original))
        assertEquals(original.tags, decoded.tags)
    }

    @Test
    fun `no tags encodes and decodes back to an empty list`() {
        val original = vault(emptyList())
        val decoded = decodeMultisigVault(encodeMultisigVault(original))
        assertEquals(emptyList<String>(), decoded.tags)
    }

    @Test
    fun `a vault file written before the tags64 line existed still decodes, with empty tags`() {
        val original = vault(listOf("would have had a tag"))
        val encoded = encodeMultisigVault(original).decodeToString()
        // Strip the trailing tags64 line entirely, simulating a real
        // pre-tags vault file already on disk from an older app version.
        val withoutTagsLine = encoded.lines().dropLast(1).joinToString("\n")

        val decoded = decodeMultisigVault(withoutTagsLine.toByteArray())

        assertEquals(emptyList<String>(), decoded.tags)
        assertEquals(original.label, decoded.label)
        assertEquals(original.cosigners, decoded.cosigners)
    }
}
