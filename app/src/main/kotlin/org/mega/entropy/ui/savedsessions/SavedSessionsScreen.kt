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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import org.mega.entropy.ui.components.MegaLabelSessionDialog
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
    allowScreenshots: Boolean,
    onAllowScreenshotsChanged: (Boolean) -> Unit,
    allowSeedCopy: Boolean,
    onAllowSeedCopyChanged: (Boolean) -> Unit,
    allowPrivateKeyExport: Boolean,
    onAllowPrivateKeyExportChanged: (Boolean) -> Unit,
    advancedModeEnabled: Boolean,
    onAdvancedModeChanged: (Boolean) -> Unit,
    viewModel: SavedSessionsViewModel = viewModel(),
) {
    SecureScreen(enabled = !allowScreenshots)
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
            allowScreenshots = allowScreenshots,
            onAllowScreenshotsChanged = onAllowScreenshotsChanged,
            allowSeedCopy = allowSeedCopy,
            onAllowSeedCopyChanged = onAllowSeedCopyChanged,
            allowPrivateKeyExport = allowPrivateKeyExport,
            onAllowPrivateKeyExportChanged = onAllowPrivateKeyExportChanged,
            advancedModeEnabled = advancedModeEnabled,
            onAdvancedModeChanged = onAdvancedModeChanged,
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
    allowScreenshots: Boolean,
    onAllowScreenshotsChanged: (Boolean) -> Unit,
    allowSeedCopy: Boolean,
    onAllowSeedCopyChanged: (Boolean) -> Unit,
    allowPrivateKeyExport: Boolean,
    onAllowPrivateKeyExportChanged: (Boolean) -> Unit,
    advancedModeEnabled: Boolean,
    onAdvancedModeChanged: (Boolean) -> Unit,
    onChangePin: () -> Unit,
    onChangeDuressPin: () -> Unit,
    onClearDuressPin: () -> Unit,
    onDeleteAll: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var confirmingAdvancedMode by remember { mutableStateOf(false) }

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


        MegaCard(title = "Sensitive Display") {
            SettingSwitchRow(
                label = "Allow screenshots",
                description = "Permit Android screenshots and recent-app previews on MEGA sensitive screens.",
                checked = allowScreenshots,
                onCheckedChange = onAllowScreenshotsChanged,
            )
            SettingSwitchRow(
                label = "Allow seed word copy",
                description = "Show copy buttons for seed words and BIP85 child words after reveal.",
                checked = allowSeedCopy,
                onCheckedChange = onAllowSeedCopyChanged,
            )
        }

        MegaCard(title = "Duress PIN") {
            Text(
                "Entering the duress PIN at a MEGA PIN prompt securely deletes all saved MEGA session data instead of unlocking.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MegaPrimaryButton(
                text = if (duressPinEnabled) "Change Duress PIN" else "Set Duress PIN",
                onClick = onChangeDuressPin,
            )
            if (duressPinEnabled) {
                TextButton(onClick = onClearDuressPin, modifier = Modifier.fillMaxWidth()) {
                    Text("Clear Duress PIN", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        MegaCard(title = "Advanced Mode") {
            Text(
                "Manually enter an existing seed phrase to derive BIP85 children or wallet account keys. Off by default — MEGA's core purpose is generating a phrase from dice, not typing one in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingSwitchRow(
                label = "Advanced Mode",
                description = "Adds a manual seed entry and wallet key derivation flow, reachable from the main screen.",
                checked = advancedModeEnabled,
                onCheckedChange = { enable ->
                    if (enable) {
                        confirmingAdvancedMode = true
                    } else {
                        onAdvancedModeChanged(false)
                    }
                },
            )
        }

        MegaCard(title = "Private Key Export") {
            Text(
                "Adds a button in Advanced Mode to generate a WIF private key for a " +
                    "derived address. Unlike an xpub, a private key can spend whatever " +
                    "funds are sent there — anyone who sees it can take them. Off by " +
                    "default; each generation still requires its own confirmation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            SettingSwitchRow(
                label = "Allow private key export",
                description = "Show the Generate Private Key (WIF) button in Advanced Mode.",
                checked = allowPrivateKeyExport,
                onCheckedChange = onAllowPrivateKeyExportChanged,
            )
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

    if (confirmingAdvancedMode) {
        AlertDialog(
            onDismissRequest = { confirmingAdvancedMode = false },
            title = { Text("Enable Advanced Mode?") },
            text = {
                Text(
                    "Advanced Mode is for advanced users. Entering an existing seed phrase " +
                        "or passphrase on any connected Android device can expose the funds it " +
                        "controls if the device is compromised — MEGA cannot guarantee safety on " +
                        "an internet-connected device. Prefer an offline GrapheneOS phone for " +
                        "sensitive seed workflows.\n\n" +
                        "A mistake in the seed words, passphrase, derivation path, script type, " +
                        "or account index produces a completely different wallet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAdvancedModeChanged(true)
                    confirmingAdvancedMode = false
                }) {
                    Text("I Understand, Enable", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingAdvancedMode = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.foundation.layout.Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
            (if (session.rollsCount > 0) "${session.rollsCount} rolls" else "Manually entered seed") +
                (if (session.hasMnemonic) " · mnemonic saved" else "") +
                (if (session.hasPassphraseCheck) " · passphrase check saved" else ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (session.childSeedInfo.isNotBlank()) {
            Text(
                session.childSeedInfo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier) {
            MegaNeutralButton(text = "View", modifier = Modifier.weight(1f), onClick = onView)
            MegaNeutralButton(text = "Label", modifier = Modifier.weight(1f), onClick = { renaming = true })
        }
        MegaPrimaryButton(text = "Secure Delete", onClick = { confirmingDelete = true })
    }

    if (renaming) {
        MegaLabelSessionDialog(
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
