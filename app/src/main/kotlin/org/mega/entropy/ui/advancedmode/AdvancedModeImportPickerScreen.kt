package org.mega.entropy.ui.advancedmode

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import org.mega.entropy.storage.SavedSessionMetadata
import org.mega.entropy.storage.SessionRepository
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.MnemonicLength
import org.mega.entropycore.MnemonicResult
import org.mega.entropycore.deriveMnemonic

/**
 * Lets Advanced Mode import an existing saved session's words instead of
 * typing them by hand. Reached through the same PIN gate as Saved
 * Sessions (see MegaNavGraph's enterSavedSessionsGate) — this reads the
 * same encrypted vault, so it gets the same protection. Only the WORDS are
 * imported; any passphrase is deliberately not carried over (MEGA never
 * stores a session's actual passphrase, only a verification check), so the
 * user re-enters it in Advanced Mode's own passphrase field if needed —
 * the same "always re-type secrets, never silently carry them" pattern
 * BIP85 parent-passphrase entry already follows.
 */
@Composable
fun AdvancedModeImportPickerScreen(
    allowScreenshots: Boolean,
    onBack: () -> Unit,
    onImported: (words: List<String>, sourceLabel: String) -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)
    val context = LocalContext.current
    val repository = remember { SessionRepository(context) }
    val coroutineScope = rememberCoroutineScope()

    var sessions by remember { mutableStateOf<List<SavedSessionMetadata>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var importingSessionId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        sessions = try {
            repository.listSessions()
        } catch (e: Exception) {
            error = "Couldn't load saved sessions."
            emptyList()
        }
    }

    fun importSession(session: SavedSessionMetadata) {
        importingSessionId = session.id
        error = null
        coroutineScope.launch {
            try {
                val record = repository.loadSession(session.id)
                val words = record.mnemonicWords ?: run {
                    val length = MnemonicLength.entries.first { it.rollCount == record.diceRolls.size }
                    (deriveMnemonic(record.diceRolls, length) as? MnemonicResult.Success)?.words
                }
                if (words == null) {
                    error = "This session's saved rolls do not produce an accepted mnemonic."
                    importingSessionId = null
                } else {
                    onImported(words, session.label)
                }
            } catch (e: Exception) {
                error = "Couldn't open this session."
                importingSessionId = null
            }
        }
    }

    MegaInfoScaffold(title = "Import from Saved Session", onBack = onBack) {
        Text(
            "Select a saved session to import its seed words. Any passphrase " +
                "is not imported — re-enter it yourself afterward if this session used one.",
            style = MaterialTheme.typography.bodyMedium,
        )

        val currentError = error
        if (currentError != null) {
            Text(currentError, style = MaterialTheme.typography.bodyMedium, color = MegaError)
        }

        val currentSessions = sessions
        when {
            currentSessions == null -> CircularProgressIndicator()
            currentSessions.isEmpty() -> {
                Text("No saved sessions to import from.", style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                currentSessions.forEach { session ->
                    ImportableSessionCard(
                        session = session,
                        importing = importingSessionId == session.id,
                        onClick = { importSession(session) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportableSessionCard(
    session: SavedSessionMetadata,
    importing: Boolean,
    onClick: () -> Unit,
) {
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
                (if (session.hasMnemonic) " · mnemonic saved" else ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MegaPrimaryButton(
            text = if (importing) "Importing…" else "Import",
            enabled = !importing,
            onClick = onClick,
        )
    }
}
