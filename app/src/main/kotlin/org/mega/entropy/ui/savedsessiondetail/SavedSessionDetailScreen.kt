package org.mega.entropy.ui.savedsessiondetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
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
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError

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
    onBack: () -> Unit,
    onBip85: (List<String>) -> Unit,
) {
    SecureScreen()
    val context = LocalContext.current
    val repository = remember { SessionRepository(context) }
    val coroutineScope = rememberCoroutineScope()

    var record by remember { mutableStateOf<SavedSessionRecord?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var mnemonicRevealed by remember { mutableStateOf(false) }

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
                        MegaSecondaryButton(
                            text = "Calculate BIP85 Child",
                            onClick = { onBip85(currentRecord.mnemonicWords) },
                        )
                    }
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
