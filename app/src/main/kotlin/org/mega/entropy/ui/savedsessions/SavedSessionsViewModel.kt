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
    val isDuressPinEnabled: Boolean = false,
    /** Every distinct tag across all [sessions], for the filter row —
     * recomputed on every [refresh] rather than tracked independently, so
     * it can never drift from what the sessions actually carry. */
    val allTags: List<String> = emptyList(),
    /** Tags currently selected in the filter row — "any of" matching (a
     * session shows if it carries at least one selected tag), empty means
     * no filter (show everything). See [visibleSessions]. */
    val selectedTagFilters: Set<String> = emptySet(),
) {
    val visibleSessions: List<SavedSessionMetadata>
        get() = if (selectedTagFilters.isEmpty()) {
            sessions
        } else {
            sessions.filter { it.tags.any { tag -> tag in selectedTagFilters } }
        }
}

/**
 * Lists, deletes, and (individually) deletes-all saved sessions. Uses
 * AndroidViewModel purely to get an application Context for
 * SessionRepository — the repository itself is manually constructed here
 * rather than injected, per this project's "no DI framework" preference.
 */
class SavedSessionsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SessionRepository(application)
    private val pinManager = PinManager(application.filesDir)

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
            val duressPinEnabled = pinManager.isDuressPinEnabled()
            _uiState.update {
                it.copy(
                    sessions = sessions,
                    isLoading = false,
                    isPinEnabled = pinEnabled,
                    isDuressPinEnabled = duressPinEnabled,
                    allTags = sessions.flatMap { session -> session.tags }.distinct().sorted(),
                    // Drop any filter selection for a tag that no longer
                    // exists on any session (e.g. it was removed from the
                    // last session that had it) rather than leaving a
                    // stale, unreachable filter silently active.
                    selectedTagFilters = it.selectedTagFilters.filter { tag ->
                        sessions.any { session -> tag in session.tags }
                    }.toSet(),
                )
            }
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

    fun clearDuressPin() {
        viewModelScope.launch {
            pinManager.clearDuressPin()
            refresh()
        }
    }

    fun renameSession(id: String, label: String) {
        viewModelScope.launch {
            repository.renameSession(id, label)
            refresh()
        }
    }

    fun updateTags(id: String, tags: List<String>) {
        viewModelScope.launch {
            repository.updateSessionTags(id, tags)
            refresh()
        }
    }

    fun toggleTagFilter(tag: String) {
        _uiState.update {
            val current = it.selectedTagFilters
            it.copy(selectedTagFilters = if (tag in current) current - tag else current + tag)
        }
    }
}
