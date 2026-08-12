package org.mega.entropy.ui.advancedmode.multisig

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mega.entropy.storage.MultisigVaultRepository
import org.mega.entropy.storage.SavedMultisigVault

data class SavedMultisigVaultsUiState(
    val vaults: List<SavedMultisigVault> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * Lists, renames, and deletes saved multisig vaults. Uses AndroidViewModel
 * purely to get an application Context for MultisigVaultRepository, the
 * same reason SavedSessionsViewModel does — the repository itself is
 * manually constructed here rather than injected, per this project's "no
 * DI framework" preference.
 *
 * Unlike SavedSessionsViewModel, there is no PIN/isPinEnabled state here:
 * a saved multisig vault contains only public key material (see
 * SavedMultisigVault's doc comment), so this list is intentionally not
 * PIN-gated — MegaNavGraph routes straight to it from the "Multi-Signature
 * Vaults" button.
 */
class SavedMultisigVaultsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MultisigVaultRepository(application)

    private val _uiState = MutableStateFlow(SavedMultisigVaultsUiState())
    val uiState: StateFlow<SavedMultisigVaultsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val vaults = repository.listVaults()
            _uiState.update { it.copy(vaults = vaults, isLoading = false) }
        }
    }

    fun deleteVault(id: String) {
        viewModelScope.launch {
            repository.deleteVault(id)
            refresh()
        }
    }

    fun renameVault(id: String, label: String) {
        viewModelScope.launch {
            repository.renameVault(id, label)
            refresh()
        }
    }
}
