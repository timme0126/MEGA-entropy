package org.mega.entropy.ui.savedsessiondetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import org.mega.entropy.storage.SavedSessionRecord
import org.mega.entropy.storage.SessionRepository
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError

/**
 * Retrieves and displays a single saved session: its dice rolls always,
 * and its mnemonic (behind the same deliberate-reveal gate as
 * FinalMnemonicScreen) if it was saved. This is the screen that was
 * missing entirely in v1 — saved sessions could be listed and deleted but
 * never actually opened.
 */
@Composable
fun SavedSessionDetailScreen(
    sessionId: String,
    onBack: () -> Unit,
) {
    SecureScreen()
    val context = LocalContext.current
    val repository = remember { SessionRepository(context) }

    var record by remember { mutableStateOf<SavedSessionRecord?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var mnemonicRevealed by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        record = try {
            repository.loadSession(sessionId)
        } catch (e: Exception) {
            loadError = "Couldn't open this session — it may be corrupted, " +
                "or its data doesn't match what was recorded when it was saved."
            null
        }
    }

    MegaInfoScaffold(title = "Saved Session", onBack = onBack) {
        val currentError = loadError
        val currentRecord = record
        when {
            currentError != null -> {
                Text(currentError, style = MaterialTheme.typography.bodyMedium, color = MegaError)
            }
            currentRecord == null -> {
                CircularProgressIndicator()
            }
            else -> {
                val dateText = remember(currentRecord.metadata.createdAtEpochMillis) {
                    DateFormat.getDateTimeInstance().format(Date(currentRecord.metadata.createdAtEpochMillis))
                }
                Text(dateText, style = MaterialTheme.typography.titleMedium)

                MegaCard(title = "Dice rolls (${currentRecord.diceRolls.size} / 100)") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        currentRecord.diceRolls.chunked(5).forEachIndexed { index, batch ->
                            MegaMonoText("Batch ${(index + 1).toString().padStart(2, '0')}:  ${batch.joinToString(" ")}")
                        }
                    }
                }

                if (currentRecord.mnemonicWords != null) {
                    if (!mnemonicRevealed) {
                        MegaCard {
                            Text(
                                "This session also has its derived mnemonic saved. " +
                                    "Anyone who sees it may be able to take funds from " +
                                    "any wallet initialized with it.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MegaError,
                            )
                        }
                        MegaPrimaryButton(text = "Reveal Mnemonic", onClick = { mnemonicRevealed = true })
                    } else {
                        MegaCard(title = "Mnemonic") {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                currentRecord.mnemonicWords.forEachIndexed { index, word ->
                                    MegaMonoText("${(index + 1).toString().padStart(2, '0')}. $word")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
