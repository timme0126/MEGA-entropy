package org.mega.entropy.ui.savedsessiondetail

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import org.mega.entropy.security.passphrase.PassphraseVerification
import org.mega.entropy.storage.SavedSessionRecord
import org.mega.entropy.storage.SessionRepository
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaCopyIconButton
import org.mega.entropy.ui.components.MegaLockIconButton
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.MnemonicLength
import org.mega.entropycore.MnemonicResult
import org.mega.entropycore.deriveMnemonic

private enum class PassphraseUiMode { NONE, SETTING, VERIFYING }

/**
 * Retrieves and displays a single saved session: its dice rolls always,
 * and its mnemonic (behind the same deliberate-reveal gate as
 * FinalMnemonicScreen) if it was saved. This screen was missing entirely
 * in v1 — saved sessions could be listed and deleted but never actually
 * opened.
 */
@Composable
fun SavedSessionDetailScreen(
    sessionId: String,
    allowScreenshots: Boolean,
    allowSeedCopy: Boolean,
    onBack: () -> Unit,
    onBip85: (List<String>, String) -> Unit,
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
    // Per-screen only, not persisted — default unlocked preserves the prior
    // always-editable behavior; locking is a deliberate, transient choice
    // the user can make while reviewing this session.
    var diceRollsLocked by remember { mutableStateOf(false) }

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

    var passphraseMode by remember { mutableStateOf(PassphraseUiMode.NONE) }
    var passphraseField by remember { mutableStateOf("") }
    var confirmPassphraseField by remember { mutableStateOf("") }
    var showPassphraseField by remember { mutableStateOf(false) }
    var verifyResult by remember { mutableStateOf<PassphraseVerification?>(null) }
    var seedRevealed by remember { mutableStateOf(false) }
    var passphraseError by remember { mutableStateOf<String?>(null) }
    var confirmingClearCheck by remember { mutableStateOf(false) }

    fun resetPassphraseUi() {
        passphraseMode = PassphraseUiMode.NONE
        passphraseField = ""
        confirmPassphraseField = ""
        showPassphraseField = false
        verifyResult = null
        seedRevealed = false
        passphraseError = null
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

                MegaCard(
                    title = "Dice rolls (${currentRecord.diceRolls.size} / ${currentRecord.diceRolls.size})",
                    trailingAction = {
                        MegaLockIconButton(
                            locked = diceRollsLocked,
                            onToggle = {
                                val newLocked = !diceRollsLocked
                                diceRollsLocked = newLocked
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
                        leadingAction = if (allowSeedCopy) {
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
                    MegaSecondaryButton(
                        text = if (currentRecord.metadata.hasPassphraseCheck) {
                            "Calculate BIP85 Child (No Passphrase)"
                        } else {
                            "Calculate BIP85 Child"
                        },
                        onClick = { onBip85(wordsToDisplay, "") },
                    )
                }
                val currentMnemonicActionError = mnemonicActionError
                if (currentMnemonicActionError != null) {
                    Text(currentMnemonicActionError, style = MaterialTheme.typography.bodySmall, color = MegaError)
                }

                val visualTransformation = if (showPassphraseField) VisualTransformation.None else PasswordVisualTransformation()
                val mismatch = confirmPassphraseField.isNotEmpty() && confirmPassphraseField != passphraseField

                when (passphraseMode) {
                    PassphraseUiMode.NONE -> {
                        if (currentRecord.metadata.hasPassphraseCheck) {
                            MegaCard(title = "Passphrase Check") {
                                Text(
                                    "This session has a passphrase check saved. You " +
                                        "can verify a re-entered passphrase matches — " +
                                        "MEGA will never display the passphrase itself.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            MegaPrimaryButton(
                                text = "Verify Passphrase",
                                onClick = { resetPassphraseUi(); passphraseMode = PassphraseUiMode.VERIFYING },
                            )
                            MegaSecondaryButton(
                                text = "Clear Passphrase Check",
                                onClick = { confirmingClearCheck = true },
                            )
                        } else {
                            MegaCard(title = "Passphrase Check") {
                                Text(
                                    "No passphrase check is saved for this session. " +
                                        "You can set one now to verify a re-entered " +
                                        "passphrase later — MEGA will never store or " +
                                        "display the passphrase itself.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            MegaSecondaryButton(
                                text = "Set a Passphrase Check",
                                onClick = { resetPassphraseUi(); passphraseMode = PassphraseUiMode.SETTING },
                            )
                        }
                    }
                    PassphraseUiMode.SETTING -> {
                        MegaCard(title = "Set a Passphrase Check") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = passphraseField,
                                    onValueChange = { passphraseField = it },
                                    label = { Text("Passphrase") },
                                    singleLine = true,
                                    visualTransformation = visualTransformation,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = confirmPassphraseField,
                                    onValueChange = { confirmPassphraseField = it },
                                    label = { Text("Confirm Passphrase") },
                                    singleLine = true,
                                    isError = mismatch,
                                    visualTransformation = visualTransformation,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (mismatch) {
                                    Text("Passphrases don't match.", style = MaterialTheme.typography.bodySmall, color = MegaError)
                                }
                                Text(
                                    text = if (showPassphraseField) "Hide passphrase" else "Show passphrase",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { showPassphraseField = !showPassphraseField },
                                )
                                val currentError = passphraseError
                                if (currentError != null) {
                                    Text(currentError, style = MaterialTheme.typography.bodySmall, color = MegaError)
                                }
                            }
                        }
                        MegaPrimaryButton(
                            text = "Save Passphrase Check",
                            enabled = passphraseField.isNotEmpty() && !mismatch,
                            onClick = {
                                coroutineScope.launch {
                                    try {
                                        repository.setPassphraseCheck(sessionId, passphraseField)
                                        reload()
                                        resetPassphraseUi()
                                    } catch (e: Exception) {
                                        passphraseError = "Couldn't save the passphrase check. Please try again."
                                    }
                                }
                            },
                        )
                        MegaSecondaryButton(text = "Cancel", onClick = { resetPassphraseUi() })
                    }
                    PassphraseUiMode.VERIFYING -> {
                        MegaCard(title = "Verify Passphrase") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = passphraseField,
                                    onValueChange = { passphraseField = it; verifyResult = null; seedRevealed = false },
                                    label = { Text("Passphrase") },
                                    singleLine = true,
                                    visualTransformation = visualTransformation,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = if (showPassphraseField) "Hide passphrase" else "Show passphrase",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { showPassphraseField = !showPassphraseField },
                                )
                                val result = verifyResult
                                if (result != null) {
                                    Text(
                                        text = if (result.matches) "✓ Matches" else "✗ Does not match",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (result.matches) MaterialTheme.colorScheme.primary else MegaError,
                                    )
                                }
                                val currentError = passphraseError
                                if (currentError != null) {
                                    Text(currentError, style = MaterialTheme.typography.bodySmall, color = MegaError)
                                }
                            }
                        }
                        MegaPrimaryButton(
                            text = "Check",
                            enabled = passphraseField.isNotEmpty(),
                            onClick = {
                                coroutineScope.launch {
                                    try {
                                        verifyResult = repository.verifyPassphrase(sessionId, passphraseField)
                                        seedRevealed = false
                                        passphraseError = null
                                    } catch (e: Exception) {
                                        passphraseError = "Couldn't check the passphrase. Please try again."
                                    }
                                }
                            },
                        )

                        // Only offered once the entered passphrase is confirmed
                        // to match the one originally used — never for an
                        // unconfirmed guess. This is the same entropy result
                        // (the derived BIP39 seed) PassphraseScreen shows right
                        // after deriving a mnemonic, now reachable again from a
                        // saved session once you've proven you know its
                        // passphrase.
                        if (verifyResult?.matches == true) {
                            MegaSecondaryButton(
                                text = "Calculate BIP85 Child (Verified Passphrase)",
                                onClick = {
                                    val words = currentRecord.mnemonicWords ?: derivedMnemonicWords ?: run {
                                        val length = MnemonicLength.entries.first { it.rollCount == currentRecord.diceRolls.size }
                                        (deriveMnemonic(currentRecord.diceRolls, length) as? MnemonicResult.Success)?.words
                                    }
                                    if (words == null) {
                                        mnemonicActionError = "These saved rolls do not produce an accepted mnemonic."
                                    } else {
                                        mnemonicActionError = null
                                        onBip85(words, passphraseField)
                                    }
                                },
                            )
                            if (!seedRevealed) {
                                MegaSecondaryButton(text = "Reveal BIP39 Seed", onClick = { seedRevealed = true })
                            } else {
                                MegaCard(title = "BIP39 Seed (512-bit, hex)") {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        verifyResult?.seed?.hex?.chunked(32)?.forEach { line ->
                                            MegaMonoText(line)
                                        }
                                    }
                                }
                            }
                        }

                        MegaSecondaryButton(text = "Done", onClick = { resetPassphraseUi() })
                    }
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
                            resetPassphraseUi()
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

    if (confirmingClearCheck) {
        AlertDialog(
            onDismissRequest = { confirmingClearCheck = false },
            title = { Text("Are you sure?") },
            text = { Text("This removes the saved passphrase check. You'll need to set a new one to verify a passphrase for this session again.") },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        repository.clearPassphraseCheck(sessionId)
                        reload()
                        resetPassphraseUi()
                        confirmingClearCheck = false
                    }
                }) {
                    Text("Clear Passphrase Check", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClearCheck = false }) { Text("Cancel") }
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
                Text(
                    "Saving an edit also clears any saved passphrase check for this session.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
