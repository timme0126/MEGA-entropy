package org.mega.entropy.pdf

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import org.mega.entropy.ui.advancedmode.multisig.CosignerDisplayInfo
import org.mega.entropy.ui.components.qrBitmap
import org.mega.entropycore.MultisigWallet

private const val PAGE_WIDTH_PX = 612 // US Letter width at 72dpi, matching drawMultisigVaultPdfPage's px convention.

/**
 * Generates a one-page PDF of a multisig vault (label, descriptor, QR,
 * first receive address, cosigner tiles) to the app's private cache dir —
 * never external/shared storage, so no storage permission is ever touched
 * — and returns a content:// URI for it via FileProvider, scoped to just
 * that one file with a short-lived read grant (see shareMultisigVaultPdf).
 * All drawing logic lives in drawMultisigVaultPdfPage; this function only
 * owns the PdfDocument/file/URI plumbing around it.
 */
fun exportMultisigVaultPdf(
    context: Context,
    vaultLabel: String,
    wallet: MultisigWallet,
    cosigners: List<CosignerDisplayInfo>,
): Uri {
    val cosignerLines = cosigners.map { it.toCosignerLine() }
    val qr = qrBitmap(wallet.descriptor, sizePx = 480)
    val pageHeight = estimatePageHeightPx(wallet.descriptor, cosignerLines, hasQr = qr != null)

    val document = PdfDocument()
    val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH_PX, pageHeight, 1).create())
    drawMultisigVaultPdfPage(
        canvas = page.canvas,
        pageWidthPx = PAGE_WIDTH_PX,
        pageHeightPx = pageHeight,
        vaultLabel = vaultLabel,
        thresholdOfCount = "${wallet.threshold}-of-${wallet.cosigners.size} multisig",
        descriptor = wallet.descriptor,
        firstReceiveAddress = wallet.firstReceiveAddress,
        qrBitmap = qr,
        cosigners = cosignerLines,
    )
    document.finishPage(page)

    val pdfDir = File(context.cacheDir, "pdfs")
    pdfDir.mkdirs()
    // Best-effort clear of previously exported PDFs — this directory is
    // reachable via FileProvider, so nothing generated for an earlier
    // (possibly different) vault should linger in it.
    pdfDir.listFiles()?.forEach { it.delete() }
    val file = File(pdfDir, "${sanitizePdfFileName(vaultLabel)}.pdf")
    FileOutputStream(file).use { output -> document.writeTo(output) }
    document.close()

    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/**
 * Launches the system share sheet for a PDF produced by
 * [exportMultisigVaultPdf] — "Print" (Android's own print framework can
 * save a shared PDF straight to PDF/a physical printer from here), email,
 * or any other app the user picks. FLAG_GRANT_READ_URI_PERMISSION scopes
 * the read grant to exactly the receiving app for exactly this URI, per
 * the standard FileProvider sharing pattern — nothing broader than that is
 * ever granted.
 */
fun shareMultisigVaultPdf(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share multisig vault PDF"))
}

private fun CosignerDisplayInfo.toCosignerLine(): CosignerLine = CosignerLine(
    label = label,
    masterFingerprint = masterFingerprint,
    derivationPath = derivationPath,
    extendedPublicKey = extendedPublicKey,
    accountIndexText = accountIndex?.let { "Account index: $it" },
    passphraseUsedText = when (passphraseUsed) {
        true -> "Passphrase: used"
        false -> "Passphrase: not used"
        null -> "Passphrase: unknown (not derived on this device)"
    },
)

/** A user-chosen vault label is free text and may contain characters that
 * are unsafe or meaningless in a filename (path separators, control
 * characters); this keeps only characters that are safe everywhere PDFs
 * commonly get shared to/from, falling back to a fixed name if nothing
 * usable is left. */
private fun sanitizePdfFileName(label: String): String {
    val sanitized = label.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").trim()
    return sanitized.ifEmpty { "multisig-vault" }
}

/**
 * A deliberately generous estimate — better to leave blank space at the
 * bottom of the page than clip content. Independent of
 * drawMultisigVaultPdfPage's own exact line-height constants (that
 * function is explicitly allowed to run past pageHeightPx without
 * crashing — see its doc comment — so an estimate here that runs a little
 * short is not a correctness bug, just a slightly awkward page break were
 * this ever split across pages, which it currently is not).
 */
private fun estimatePageHeightPx(descriptor: String, cosigners: List<CosignerLine>, hasQr: Boolean): Int {
    val charsPerLine = 85
    val descriptorLines = ceil(descriptor.length / charsPerLine.toDouble()).toInt().coerceAtLeast(1)
    var height = 260 // title, summary, "Output Descriptor" header, top/bottom margins
    height += descriptorLines * 16
    height += if (hasQr) 520 else 40 // QR header + bitmap, or just a header if a QR wasn't generated
    height += 80 // "First Receive Address" header + address line
    height += 40 // "Cosigners" header
    cosigners.forEach { cosigner ->
        val xpubLines = ceil(cosigner.extendedPublicKey.length / charsPerLine.toDouble()).toInt().coerceAtLeast(1)
        height += 90 + xpubLines * 16 // label/fingerprint/path/account/passphrase lines + wrapped xpub + gap
    }
    return height.coerceAtLeast(792) // never smaller than one US Letter page
}
