package org.mega.entropy.ui.savedsessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import org.mega.entropy.storage.SavedSessionMetadata
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen

/** Spec section 24: "Saved Sessions" entry point from Welcome. */
@Composable
fun SavedSessionsScreen(
    onBack: () -> Unit,
    viewModel: SavedSessionsViewModel = viewModel(),
) {
    SecureScreen()
    val state by viewModel.uiState.collectAsState()
    var confirmingDeleteAll by remember { mutableStateOf(false) }

    MegaInfoScaffold(title = "Saved Sessions", onBack = onBack) {
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
                    SavedSessionCard(session = session, onDelete = { viewModel.deleteSession(session.id) })
                }

                if (!confirmingDeleteAll) {
                    MegaSecondaryButton(text = "Delete All MEGA Data", onClick = { confirmingDeleteAll = true })
                } else {
                    MegaCard {
                        Text(
                            "This permanently deletes every saved session and " +
                                "its encryption key. This cannot be undone.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        MegaSecondaryButton(
                            text = "Cancel",
                            modifier = Modifier.weight(1f),
                            onClick = { confirmingDeleteAll = false },
                        )
                        MegaSecondaryButton(
                            text = "Confirm Delete All",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                viewModel.deleteAllSessions()
                                confirmingDeleteAll = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedSessionCard(session: SavedSessionMetadata, onDelete: () -> Unit) {
    var confirmingDelete by remember { mutableStateOf(false) }
    val dateText = remember(session.createdAtEpochMillis) {
        DateFormat.getDateTimeInstance().format(Date(session.createdAtEpochMillis))
    }

    MegaCard {
        Text(dateText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "${session.rollsCount} / 100 rolls" + if (session.hasMnemonic) " · mnemonic saved" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!confirmingDelete) {
            MegaSecondaryButton(text = "Delete Session", onClick = { confirmingDelete = true })
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MegaSecondaryButton(
                    text = "Cancel",
                    modifier = Modifier.weight(1f),
                    onClick = { confirmingDelete = false },
                )
                MegaSecondaryButton(
                    text = "Confirm Delete",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onDelete()
                        confirmingDelete = false
                    },
                )
            }
        }
    }
}
