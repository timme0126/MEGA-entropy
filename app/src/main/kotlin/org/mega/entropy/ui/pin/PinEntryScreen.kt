package org.mega.entropy.ui.pin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.SecureScreen

const val PIN_MIN_LENGTH = 5
const val PIN_MAX_LENGTH = 8

/**
 * The in-app numeric PIN pad (spec section 20). This screen only collects
 * digits and reports the finished PIN string via [onSubmit] — it does not
 * know how to hash, verify, store, or rate-limit a PIN itself. That logic
 * (PinManager, in the storage/security layer) is deliberately kept
 * separate so nothing here needs to touch Android Keystore, and so a
 * reviewer auditing "where can the PIN leak" only has one file to check.
 *
 * The PIN is held only as a transient List<Int> in Compose state — never
 * placed in a String until the moment [onSubmit] is called, never logged,
 * never put in SavedStateHandle (this screen takes no saved-instance-state
 * parameters at all).
 */
@Composable
fun PinEntryScreen(
    title: String,
    subtitle: String? = null,
    errorMessage: String? = null,
    // Scrambled (spec section 21) for unlocking/verifying an existing PIN,
    // where shoulder-surfing resistance matters. Standard ordered dial-pad
    // for choosing a new PIN, matching the layout users already expect from
    // every phone's own PIN/passcode setup — nothing to protect against yet
    // since no PIN exists until this step completes.
    scrambled: Boolean = true,
    onSubmit: (String) -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    SecureScreen()
    // No visible Cancel button (most screens using this already have a back
    // gesture/tray in the system UI) — but callers that pass onCancel still
    // need its cleanup (e.g. clearing a pending save) to run on that back
    // gesture, so it's wired here instead of dropped.
    onCancel?.let { BackHandler(onBack = it) }
    var enteredDigits by remember { mutableStateOf(listOf<Int>()) }
    var shuffleGeneration by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        errorMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        PinDots(enteredCount = enteredDigits.size, maxLength = PIN_MAX_LENGTH)

        ScrambledKeypad(
            shuffleKey = shuffleGeneration,
            scrambled = scrambled,
            onDigitTapped = { digit ->
                if (enteredDigits.size < PIN_MAX_LENGTH) {
                    enteredDigits = enteredDigits + digit
                }
            },
            onDelete = { enteredDigits = enteredDigits.dropLast(1) },
            onClear = { enteredDigits = emptyList() },
            deleteEnabled = enteredDigits.isNotEmpty(),
            clearEnabled = enteredDigits.isNotEmpty(),
        )

        MegaPrimaryButton(
            text = "Submit",
            enabled = enteredDigits.size in PIN_MIN_LENGTH..PIN_MAX_LENGTH,
            onClick = {
                onSubmit(enteredDigits.joinToString(""))
                enteredDigits = emptyList()
                // Reshuffle after every submit attempt, matching "after an
                // incorrect attempt" from spec section 21 — harmless to also
                // reshuffle on a correct one, since the screen is about to
                // navigate away anyway.
                shuffleGeneration++
            },
        )
    }
}
