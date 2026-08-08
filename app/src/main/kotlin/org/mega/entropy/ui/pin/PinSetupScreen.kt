package org.mega.entropy.ui.pin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import org.mega.entropy.security.pin.PinManager

private enum class SetupStep { ENTER, CONFIRM }

/**
 * Two-step "enter, then confirm" PIN setup (spec section 20). A mismatch
 * on the confirm step restarts from ENTER rather than silently accepting
 * whichever value came last — the user re-types both times.
 */
@Composable
fun PinSetupScreen(
    onPinSet: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val pinManager = remember { PinManager(context) }
    val coroutineScope = rememberCoroutineScope()

    var step by remember { mutableStateOf(SetupStep.ENTER) }
    var firstEntry by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    when (step) {
        SetupStep.ENTER -> {
            PinEntryScreen(
                title = "Choose a MEGA PIN",
                subtitle = "5 to 8 digits",
                errorMessage = errorMessage,
                onCancel = onCancel,
                onSubmit = { pin ->
                    firstEntry = pin
                    errorMessage = null
                    step = SetupStep.CONFIRM
                },
            )
        }
        SetupStep.CONFIRM -> {
            PinEntryScreen(
                title = "Confirm Your PIN",
                subtitle = "Enter the same PIN again",
                errorMessage = errorMessage,
                onCancel = onCancel,
                onSubmit = { pin ->
                    if (pin == firstEntry) {
                        coroutineScope.launch {
                            pinManager.setPin(pin)
                            onPinSet()
                        }
                    } else {
                        errorMessage = "PINs didn't match — start over."
                        firstEntry = null
                        step = SetupStep.ENTER
                    }
                },
            )
        }
    }
}
