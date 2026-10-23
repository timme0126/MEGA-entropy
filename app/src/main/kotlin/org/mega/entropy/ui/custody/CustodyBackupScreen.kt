package org.mega.entropy.ui.custody

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlinx.coroutines.launch
import org.mega.entropy.ui.a11y.MegaReadAloudButton
import org.mega.entropy.ui.a11y.buildPassphraseAnnouncement
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaCopyIconButton
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.megaAccessibilityModeActive
import org.mega.entropy.ui.components.megaLiveRegion
import org.mega.entropy.ui.theme.MegaError

/**
 * "Custody Backup+" — seals the full standard backup plus one attached
 * document (e.g. estate planning documents) into a single
 * MEGACUSTODY-V1 container for a trusted third party, encrypted under
 * one passphrase that is shown exactly once and never stored.
 *
 * Files never touch app-private storage: attachment in, container out,
 * and the verify path's extracted records all move through
 * user-chosen SAF documents only.
 *
 * The gated "Read passphrase aloud" button under the generated
 * passphrase: TextToSpeech is offline/local and the speech is audio in
 * the room — this is the user's own generated secret, spoken once so
 * they can verify what they write down, never logged, and composed
 * only by buildPassphraseAnnouncement (see the invariant in
 * MegaReadAloud.kt). User-typed passphrase fields get no such button:
 * only the app's own generated value is ever read aloud.
 */
