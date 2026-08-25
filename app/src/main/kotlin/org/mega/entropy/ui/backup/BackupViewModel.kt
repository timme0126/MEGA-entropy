package org.mega.entropy.ui.backup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mega.entropy.security.pin.PinManager
import org.mega.entropy.storage.BackupRepository

sealed class BackupOperationResult {
    data class ExportSucceeded(val fileBytes: ByteArray) : BackupOperationResult()
    data class ExportFailed(val message: String) : BackupOperationResult()
    data class ImportSucceeded(val sessionCount: Int, val vaultCount: Int) : BackupOperationResult()
    data class ImportFailed(val message: String) : BackupOperationResult()
}

data class BackupUiState(
    val isWorking: Boolean = false,
    val result: BackupOperationResult? = null,
)

/**
 * Thin UI-state wrapper around BackupRepository — see its doc comment for
 * the actual export/import design. Uses AndroidViewModel purely for a
 * Context, same rationale as SavedSessionsViewModel; no DI framework.
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BackupRepository(application)
    private val pinManager = PinManager(application.filesDir)

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /**
     * Same rule the dice flow's Save screen and Advanced Mode's save path
     * already enforce ("saving any data requires a MEGA PIN to already
     * exist") — a restore is still a write of new saved data, so it must
     * not be able to bypass that rule just because it arrived via a file
     * instead of the normal save flow.
     */
    suspend fun isPinMissing(): Boolean = withContext(Dispatchers.IO) { !pinManager.isPinEnabled() }

    fun exportBackup(passphrase: String) {
        _uiState.update { it.copy(isWorking = true, result = null) }
        viewModelScope.launch {
            val result = try {
                BackupOperationResult.ExportSucceeded(repository.exportBackup(passphrase))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BackupOperationResult.ExportFailed(e.message ?: "Could not build the backup file.")
            }
            _uiState.update { it.copy(isWorking = false, result = result) }
        }
    }

    fun importBackup(fileBytes: ByteArray, passphrase: String) {
        _uiState.update { it.copy(isWorking = true, result = null) }
        viewModelScope.launch {
            val result = try {
                val outcome = repository.importBackup(fileBytes, passphrase)
                BackupOperationResult.ImportSucceeded(outcome.sessionCount, outcome.vaultCount)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AEADBadTagException) {
                BackupOperationResult.ImportFailed("Wrong passphrase, or this backup file is corrupted or was tampered with.")
            } catch (e: IllegalArgumentException) {
                BackupOperationResult.ImportFailed(e.message ?: "This doesn't look like a MEGA backup file.")
            } catch (e: IllegalStateException) {
                BackupOperationResult.ImportFailed(e.message ?: "This backup file is corrupted.")
            } catch (e: Exception) {
                BackupOperationResult.ImportFailed(e.message ?: "Could not import this backup file.")
            }
            _uiState.update { it.copy(isWorking = false, result = result) }
        }
    }

    fun clearResult() {
        _uiState.update { it.copy(result = null) }
    }
}
