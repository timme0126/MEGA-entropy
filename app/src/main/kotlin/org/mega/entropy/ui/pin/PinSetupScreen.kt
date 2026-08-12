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
    title: String = "Choose a MEGA PIN",
    confirmTitle: String = "Confirm Your PIN",
    subtitle: String = "5 to 8 digits",
    onSavePin: (suspend (String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val pinManager = remember { PinManager(context.filesDir) }
    val coroutineScope = rememberCoroutineScope()

    var step by remember { mutableStateOf(SetupStep.ENTER) }
    var firstEntry by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    when (step) {
        SetupStep.ENTER -> {
            PinEntryScreen(
                title = title,
                subtitle = subtitle,
                errorMessage = errorMessage,
                scrambled = false,
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
                title = confirmTitle,
                subtitle = "Enter the same PIN again",
                errorMessage = errorMessage,
                scrambled = false,
                onCancel = onCancel,
                onSubmit = { pin ->
                    if (pin == firstEntry) {
                        coroutineScope.launch {
                            try {
                                if (onSavePin != null) {
                                    onSavePin(pin)
                                } else {
                                    pinManager.setPin(pin)
                                }
                                onPinSet()
                            } catch (e: IllegalArgumentException) {
                                errorMessage = e.message ?: "PIN could not be saved."
                                firstEntry = null
                                step = SetupStep.ENTER
                            } catch (e: Exception) {
                                errorMessage = "PIN could not be saved."
                                firstEntry = null
                                step = SetupStep.ENTER
                            }
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