@Composable
fun CustodyBackupScreen(
    // Stored "Accessibility mode" value; see megaAccessibilityModeActive
    // for how it combines with live touch exploration.
    accessibilityModeEnabled: Boolean,
    onBack: () -> Unit,
) {
    val viewModel: CustodyViewModel = viewModel()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // Same gate shape as PsbtReviewScreen: computed once per screen,
    // gating only the read-aloud button (and its TTS).
    val readAloudEnabled = remember(accessibilityModeEnabled) {
        megaAccessibilityModeActive(context, accessibilityModeEnabled)
    }

    val busy by viewModel.busy.collectAsState()
    val progress by viewModel.progress.collectAsState()

    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    var verifyPassphrase by remember { mutableStateOf("") }
    var showVerifyPassphrase by remember { mutableStateOf(false) }
    var generatedPassphrase by remember { mutableStateOf<String?>(null) }
    var attachmentUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var attachmentName by remember { mutableStateOf<String?>(null) }
    var attachmentSize by remember { mutableStateOf(0) }
    var created by remember { mutableStateOf<CreatedEnvelope?>(null) }
    var envelopeUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var envelopeLabel by remember { mutableStateOf<String?>(null) }
    var envelopeSizeBytes by remember { mutableStateOf(0L) }
    var envelopeAttachmentName by remember { mutableStateOf("attachment.bin") }
    var pendingBackupOut by remember { mutableStateOf<android.net.Uri?>(null) }
    var verifyOutcome by remember { mutableStateOf<VerifyResult?>(null) }
    var extractConfirm by remember { mutableStateOf(false) }
    var errorDialog by remember { mutableStateOf<String?>(null) }

    val pickAttachment = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { viewModel.describe(uri) }
                    .onSuccess { (name, size) ->
                        attachmentUri = uri
                        attachmentName = name
                        attachmentSize = size
                        created = null
                    }
                    .onFailure { errorDialog = it.message ?: "Could not read that file" }
            }
        }
    }

    val saveContainer = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { dest ->
        val uri = attachmentUri
        val name = attachmentName
        val size = attachmentSize
        if (dest != null && uri != null && name != null && size > 0) {
            scope.launch {
                runCatching { viewModel.createCustodyEnvelope(passphrase, uri, name, size, dest) }
                    .onSuccess {
                        created = it
                    }
                    .onFailure {
                        errorDialog = (it.message ?: "Sealing failed") +
                            "\n\nIf a partial file was written to the chosen location, delete it before retrying."
                    }
            }
        }
    }

    val pickEnvelope = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { viewModel.readEnvelopeInfo(uri) }
                    .onSuccess { info ->
                        envelopeUri = uri
                        envelopeAttachmentName = info.attachmentName
                        envelopeLabel =
                            "MEGA custody container — holds the document " +
                            "\"${info.attachmentName}\" (about " +
                            "${formatSize(info.attachmentSize.toLong())})."
                        verifyOutcome = null
                        envelopeSizeBytes = viewModel.sizeOf(uri)
                    }
                    .onFailure {
                        envelopeUri = null
                        envelopeLabel = null
                        errorDialog = "This file is not a MEGA custody container, or it is damaged."
                    }
            }
        }
    }

    val saveFingerprint = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { dest ->
        val result = created
        if (dest != null && result != null) {
            scope.launch {
                runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        context.contentResolver
                            .openOutputStream(dest)?.use { os ->
                                val text = buildString {
                                    append("MEGA custody container — SHA-256 fingerprint\n\n")
                                    append(result.fingerprintHex).append("\n\n")
                                    append("Container size: ").append(result.containerSizeBytes).append(" bytes\n")
                                    append("Recorded: ")
                                    append(
                                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                                            .format(java.util.Date()),
                                    )
                                    append("\n")
                                }
                                os.write(text.toByteArray(Charsets.UTF_8))
                            } ?: error("Could not open the destination")
                    }
                }.onFailure { errorDialog = it.message ?: "Could not write the fingerprint file" }
            }
        }
    }

    val saveAttachmentOut = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { attachOut ->
        val envUri = envelopeUri
        val backupOut = pendingBackupOut
        if (envUri != null && backupOut != null && attachOut != null) {
            val pending = backupOut
            pendingBackupOut = null
            scope.launch {
                runCatching {
                    viewModel.verifyContainer(
                        verifyPassphrase,
                        envUri,
                        viewModel.sizeOf(envUri),
                        pending,
                        attachOut,
                    )
                }
                    .onSuccess {
                        verifyOutcome = it
                    }
                    .onFailure {
                        errorDialog = decryptErrorMessage(it) +
                            if (envelopeSizeBytes > CustodyViewModel.IN_MEMORY_VERIFY_LIMIT) {
                                "\n\nBecause this container is large, the two files it just " +
                                "started saving are unusable — please delete them."
                            } else {
                                ""
                            }
                    }
            }
        }
    }

    val saveBackupOut = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { backupOut ->
        if (backupOut != null && envelopeUri != null) {
            pendingBackupOut = backupOut
            saveAttachmentOut.launch(suggestedFileName(envelopeAttachmentName))
        }
    }

    MegaInfoScaffold(title = "Custody Backup+", onBack = onBack) {
        Text(
            text = "Wrap your complete MEGA backup and one attached document — for example " +
                "your estate planning documents — into a single encrypted container for a " +
                "trusted third party.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = "Only the passphrase holder can ever open it — not even MEGA. Send the " +
                "encrypted container file to your Bitcoin Advisor, and share the on-screen " +
                "fingerprint with them so they can confirm the exact file arrived intact.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Text("1 · Passphrase (hand this to your trusted third party)", style = MaterialTheme.typography.titleMedium)
        PassphraseField(
            label = "Passphrase — min ${CustodyViewModel.MIN_PASSPHRASE_CHARS} characters",
            value = passphrase,
            onValueChange = { passphrase = it },
            visible = showPassphrase,
            onToggleVisible = { showPassphrase = !showPassphrase },
            enabled = !busy,
        )
        PassphraseField(
            label = "Re-enter passphrase",
            value = confirmPassphrase,
            onValueChange = { confirmPassphrase = it },
            visible = showPassphrase,
            onToggleVisible = { showPassphrase = !showPassphrase },
            enabled = !busy,
        )
        if (confirmPassphrase.isNotEmpty() && confirmPassphrase != passphrase) {
            Text(
                text = "The two passphrases do not match.",
                style = MaterialTheme.typography.bodyMedium,
                color = MegaError,
                modifier = Modifier.megaLiveRegion(),
            )
        }
        MegaPrimaryButton(
            text = "Generate strong passphrase",
            enabled = !busy,
            onClick = {
                val generated = viewModel.generatePassphrase()
                generatedPassphrase = generated
                passphrase = generated
                confirmPassphrase = generated
                showPassphrase = true
            },
        )
        generatedPassphrase?.let { generated ->
            MegaCard(title = "Generated passphrase — shown only once") {
                SelectionContainer {
                    MegaMonoText(generated)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MegaCopyIconButton(
                        contentDescription = "Copy passphrase",
                        getTextToCopy = { generated },
                    )
                    Text(
                        text = "Copy it NOW or write it down. It is the only way to open the " +
                            "container, and it is never stored anywhere, including here.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // Read the GENERATED value only — composed solely by
                // buildPassphraseAnnouncement (raw groups, then a full
                // NATO spelling) so the user can verify the characters
                // they write down against an unambiguous audio pass.
                if (readAloudEnabled) {
                    MegaReadAloudButton(
                        value = generated,
                        buildAnnouncement = ::buildPassphraseAnnouncement,
                        label = "Read passphrase aloud",
                    )
                }
            }
        }

        Text("2 · Attach one document", style = MaterialTheme.typography.titleMedium)
        MegaPrimaryButton(
            text = if (attachmentName == null) "Choose file to attach" else "Replace attached file",
            enabled = !busy,
            onClick = { pickAttachment.launch(arrayOf("*/*")) },
        )
        attachmentName?.let { name ->
            Text("$name (${formatSize(attachmentSize.toLong())})", style = MaterialTheme.typography.bodyMedium)
        }

        MegaPrimaryButton(
            text = "Create Encrypted Container",
            enabled = !busy &&
                passphrase.length >= CustodyViewModel.MIN_PASSPHRASE_CHARS &&
                confirmPassphrase == passphrase &&
                attachmentUri != null,
            onClick = { saveContainer.launch("mega-encrypted-container.megacustody") },
        )
        created?.let { result ->
            MegaCard(title = "Container created") {
                Text(
                    text = "Written (${formatSize(result.containerSizeBytes)}). Share this " +
                        "fingerprint out-of-band so the recipient can verify the exact file:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SelectionContainer {
                    MegaMonoText(result.fingerprintHex)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MegaCopyIconButton(
                        contentDescription = "Copy fingerprint",
                        getTextToCopy = { result.fingerprintHex },
                    )
                    Text(
                        text = "Or save it as a text file to keep with the container:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                MegaPrimaryButton(
                    text = "Save fingerprint (.txt)",
                    onClick = { saveFingerprint.launch("mega-container-fingerprint.txt") },
                )
            }
        }

        Text("Verify an existing container", style = MaterialTheme.typography.titleMedium)
        MegaPrimaryButton(
            text = "Choose container to verify…",
            enabled = !busy,
            onClick = { pickEnvelope.launch(arrayOf("*/*")) },
        )
        envelopeLabel?.let { label ->
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = "Two separate steps:\n" +
                "• Check passphrase — authenticates the whole container and writes " +
                "NOTHING. Use this to test a key.\n" +
                "• Extract backup + attachment — verifies first, then writes the two " +
                "files (the restore-ready backup and the attached document) to locations " +
                "you choose.",
            style = MaterialTheme.typography.bodyMedium,
        )
        PassphraseField(
            label = "Container passphrase",
            value = verifyPassphrase,
            onValueChange = { verifyPassphrase = it },
            visible = showVerifyPassphrase,
            onToggleVisible = { showVerifyPassphrase = !showVerifyPassphrase },
            enabled = !busy,
        )
        MegaPrimaryButton(
            text = "Check passphrase (writes nothing)",
            enabled = !busy && envelopeUri != null && verifyPassphrase.isNotEmpty(),
            onClick = {
                val envUri = envelopeUri ?: return@MegaPrimaryButton
                scope.launch {
                    runCatching {
                        viewModel.verifyContainer(verifyPassphrase, envUri, viewModel.sizeOf(envUri))
                    }
                        .onSuccess {
                            verifyOutcome = it
                        }
                        .onFailure {
                            errorDialog = decryptErrorMessage(it)
                        }
                }
            },
        )
        MegaPrimaryButton(
            text = "Extract backup + attachment…",
            enabled = !busy && envelopeUri != null && verifyPassphrase.isNotEmpty(),
            onClick = { extractConfirm = true },
        )
        if (envelopeUri != null && verifyPassphrase.isEmpty()) {
            Text(
                text = "Enter the container passphrase above to enable these.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        verifyOutcome?.let { result ->
            MegaCard(title = "Verified") {
                Text(
                    text = "Passphrase correct and integrity confirmed — the container is " +
                        "exactly what was sealed, with \"${result.attachmentName}\" inside.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (result.wroteFiles) {
                    Text(
                        text = "The backup file and the attachment were written to the " +
                            "locations you chose. Restore the backup in a fresh MEGA " +
                            "install via Settings → Backup with the SAME passphrase.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = "Nothing was written. When you're ready, use " +
                            "\"Extract backup + attachment…\" to save the files.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Centered, scroll-proof: long steps explain themselves wherever
        // the user happens to be looking, instead of off-screen.
        if (busy) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Working") },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                        Text(
                            text = progress ?: "Working… Keep MEGA open.",
                            style = MaterialTheme.typography.bodyMedium,
                            // Export steps mutate this in place — announce each.
                            modifier = Modifier.megaLiveRegion(),
                        )
                    }
                },
                confirmButton = {},
            )
        }

        if (extractConfirm) {
            AlertDialog(
                onDismissRequest = { extractConfirm = false },
                title = { Text("Extract the two files?") },
                text = {
                    Text(
                        "MEGA will check the passphrase against the container. If it " +
                            "matches, you'll be asked where to save the two things inside:\n\n" +
                            "• your MEGA backup — restore-ready on any phone\n" +
                            "• the attached document\n\n" +
                            "If the passphrase does not match, nothing is saved." +
                            if (envelopeSizeBytes > CustodyViewModel.IN_MEMORY_VERIFY_LIMIT) {
                                "\n\n(This container is large: checking it reads the whole " +
                                    "file, which can take a few minutes.)"
                            } else {
                                ""
                            },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            extractConfirm = false
                            saveBackupOut.launch("mega-backup.megabkp")
                        },
                    ) { Text("Continue") }
                },
                dismissButton = {
                    TextButton(onClick = { extractConfirm = false }) { Text("Cancel") }
                },
            )
        }

        errorDialog?.let { message ->
            AlertDialog(
                onDismissRequest = { errorDialog = null },
                title = { Text("Could not continue") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { errorDialog = null }) { Text("OK") }
                },
            )
        }
    }
}

/**
 * Turn raw cipher failures into something a person can act on. A bad GCM
 * tag is indistinguishable from a wrong key — that IS the security
 * design — so the honest, safe message is about the passphrase.
 */
private fun decryptErrorMessage(t: Throwable): String {
    val raw = t.message.orEmpty()
    return if (t is javax.crypto.AEADBadTagException ||
        (
            t is java.security.GeneralSecurityException &&
                (
                    raw.contains("BAD_DECRYPT", ignoreCase = true) ||
                        raw.contains("Tag mismatch", ignoreCase = true)
                    )
            )
    ) {
        "MEGA could not unlock the container with that passphrase.\n\n" +
            "Check the passphrase and try again. If it still fails, the passphrase is " +
            "wrong or the file did not transfer intact. Nothing has been changed."
    } else {
        raw.ifEmpty { "Something went wrong — nothing has been changed." }
    }
}

/** SAF-suggested name: keep it printable and non-empty. */
private fun suggestedFileName(raw: String): String =
    raw.map { if (it.isLetterOrDigit() || it in ".-_ ") it else '_' }
        .joinToString("")
        .trim()
        .ifEmpty { "attachment.bin" }

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes bytes"
}

/**
 * Password-style field with an explicit show/hide toggle — a passphrase
 * the user must transcribe or re-enter is not a field to keep masked
 * by force.
 */
@Composable
private fun PassphraseField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (visible) {
            androidx.compose.ui.text.input.VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    imageVector = if (visible) {
                        Icons.Default.VisibilityOff
                    } else {
                        Icons.Default.Visibility
                    },
                    contentDescription = if (visible) "Hide passphrase" else "Show passphrase",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
