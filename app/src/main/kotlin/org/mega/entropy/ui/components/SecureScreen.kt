package org.mega.entropy.ui.components

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Applies FLAG_SECURE for as long as the caller is composed, per spec
 * section 15: sensitive screens (dice history once meaningful entropy
 * exists, E/entropy hex, SHA-256 details, the mnemonic, saved-vault
 * screens, PIN screens) must not appear in screenshots or the recent-apps
 * thumbnail. The flag is removed on dispose so non-sensitive screens
 * (Welcome, How It Works, etc.) are never accidentally left protected —
 * and, more importantly, so it's cleared even if the composable is removed
 * abnormally, since FLAG_SECURE is a window-level flag, not per-composable.
 */
@Composable
fun SecureScreen() {
    val view = LocalView.current
    DisposableEffect(view) {
        val activity = view.context as? Activity
        activity?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
