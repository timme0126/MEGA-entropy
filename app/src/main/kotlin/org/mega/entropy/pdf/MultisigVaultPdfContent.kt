package org.mega.entropy.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

data class CosignerLine(
    val label: String,
    val masterFingerprint: String,
    val derivationPath: String,
    val extendedPublicKey: String,
    val accountIndexText: String?,
    val passphraseUsedText: String,
)

/**
 * Draws one multisig vault's full record onto an already-created Canvas —
 * title, threshold summary, wrapped output descriptor, QR code (if one was
 * generated), first receive address, and a block per cosigner. Pure
 * drawing only: no PdfDocument, no file I/O — the caller owns creating the
 * page/document and writing it out. Does not paginate or clip to
 * pageHeightPx; content is allowed to run past it, left to the caller to
 * size the page generously enough (see MultisigVaultPdfExporter's height
 * estimate).
 */
fun drawMultisigVaultPdfPage(
    canvas: Canvas,
    pageWidthPx: Int,
    pageHeightPx: Int,
    vaultLabel: String,
    thresholdOfCount: String,
    descriptor: String,
    firstReceiveAddress: String,
    qrBitmap: Bitmap?,
    cosigners: List<CosignerLine>,
) {
    val margin = 36
    val spacing = 16
    val lineHeightMult = 1.3f
    val qrSize = 200
    val maxWidth = (pageWidthPx - 2 * margin).coerceAtLeast(1).toFloat()

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        isFakeBoldText = true
    }
    val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f
    }
    val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f
        isFakeBoldText = true
    }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f
        typeface = Typeface.MONOSPACE
    }
    val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f
    }
    val cosignerLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f
        isFakeBoldText = true
    }

    var y = margin.toFloat()

    canvas.drawText(vaultLabel, margin.toFloat(), y, titlePaint)
    y += titlePaint.textSize * lineHeightMult

    canvas.drawText(thresholdOfCount, margin.toFloat(), y, subtitlePaint)
    y += subtitlePaint.textSize * lineHeightMult + spacing

    canvas.drawText("Output Descriptor", margin.toFloat(), y, headerPaint)
    y += headerPaint.textSize * lineHeightMult + spacing
    for (line in wrapText(descriptor, bodyPaint, maxWidth)) {
        canvas.drawText(line, margin.toFloat(), y, bodyPaint)
        y += bodyPaint.textSize * lineHeightMult
    }
    y += spacing

    if (qrBitmap != null) {
        canvas.drawText("QR Code (Descriptor)", margin.toFloat(), y, headerPaint)
        y += headerPaint.textSize * lineHeightMult + spacing

        val qrRect = Rect(margin, y.toInt(), margin + qrSize, (y + qrSize).toInt())
        canvas.drawBitmap(qrBitmap, null, qrRect, null)
        y += qrSize + spacing
    }

    canvas.drawText("First Receive Address", margin.toFloat(), y, headerPaint)
    y += headerPaint.textSize * lineHeightMult + spacing
    canvas.drawText(firstReceiveAddress, margin.toFloat(), y, bodyPaint)
    y += bodyPaint.textSize * lineHeightMult + spacing

    canvas.drawText("Cosigners", margin.toFloat(), y, headerPaint)
    y += headerPaint.textSize * lineHeightMult + spacing

    for ((index, cosigner) in cosigners.withIndex()) {
        canvas.drawText("Cosigner ${index + 1}: ${cosigner.label}", margin.toFloat(), y, cosignerLabelPaint)
        y += cosignerLabelPaint.textSize * lineHeightMult + spacing

        canvas.drawText("Fingerprint: ${cosigner.masterFingerprint}", margin.toFloat(), y, bodyPaint)
        y += bodyPaint.textSize * lineHeightMult + spacing

        canvas.drawText("Path: ${cosigner.derivationPath}", margin.toFloat(), y, bodyPaint)
        y += bodyPaint.textSize * lineHeightMult + spacing

        // Tightly packed, single trailing gap after the whole wrapped value —
        // matching the descriptor's own wrapping above — rather than a gap
        // after every line, which would visually break one xpub into what
        // looks like several disconnected short paragraphs.
        for (line in wrapText(cosigner.extendedPublicKey, bodyPaint, maxWidth)) {
            canvas.drawText(line, margin.toFloat(), y, bodyPaint)
            y += bodyPaint.textSize * lineHeightMult
        }
        y += spacing

        if (cosigner.accountIndexText != null) {
            canvas.drawText(cosigner.accountIndexText, margin.toFloat(), y, smallPaint)
            y += smallPaint.textSize * lineHeightMult + spacing
        }

        canvas.drawText(cosigner.passphraseUsedText, margin.toFloat(), y, smallPaint)
        y += smallPaint.textSize * lineHeightMult + spacing

        y += spacing // Gap between one cosigner's block and the next.
    }
}

/** Breaks [text] into lines that each fit within [maxWidth] at [paint]'s
 * current text size, using Paint.breakText's own text measurement rather
 * than a fixed characters-per-line guess. Always makes progress — even if
 * breakText can't fit a single character at this width, one character is
 * force-taken per iteration — so this can never loop forever. */
private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
    val lines = mutableListOf<String>()
    if (text.isEmpty()) return lines

    var remaining = text
    val measuredWidth = FloatArray(1)
    while (remaining.isNotEmpty()) {
        val count = paint.breakText(remaining, true, maxWidth, measuredWidth)
        if (count == 0) {
            lines.add(remaining[0].toString())
            remaining = remaining.substring(1)
        } else {
            lines.add(remaining.substring(0, count))
            remaining = remaining.substring(count)
        }
    }
    return lines
}
