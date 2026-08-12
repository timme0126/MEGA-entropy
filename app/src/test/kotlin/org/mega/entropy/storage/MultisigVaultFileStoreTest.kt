package org.mega.entropy.storage

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mega.entropycore.MultisigScriptType
import org.mega.entropycore.WalletNetwork

class MultisigVaultFileStoreTest {
    private val vault = SavedMultisigVault(
        id = "vault-1",
        createdAtEpochMillis = 1_700_000_000_000L,
        label = "Cold storage",
        threshold = 2,
        network = WalletNetwork.MAINNET,
        scriptType = MultisigScriptType.NATIVE_SEGWIT,
        cosigners = listOf(
            SavedMultisigCosigner("Alice", "751e76e8", "m/48'/0'/0'/2'", "xpubAAA", passphraseUsed = true),
            SavedMultisigCosigner("Bob", "06afd46b", "m/48'/0'/0'/2'", "xpubBBB", passphraseUsed = null),
        ),
    )

    private fun newStore(): MultisigVaultFileStore = MultisigVaultFileStore(createTempDirectory().toFile())

    @Test
    fun `writes and reads back a vault`() {
        val store = newStore()
        store.writeVaultFile(vault)

        assertEquals(vault, store.readVaultFile(vault.id))
    }

    @Test
    fun `readVaultFile returns null for a vault that was never written`() {
        val store = newStore()
        assertNull(store.readVaultFile("does-not-exist"))
    }

    @Test
    fun `listAllVaults finds every written vault`() {
        val store = newStore()
        val second = vault.copy(id = "vault-2", label = "Hot wallet backup")
        store.writeVaultFile(vault)
        store.writeVaultFile(second)

        val listed = store.listAllVaults()
        assertEquals(2, listed.size)
        assertTrue(listed.any { it.id == vault.id })
        assertTrue(listed.any { it.id == second.id })
    }

    @Test
    fun `listAllVaults skips a corrupt vault file without failing the whole list`() {
        val dir = createTempDirectory().toFile()
        val store = MultisigVaultFileStore(dir)
        store.writeVaultFile(vault)
        File(dir, "mega_multisig_vaults/corrupt.vault").writeBytes("not a valid vault file".toByteArray())

        val listed = store.listAllVaults()
        assertEquals(1, listed.size)
        assertEquals(vault.id, listed.single().id)
    }

    @Test
    fun `deleteVaultFile removes the vault so it is no longer listed or readable`() {
        val store = newStore()
        store.writeVaultFile(vault)

        store.deleteVaultFile(vault.id)

        assertNull(store.readVaultFile(vault.id))
        assertTrue(store.listAllVaults().isEmpty())
    }
    @Test
    fun `rejects unsafe vault ids`() {
        val store = newStore()

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            store.readVaultFile("../escape")
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            store.deleteVaultFile("nested/path")
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            store.writeVaultFile(vault.copy(id = "bad.id"))
        }
    }

}
