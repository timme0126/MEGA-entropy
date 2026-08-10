package org.mega.entropy.ui.savedsessiondetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import org.mega.entropy.storage.SavedSessionRecord
import org.mega.entropy.storage.SessionRepository
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaCopyIconButton
import org.mega.entropy.ui.components.MegaLockIconButton
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.MnemonicLength
import org.mega.entropycore.MnemonicResult
import org.mega.entropycore.deriveMnemonic

/**
 * Retrieves and displays a single saved session: its dice rolls (if any —
 * an Advanced Mode session saved via "Save as Session" has none, only
 * words), and its mnemonic (behind the same deliberate-reveal gate as
 * FinalMnemonicScreen). This screen was missing entirely in v1 — saved
 * sessions could be listed and deleted but never actually opened.
 *
 * Passphrase entry/verification, BIP85, and wallet-key derivation are
 * deliberately not offered here — those are Advanced Mode features now
 * (see AdvancedModeHubScreen), reachable for a saved session's words via
 * "Import from Saved Session" rather than duplicated in this screen.
 */
@Composable
fun SavedSessionDetailScreen(
    sessionId: String,
    allowScreenshots: Boolean,
    allowSeedCopy: Boolean,
    diceRollsLockedDefault: Boolean,
    onDiceRollsLockedDefaultChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)
    val context = LocalContext.current
    val repository = remember { SessionRepository(context) }
    val coroutineScope = rememberCoroutineScope()

    var record by remember { mutableStateOf<SavedSessionRecord?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var mnemonicRevealed by remember { mutableStateOf(false) }
    var derivedMnemonicWords by remember { mutableStateOf<List<String>?>(null) }
    var mnemonicActionError by remember { mutableStateOf<String?>(null) }
    var editingBatchIndex by remember { mutableStateOf<Int?>(null) }
    var editRollTexts by remember { mutableStateOf(List(5) { "" }) }
    var editRollError by remember { mutableStateOf<String?>(null) }
    // Starts from the remembered default (see SavedSessionSecuritySettings)
    // but is otherwise this screen's own state — toggling it here updates
    // that default for the next saved session opened, without retroactively
    // changing any other currently-open screen.
    var diceRollsLocked by remember { mutableStateOf(diceRollsLockedDefault) }

    suspend fun reload() {
        record = try {
            repository.loadSession(sessionId)
        } catch (e: Exception) {
            loadError = "Couldn't open this session — it may be corrupted, " +
                "or its data doesn't match what was recorded when it was saved."
            null
        }
    }

    LaunchedEffect(sessionId) { reload() }

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
                if (currentRecord.metadata.childSeedInfo.isNotBlank()) {
                    Text(
                        currentRecord.metadata.childSeedInfo,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Advanced-Mode-entered sessions have no dice behind them at
                // all (the words were typed in directly) — this card only
                // applies to sessions that actually originated from a dice
                // roll sequence.
                if (currentRecord.diceRolls.isNotEmpty()) {
                    MegaCard(
                        title = "Dice rolls (${currentRecord.diceRolls.size} / ${currentRecord.diceRolls.size})",
                        trailingAction = {
                            MegaLockIconButton(
                                locked = diceRollsLocked,
                                onToggle = {
                                    val newLocked = !diceRollsLocked
                                    diceRollsLocked = newLocked
                                    onDiceRollsLockedDefaultChanged(newLocked)
                                    if (newLocked) {
                                        editingBatchIndex = null
                                        editRollError = null
                                    }
                                },
                            )
                        },
                    ) {
                        if (!diceRollsLocked) {
                            Text(
                                "Editing a batch changes the derived seed for this session.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MegaError,
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            currentRecord.diceRolls.chunked(5).forEachIndexed { index, batch ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    MegaMonoText(
                                        "Batch ${(index + 1).toString().padStart(2, '0')}:  ${batch.joinToString(" ")}",
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (!diceRollsLocked) {
                                        TextButton(onClick = {
                                            editingBatchIndex = index
                                            editRollTexts = batch.map { it.toString() }
                                            editRollError = null
                                        }) {
                                            Text("Edit")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                val wordsToDisplay = currentRecord.mnemonicWords ?: derivedMnemonicWords
                if (wordsToDisplay == null) {
                    MegaCard(title = "Seed Words") {
                        Text(
                            "This session was saved as dice-only. You can calculate the derived seed words from the saved dice rolls without saving the words yet.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    MegaPrimaryButton(
                        text = "Calculate Seed Words",
                        onClick = {
                            val length = MnemonicLength.entries.first { it.rollCount == currentRecord.diceRolls.size }
                            val result = deriveMnemonic(currentRecord.diceRolls, length)
                            val success = result as? MnemonicResult.Success
                            if (success == null) {
                                mnemonicActionError = "These saved rolls do not produce an accepted mnemonic."
                            } else {
                                derivedMnemonicWords = success.words
                                mnemonicRevealed = true
                                mnemonicActionError = null
                            }
                        },
                    )
                } else if (!mnemonicRevealed) {
                    MegaCard {
                        Text(
                            "Anyone who sees these seed words may be able to take funds from any wallet initialized with them.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MegaError,
                        )
                    }
                    MegaPrimaryButton(text = "Reveal Seed Words", onClick = { mnemonicRevealed = true })
                } else {
                    MegaCard(
                        title = "Seed Words",
                        trailingAction = if (allowSeedCopy) {
                            {
                                MegaCopyIconButton(
                                    contentDescription = "Copy seed words",
                                    getTextToCopy = { wordsToDisplay.joinToString(" ") },
                                )
                            }
                        } else {
                            null
                        },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            wordsToDisplay.forEachIndexed { index, word ->
                                MegaMonoText("${(index + 1).toString().padStart(2, '0')}. $word")
                            }
                        }
                    }
                    if (currentRecord.mnemonicWords == null) {
                        MegaPrimaryButton(
                            text = "Save Seed Words to Session",
                            onClick = {
                                coroutineScope.launch {
                                    try {
                                        repository.saveMnemonicWords(sessionId)
                                        reload()
                                        mnemonicRevealed = true
                                        derivedMnemonicWords = null
                                        mnemonicActionError = null
                                    } catch (e: Exception) {
                                        mnemonicActionError = "Couldn't save the seed words. Please try again."
                                    }
                                }
                            },
                        )
                    }
                }
                val currentMnemonicActionError = mnemonicActionError
                if (currentMnemonicActionError != null) {
                    Text(currentMnemonicActionError, style = MaterialTheme.typography.bodySmall, color = MegaError)
                }
            }
        }
    }

    val batchIndex = editingBatchIndex
    val currentRecordForEdit = record
    if (batchIndex != null && currentRecordForEdit != null) {
        EditDiceBatchDialog(
            batchNumber = batchIndex + 1,
            rollTexts = editRollTexts,
            errorMessage = editRollError,
            onRollChanged = { index, value ->
                editRollTexts = editRollTexts.toMutableList().also { values ->
                    values[index] = value.filter { it in '1'..'6' }.take(1)
                }
                editRollError = null
            },
            onConfirm = {
                val editedBatch = editRollTexts.mapNotNull { it.toIntOrNull() }
                if (editedBatch.size != 5 || editedBatch.any { it !in 1..6 }) {
                    editRollError = "Enter five die rolls, each 1 through 6."
                } else {
                    val updatedRolls = currentRecordForEdit.diceRolls.toMutableList()
                    editedBatch.forEachIndexed { offset, roll ->
                        updatedRolls[batchIndex * 5 + offset] = roll
                    }
                    coroutineScope.launch {
                        try {
                            repository.updateDiceRolls(sessionId, updatedRolls)
                            reload()
                            mnemonicRevealed = false
                            derivedMnemonicWords = null
                            mnemonicActionError = null
                            editingBatchIndex = null
                            editRollError = null
                        } catch (e: IllegalArgumentException) {
                            editRollError = "Those edited rolls do not produce an accepted mnemonic. Try a different correction."
                        } catch (e: Exception) {
                            editRollError = "Couldn't save the edited rolls. Please try again."
                        }
                    }
                }
            },
            onDismiss = {
                editingBatchIndex = null
                editRollError = null
            },
        )
    }
}


@Composable
private fun EditDiceBatchDialog(
    batchNumber: Int,
    rollTexts: List<String>,
    errorMessage: String?,
    onRollChanged: (Int, String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Batch ${batchNumber.toString().padStart(2, '0')}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Warning: changing saved dice rolls changes the derived seed. If the current seed is not properly backed up and has funds on it, those funds could be lost forever without the correct backup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MegaError,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    repeat(5) { index ->
                        OutlinedTextField(
                            value = rollTexts.getOrElse(index) { "" },
                            onValueChange = { onRollChanged(index, it) },
                            label = { Text((index + 1).toString()) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (errorMessage != null) {
                    Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = MegaError)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Save Changes") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
