package org.mega.entropy.ui.backup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaSecondaryButton

private const val MIN_BACKUP_PASSPHRASE_LENGTH = 8

/**
 * Settings section for exporting/importing MEGA's portable encrypted
 * backup file — see BackupRepository's doc comment for the full design.
 * Self-contained: obtains its own BackupViewModel and Storage Access
 * Framework launchers, so it drops into SavedSessionSettingsScreen with no
 * wiring needed from MegaNavGraph, the same way a plain MegaCard would.
 */
@Composable
fun BackupCard() {
    val context = LocalContext.current
    val viewModel: BackupViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    var showingExportDialog by remember { mutableStateOf(false) }
    // Holds the export ciphertext between "Export" completing and the
    // "where to save" picker needing it. This one is safe as plain
    // remember state (unlike the import side below) because it's set
    // *before* the picker launches, not inside the picker's async result
    // callback, so it survives being read even if the composable is torn
    // down and recomposed by an auto-lock re-entry in between.
    var pendingExportBytes by remember { mutableStateOf<ByteArray?>(null) }
    // Pending-import bytes and the PIN-required dialog live in
    // BackupViewModel, not here — see BackupUiState's doc comment for why.

    val saveLauncher = rememberLauncherForActivityResult(CreateLocalBackupDocument) { destination ->
        val bytes = pendingExportBytes
        pendingExportBytes = null
        if (destination != null && bytes != null) {
            val written = writeBackupBytes(context, destination, bytes)
            Toast.makeText(
                context,
                if (written) "Backup saved." else "Could not save the backup to that location.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val openLauncher = rememberLauncherForActivityResult(OpenLocalBackupDocument) { source ->
        if (source != null) {
            val bytes = readBackupBytes(context, source)
            if (bytes == null) {
                Toast.makeText(context, "Could not read that file.", Toast.LENGTH_LONG).show()
            } else {
                viewModel.onBackupFilePicked(bytes)
            }
        }
    }

    MegaCard(title = "Backup") {
        Text(
            "Export every saved session and multisig vault into one file, " +
                "encrypted with a passphrase of your choice, to move to another " +
                "MEGA device and recover from. Encrypted with AES-256-GCM; the " +
                "key is derived from your passphrase via scrypt (RFC 7914), a " +
                "memory-hard key derivation function chosen specifically to " +
                "resist offline brute-forcing of a stolen backup file. MEGA " +
                "never stores this passphrase — if you forget it, this backup " +
                "cannot be recovered.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MegaPrimaryButton(text = "Export Encrypted Backup", onClick = { showingExportDialog = true })
        MegaSecondaryButton(
            text = "Import Backup",
            onClick = { openLauncher.launch(arrayOf("*/*")) },
        )
        if (state.isWorking) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Working — deriving the encryption key can take a few seconds…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (showingExportDialog) {
        ExportPassphraseDialog(
            onDismiss = { showingExportDialog = false },
            onConfirm = { passphrase ->
                showingExportDialog = false
                viewModel.exportBackup(passphrase)
            },
        )
    }

    if (state.pendingImportBytes != null) {
        ImportPassphraseDialog(
            onDismiss = { viewModel.cancelPendingImport() },
            onConfirm = { passphrase ->
                val bytes = state.pendingImportBytes
                if (bytes != null) viewModel.importBackup(bytes, passphrase)
            },
        )
    }

    if (state.showPinRequiredDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPinRequiredDialog() },
            title = { Text("Set a PIN First") },
            text = { Text("MEGA requires a PIN before importing saved data — set one up in the PIN section above, then try importing again.") },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissPinRequiredDialog() }) { Text("OK") }
            },
        )
    }

    LaunchedEffect(state.result) {
        when (val result = state.result) {
            is BackupOperationResult.ExportSucceeded -> {
                pendingExportBytes = result.fileBytes
                saveLauncher.launch(suggestedBackupFileName())
                viewModel.clearResult()
            }
            is BackupOperationResult.ExportFailed -> {
                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                viewModel.clearResult()
            }
            is BackupOperationResult.ImportSucceeded -> {
                val summary = "Imported ${result.sessionCount} session(s) and ${result.vaultCount} vault(s)."
                Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
                viewModel.clearResult()
            }
            is BackupOperationResult.ImportFailed -> {
                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                viewModel.clearResult()
            }
            null -> Unit
        }
    }
}

@Composable
private fun ExportPassphraseDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }

    val tooShort = passphrase.length < MIN_BACKUP_PASSPHRASE_LENGTH
    val mismatched = confirmPassphrase.isNotEmpty() && passphrase != confirmPassphrase

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a Backup Passphrase") },
        text = {
            Column {
                Text(
                    "Anyone with this file AND this passphrase can recover every " +
                        "seed phrase in it. Choose something strong, and store it " +
                        "somewhere separate from the backup file itself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                    supportingText = { Text("At least $MIN_BACKUP_PASSPHRASE_LENGTH characters") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirmPassphrase,
                    onValueChange = { confirmPassphrase = it },
                    label = { Text("Confirm Passphrase") },
                    singleLine = true,
                    isError = mismatched,
                    visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                    supportingText = { if (mismatched) Text("Doesn't match") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (showPassphrase) "Hide passphrase" else "Show passphrase",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showPassphrase = !showPassphrase },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !tooShort && !mismatched && confirmPassphrase.isNotEmpty(),
                onClick = { onConfirm(passphrase) },
            ) { Text("Export") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ImportPassphraseDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup Passphrase") },
        text = {
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Passphrase") },
                singleLine = true,
                visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = passphrase.isNotEmpty(), onClick = { onConfirm(passphrase) }) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun suggestedBackupFileName(): String {
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    return "MEGA-Backup-$date.megabackup"
}

private fun writeBackupBytes(context: Context, destination: Uri, bytes: ByteArray): Boolean {
    return try {
        context.contentResolver.openOutputStream(destination)?.use { output -> output.write(bytes) } != null
    } catch (e: Exception) {
        // Deliberately not logged — see copyPdfToDestination's identical
        // reasoning: the exception can carry the user-chosen path, and
        // this app's policy is no Log calls at all.
        false
    }
}

private fun readBackupBytes(context: Context, source: Uri): ByteArray? {
    return try {
        context.contentResolver.openInputStream(source)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    }
}

/** Same EXTRA_LOCAL_ONLY reasoning as MultisigVaultPdfMenuButton's
 * CreateLocalPdfDocument — prefer excluding cloud-storage picker
 * destinations for a file this sensitive. */
private object CreateLocalBackupDocument : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_TITLE, input)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data.takeIf { resultCode == Activity.RESULT_OK }
}

private object OpenLocalBackupDocument : ActivityResultContract<Array<String>, Uri?>() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, input)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data.takeIf { resultCode == Activity.RESULT_OK }
}
