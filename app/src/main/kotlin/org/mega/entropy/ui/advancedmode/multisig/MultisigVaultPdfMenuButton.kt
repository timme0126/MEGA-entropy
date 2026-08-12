package org.mega.entropy.ui.advancedmode.multisig

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.mega.entropy.pdf.copyPdfToDestination
import org.mega.entropy.pdf.exportMultisigVaultPdf
import org.mega.entropy.pdf.pdfSuggestedFileName
import org.mega.entropy.pdf.shareMultisigVaultPdf
import org.mega.entropycore.MultisigWallet

/**
 * Top-bar "Create PDF" icon for a multisig vault's descriptor/cosigner
 * record, offering:
 * - "Save to Device" — Storage Access Framework's document picker, which
 *   the system populates with every destination it exposes on the current
 *   device (internal storage, an SD card, or a mounted USB drive), with no
 *   storage permission needed at all — the picker itself grants a scoped
 *   write URI.
 * - "Share" — the existing system share sheet (Print, Bluetooth, email, or
 *   any other app that accepts a PDF).
 *
 * Generates the PDF itself from [wallet]/[cosigners]/[vaultLabel] rather
 * than taking a pre-built URI, so both callers (the freshly-built Result
 * step and a saved vault's read-only detail view) can just hand over their
 * own data. Lives at this level (not as a plain callback threaded through
 * MegaNavGraph) because rememberLauncherForActivityResult is a Compose API
 * that must be called during composition.
 */
@Composable
fun MultisigVaultPdfMenuButton(vaultLabel: String, wallet: MultisigWallet, cosigners: List<CosignerDisplayInfo>) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    // Holds the just-generated cache-file URI between "Save to Device" being
    // tapped (which generates the PDF and launches the picker) and the
    // picker's own callback (which needs that same URI to copy from).
    var pendingSaveSource by remember { mutableStateOf<Uri?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(CreateLocalPdfDocument) { destination ->
        val source = pendingSaveSource
        // A null destination means the user cancelled the picker — not a
        // failure, nothing to report. A non-null destination that still
        // fails to copy (rare — e.g. the target volume went away mid-pick)
        // must not fail silently: without this, the user would believe the
        // PDF saved when nothing was actually written.
        if (destination != null) {
            val copied = source != null && copyPdfToDestination(context, source, destination)
            if (!copied) {
                Toast.makeText(context, "Could not save the PDF to that location.", Toast.LENGTH_LONG).show()
            }
        }
        pendingSaveSource = null
    }

    IconButton(onClick = { menuExpanded = true }) {
        Icon(imageVector = Icons.Filled.PictureAsPdf, contentDescription = "Create PDF")
    }
    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
        DropdownMenuItem(
            text = { Text("Save to Device") },
            onClick = {
                menuExpanded = false
                val uri = exportMultisigVaultPdf(context, vaultLabel, wallet, cosigners)
                pendingSaveSource = uri
                saveLauncher.launch(pdfSuggestedFileName(vaultLabel))
            },
        )
        DropdownMenuItem(
            text = { Text("Share") },
            onClick = {
                menuExpanded = false
                val uri = exportMultisigVaultPdf(context, vaultLabel, wallet, cosigners)
                shareMultisigVaultPdf(context, uri)
            },
        )
    }
}

/**
 * Same shape as androidx.activity.result.contract.ActivityResultContracts.CreateDocument,
 * plus Intent.EXTRA_LOCAL_ONLY = true. That extra is only a hint — a
 * DocumentsProvider isn't required to honor it — but it tells the system
 * picker to prefer excluding remote/cloud-storage backends (Google Drive
 * and similar) from the destinations it offers, so "Save to Device" is
 * less likely to silently route a vault's descriptor/cosigner data off the
 * device through a picker entry a user might not realize is a cloud
 * provider. No API-level guard is needed: EXTRA_LOCAL_ONLY has existed
 * since API 19, well below this app's minSdk 29.
 */
private object CreateLocalPdfDocument : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_TITLE, input)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data.takeIf { resultCode == Activity.RESULT_OK }
}
