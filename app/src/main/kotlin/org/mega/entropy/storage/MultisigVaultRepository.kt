package org.mega.entropy.storage

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mega.entropycore.MultisigScriptType
import org.mega.entropycore.WalletNetwork

/**
 * Saves, lists, renames, and deletes multisig vaults built by "Setup
 * Multi-Signature Vault". Deliberately much simpler than SessionRepository:
 * there is no Android Keystore key, no encryption, no meta/enc file split —
 * see SavedMultisigVault's doc comment for why a plaintext file is the
 * right call for this specific data.
 */
class MultisigVaultRepository(context: Context) {
    private val fileStore = MultisigVaultFileStore(context.filesDir)

    /**
     * Saves a new vault to disk.
     *
     * WHY: withContext(Dispatchers.IO) keeps file I/O off the main thread,
     * same as SessionRepository. The vault ID is a random UUID, purely a
     * storage identifier — independent of anything cryptographic.
     */
    suspend fun saveVault(
        threshold: Int,
        network: WalletNetwork,
        scriptType: MultisigScriptType,
        cosigners: List<SavedMultisigCosigner>,
        label: String,
    ): SavedMultisigVault {
        // Every saved vault must be labeled — MegaLabelSessionDialog already
        // blocks its own Save button on a blank label, but every caller
        // reaches this through that same dialog, so it costs nothing to
        // also refuse it here at the actual point of writing (the same
        // belt-and-suspenders SessionRepository.saveSession applies).
        require(label.isNotBlank()) { "label must not be blank" }
        require(cosigners.isNotEmpty()) { "A vault must have at least one cosigner" }
        return withContext(Dispatchers.IO) {
            val vault = SavedMultisigVault(
                id = UUID.randomUUID().toString(),
                createdAtEpochMillis = System.currentTimeMillis(),
                label = label,
                threshold = threshold,
                network = network,
                scriptType = scriptType,
                cosigners = cosigners,
            )
            fileStore.writeVaultFile(vault)
            vault
        }
    }

    suspend fun listVaults(): List<SavedMultisigVault> {
        return withContext(Dispatchers.IO) {
            fileStore.listAllVaults().sortedByDescending { it.createdAtEpochMillis }
        }
    }

    suspend fun loadVault(vaultId: String): SavedMultisigVault {
        return withContext(Dispatchers.IO) {
            fileStore.readVaultFile(vaultId)
                ?: throw NoSuchElementException("Multisig vault not found for ID: $vaultId")
        }
    }

    suspend fun renameVault(vaultId: String, label: String) {
        require(label.isNotBlank()) { "label must not be blank" }
        withContext(Dispatchers.IO) {
            val vault = fileStore.readVaultFile(vaultId)
                ?: throw NoSuchElementException("Multisig vault not found for ID: $vaultId")
            fileStore.writeVaultFile(vault.copy(label = label))
        }
    }

    suspend fun deleteVault(vaultId: String) {
        withContext(Dispatchers.IO) {
            fileStore.deleteVaultFile(vaultId)
        }
    }
}
