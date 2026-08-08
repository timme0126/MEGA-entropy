package org.mega.entropy.ui.savedsessions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mega.entropy.security.pin.PinManager
import org.mega.entropy.storage.SavedSessionMetadata
import org.mega.entropy.storage.SessionRepository

data class SavedSessionsUiState(
    val sessions: List<SavedSessionMetadata> = emptyList(),
    val isLoading: Boolean = true,
    val isPinEnabled: Boolean = false,
)

/**
 * Lists, deletes, and (individually) deletes-all saved sessions. Uses
 * AndroidViewModel purely to get an application Context for
 * SessionRepository — the repository itself is manually constructed here
 * rather than injected, per this project's "no DI framework" preference.
 */
class SavedSessionsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SessionRepository(application)
    private val pinManager = PinManager(application)

    private val _uiState = MutableStateFlow(SavedSessionsUiState())
    val uiState: StateFlow<SavedSessionsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun disablePin() {
        viewModelScope.launch {
            pinManager.disablePin()
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val sessions = repository.listSessions()
            val pinEnabled = pinManager.isPinEnabled()
            _uiState.update { it.copy(sessions = sessions, isLoading = false, isPinEnabled = pinEnabled) }
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            repository.deleteSession(id)
            refresh()
        }
    }

    fun deleteAllSessions() {
        viewModelScope.launch {
            repository.deleteAllSessions()
            refresh()
        }
    }

    fun renameSession(id: String, label: String) {
        viewModelScope.launch {
            repository.renameSession(id, label)
            refresh()
        }
    }
}
