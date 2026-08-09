package org.mega.entropy.ui.pin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import org.mega.entropy.security.pin.PinManager
import org.mega.entropy.security.pin.PinVerifyResult
import org.mega.entropy.storage.SessionRepository

/**
 * Stateful wrapper around PinEntryScreen for the "unlock" case: verifies
 * against the stored PIN via PinManager and calls [onUnlocked] only on a
 * PinVerifyResult.Correct. Never navigates forward on anything else.
 */
@Composable
fun PinVerifyScreen(
    onUnlocked: () -> Unit,
    onCancel: () -> Unit,
    randomizeKeypad: Boolean = true,
    onDuressWipe: () -> Unit = onCancel,
) {
    val context = LocalContext.current
    val pinManager = remember { PinManager(context) }
    val repository = remember { SessionRepository(context) }
    val coroutineScope = rememberCoroutineScope()
    var errorMessage by remember { mutableStateOf<String?>(null) }

    PinEntryScreen(
        title = "Enter MEGA PIN",
        subtitle = "Unlock to view saved sessions",
        errorMessage = errorMessage,
        scrambled = randomizeKeypad,
        onCancel = onCancel,
        onSubmit = { pin ->
            coroutineScope.launch {
                when (val result = pinManager.verifyPin(pin)) {
                    PinVerifyResult.Correct -> {
                        errorMessage = null
                        onUnlocked()
                    }
                    is PinVerifyResult.Incorrect -> {
                        errorMessage = "Incorrect PIN (attempt ${result.failedAttempts})"
                    }
                    is PinVerifyResult.Locked -> {
                        val until = DateFormat.getTimeInstance().format(Date(result.lockedUntilEpochMillis))
                        errorMessage = "Too many attempts. Try again after $until."
                    }
                    PinVerifyResult.Duress -> {
                        repository.deleteAllSessions()
                        errorMessage = null
                        onDuressWipe()
                    }
                    PinVerifyResult.NoPinConfigured -> {
                        // Shouldn't happen (caller only shows this screen when a PIN
                        // exists), but fail safe by letting the user back out rather
                        // than silently unlocking.
                        errorMessage = "No PIN is configured."
                    }
                }
            }
        },
    )
}
