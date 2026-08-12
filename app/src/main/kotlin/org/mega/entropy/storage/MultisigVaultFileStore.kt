package org.mega.entropy.storage

import java.io.File

private val VALID_VAULT_ID = Regex("[A-Za-z0-9_-]{1,100}")

/** Takes the base directory directly (the caller passes context.filesDir)
 * rather than a Context, so this class can be unit-tested with plain JVM
 * File I/O — a temp directory — with no Android framework dependency. Same
 * pattern SessionFileStore uses. */
class MultisigVaultFileStore(private val baseDir: File) {
    /**
     * Resolves the app-private internal storage directory for saved
     * multisig vaults.
     *
     * WHY: context.filesDir keeps this inside the app's sandboxed storage —
     * no external storage permission, no other app can read it — the same
     * reasoning SessionFileStore documents for saved sessions, even though
     * a multisig vault's own contents are public key material rather than
     * a secret.
     */
    private fun vaultsDir(): File {
        val dir = File(baseDir, "mega_multisig_vaults")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun writeVaultFile(vault: SavedMultisigVault) {
        vaultFile(vault.id).writeBytes(encodeMultisigVault(vault))
    }

    fun readVaultFile(vaultId: String): SavedMultisigVault? {
        val file = vaultFile(vaultId)
        return file.takeIf { it.exists() }?.let { decodeMultisigVault(it.readBytes()) }
    }

    fun deleteVaultFile(vaultId: String) {
        vaultFile(vaultId).delete()
    }

    private fun vaultFile(vaultId: String): File {
        require(VALID_VAULT_ID.matches(vaultId)) { "Invalid multisig vault id" }
        return File(vaultsDir(), "$vaultId.vault")
    }

    /**
     * Scans for all saved vault files and parses them.
     *
     * WHY: runCatching around each individual parse so one corrupt vault
     * file doesn't break listing every other saved vault — the same
     * availability-over-strictness tradeoff SessionFileStore.listAllMetadata()
     * makes.
     */
    fun listAllVaults(): List<SavedMultisigVault> {
        return vaultsDir()
            .listFiles { _, name -> name.endsWith(".vault") }
            ?.mapNotNull { file ->
                runCatching { decodeMultisigVault(file.readBytes()) }.getOrNull()
            }
            ?: emptyList()
    }
}
