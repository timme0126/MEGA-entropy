package org.mega.entropy.ui.savedsessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date
import org.mega.entropy.security.settings.SavedSessionLockTimeoutOption
import org.mega.entropy.storage.SavedSessionMetadata
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaNeutralButton
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen

/** Spec section 24: "Saved Sessions" entry point from Welcome. */
@Composable
fun SavedSessionsScreen(
    onBack: () -> Unit,
    onChangePin: () -> Unit,
    onViewSession: (String) -> Unit,
    onNewDiceSession: () -> Unit,
    onChangeDuressPin: () -> Unit,
    selectedLockTimeoutMillis: Long,
    lockTimeoutOptions: List<SavedSessionLockTimeoutOption>,
    onLockTimeoutSelected: (Long) -> Unit,
    randomizePinKeypad: Boolean,
    onRandomizePinKeypadChanged: (Boolean) -> Unit,
    viewModel: SavedSessionsViewModel = viewModel(),
) {
    SecureScreen()
    val state by viewModel.uiState.collectAsState()
    var confirmingDeleteAll by remember { mutableStateOf(false) }
    var showingSettings by remember { mutableStateOf(false) }

    if (showingSettings) {
        SavedSessionSettingsScreen(
            pinButtonText = if (state.isPinEnabled) "Change PIN" else "Set Up PIN",
            duressPinEnabled = state.isDuressPinEnabled,
            selectedLockTimeoutMillis = selectedLockTimeoutMillis,
            lockTimeoutOptions = lockTimeoutOptions,
            onLockTimeoutSelected = onLockTimeoutSelected,
            randomizePinKeypad = randomizePinKeypad,
            onRandomizePinKeypadChanged = onRandomizePinKeypadChanged,
            onChangePin = {
                showingSettings = false
                onChangePin()
            },
            onChangeDuressPin = {
                showingSettings = false
                onChangeDuressPin()
            },
            onClearDuressPin = { viewModel.clearDuressPin() },
            onDeleteAll = {
                showingSettings = false
                confirmingDeleteAll = true
            },
            onBack = { showingSettings = false },
        )
    } else {
        MegaInfoScaffold(
            title = "Saved Sessions",
            onBack = onBack,
            actions = {
                IconButton(onClick = { showingSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Saved session settings")
                }
            },
        ) {
            if (!state.isPinEnabled) {
                MegaCard(title = "MEGA PIN") {
                    Text(
                        "Not set yet",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Set a PIN in Settings before saving or retrieving MEGA data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when {
                state.isLoading -> {
                    CircularProgressIndicator()
                }
                state.sessions.isEmpty() -> {
                    Text(
                        "No saved sessions. Sessions are only saved when you " +
                            "explicitly choose to save on the Save screen at the end " +
                            "of a dice-rolling flow.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    MegaPrimaryButton(text = "New Dice Session", onClick = onNewDiceSession)
                }
                else -> {
                    state.sessions.forEach { session ->
                        SavedSessionCard(
                            session = session,
                            onView = { onViewSession(session.id) },
                            onRename = { newLabel -> viewModel.renameSession(session.id, newLabel) },
                            onDelete = { viewModel.deleteSession(session.id) },
                        )
                    }
                }
            }
        }
    }

    if (confirmingDeleteAll) {
        ConfirmDeleteDialog(
            text = "This permanently deletes every saved session and its encryption key. This cannot be undone.",
            confirmText = "Secure Delete All",
            onConfirm = {
                viewModel.deleteAllSessions()
                confirmingDeleteAll = false
            },
            onDismiss = { confirmingDeleteAll = false },
        )
    }
}

/** Full-screen settings for saved sessions, reached via the cog on
 * [SavedSessionsScreen]. Grouped into auto-lock timing, PIN management, and
 * a visually separated destructive section, instead of one flat dialog. */
@Composable
private fun SavedSessionSettingsScreen(
    pinButtonText: String,
    duressPinEnabled: Boolean,
    selectedLockTimeoutMillis: Long,
    lockTimeoutOptions: List<SavedSessionLockTimeoutOption>,
    onLockTimeoutSelected: (Long) -> Unit,
    randomizePinKeypad: Boolean,
    onRandomizePinKeypadChanged: (Boolean) -> Unit,
    onChangePin: () -> Unit,
    onChangeDuressPin: () -> Unit,
    onClearDuressPin: () -> Unit,
    onDeleteAll: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    MegaInfoScaffold(title = "Saved Session Settings", onBack = onBack) {
        MegaCard(title = "Auto-lock") {
            Text(
                "Require PIN again after MEGA leaves saved sessions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            lockTimeoutOptions.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLockTimeoutSelected(option.millis) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(
                        selected = option.millis == selectedLockTimeoutMillis,
                        onClick = { onLockTimeoutSelected(option.millis) },
                    )
                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        MegaCard(title = "PIN") {
            Text(
                "Required to view or delete saved sessions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MegaPrimaryButton(text = pinButtonText, onClick = onChangePin)

            Text("PIN pad layout", style = MaterialTheme.typography.titleSmall)
            KeypadLayoutOption(
                label = "Standard",
                description = "Digits stay in the familiar phone keypad order.",
                selected = !randomizePinKeypad,
                onClick = { onRandomizePinKeypadChanged(false) },
            )
            KeypadLayoutOption(
                label = "Randomized",
                description = "Digits shuffle for PIN entry to reduce shoulder-surfing risk.",
                selected = randomizePinKeypad,
                onClick = { onRandomizePinKeypadChanged(true) },
            )
        }

        MegaCard(title = "Duress PIN") {
            Text(
                "Entering the duress PIN at a MEGA PIN prompt securely deletes all saved MEGA session data instead of unlocking.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MegaSecondaryButton(
                text = if (duressPinEnabled) "Change Duress PIN" else "Set Duress PIN",
                onClick = onChangeDuressPin,
            )
            if (duressPinEnabled) {
                TextButton(onClick = onClearDuressPin, modifier = Modifier.fillMaxWidth()) {
                    Text("Clear Duress PIN", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        MegaCard {
            Text(
                "Danger Zone",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "Permanently erases every saved session and its encryption key. This cannot be undone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MegaSecondaryButton(text = "Secure Delete All MEGA Data", onClick = onDeleteAll)
        }
    }
}

@Composable
private fun KeypadLayoutOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SavedSessionCard(
    session: SavedSessionMetadata,
    onView: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val dateText = remember(session.createdAtEpochMillis) {
        DateFormat.getDateTimeInstance().format(Date(session.createdAtEpochMillis))
    }

    MegaCard {
        if (session.label.isNotBlank()) {
            Text(session.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(dateText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(dateText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "${session.rollsCount} rolls" +
                (if (session.hasMnemonic) " · mnemonic saved" else "") +
                (if (session.hasPassphraseCheck) " · passphrase check saved" else ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier) {
            MegaNeutralButton(text = "View", modifier = Modifier.weight(1f), onClick = onView)
            MegaNeutralButton(text = "Label", modifier = Modifier.weight(1f), onClick = { renaming = true })
        }
        MegaPrimaryButton(text = "Secure Delete", onClick = { confirmingDelete = true })
    }

    if (renaming) {
        RenameDialog(
            initialLabel = session.label,
            onConfirm = { newLabel ->
                onRename(newLabel)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }

    if (confirmingDelete) {
        ConfirmDeleteDialog(
            text = "This permanently deletes this session and its encryption key. This cannot be undone.",
            confirmText = "Secure Delete",
            onConfirm = {
                onDelete()
                confirmingDelete = false
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

@Composable
private fun ConfirmDeleteDialog(
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Are you sure?") },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RenameDialog(
    initialLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Label This Session") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("e.g. Cold storage") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
