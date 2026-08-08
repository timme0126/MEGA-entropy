package org.mega.entropy.ui.pin

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide lock state for the optional MEGA PIN (spec section 22). Scoped
 * at the Activity level (constructed once via viewModel() with no back
 * stack entry override, so it survives navigation) so that backgrounding
 * the app from ANY screen re-locks saved-session access, not just when
 * backgrounded from a specific screen.
 *
 * Starts locked. MainActivity calls lock() on ON_STOP. Whether that
 * actually gates anything depends on whether a PIN is configured at all —
 * this class only tracks the lock bit, it doesn't know about PinManager.
 */
class AppLockViewModel : ViewModel() {
    private val _isLocked = MutableStateFlow(true)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    fun lock() {
        _isLocked.value = true
    }

    fun unlock() {
        _isLocked.value = false
    }
}
