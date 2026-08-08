package org.mega.entropy.ui.savedsessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date
import org.mega.entropy.storage.SavedSessionMetadata
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen

/** Spec section 24: "Saved Sessions" entry point from Welcome. */
@Composable
fun SavedSessionsScreen(
    onBack: () -> Unit,
    onChangePin: () -> Unit,
    onViewSession: (String) -> Unit,
    viewModel: SavedSessionsViewModel = viewModel(),
) {
    SecureScreen()
    val state by viewModel.uiState.collectAsState()
    var confirmingDeleteAll by remember { mutableStateOf(false) }

    MegaInfoScaffold(title = "Saved Sessions", onBack = onBack) {
        MegaCard(title = "MEGA PIN") {
            Text(
                if (state.isPinEnabled) "Enabled — required to view this screen" else "Not set yet",
                style = MaterialTheme.typography.bodyMedium,
            )
            MegaSecondaryButton(
                text = if (state.isPinEnabled) "Change PIN" else "Set Up PIN",
                onClick = onChangePin,
            )
        }

        when {
            state.isLoading -> {
                CircularProgressIndicator()
            }
            state.sessions.isEmpty() -> {
                Text(
                    "No saved sessions. Sessions are only saved when you " +
                        "explicitly choose to on the Save screen at the end " +
                        "of a dice-rolling flow.",
                    style = MaterialTheme.typography.bodyMedium,
                )
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

                MegaSecondaryButton(text = "Secure Delete All MEGA Data", onClick = { confirmingDeleteAll = true })
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
            "${session.rollsCount} rolls" + if (session.hasMnemonic) " · mnemonic saved" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier) {
            MegaSecondaryButton(text = "View", modifier = Modifier.weight(1f), onClick = onView)
            MegaSecondaryButton(text = "Label", modifier = Modifier.weight(1f), onClick = { renaming = true })
        }
        MegaSecondaryButton(text = "Secure Delete", onClick = { confirmingDelete = true })
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
                placeholder = { Text("e.g. \"Cold storage\"") },
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
