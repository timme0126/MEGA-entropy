package org.mega.entropy.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders [content] as a QR code. Purely local rendering (zxing's encoder
 * runs entirely on-device, no network) — used for public account data (an
 * xpub/ypub/zpub), and, in the Danger Zone's explicit WIF private-key
 * export flow only (behind allowPrivateKeyExport + its own confirmation
 * dialog), a single derived private key — the same "Sweep Private Key via
 * QR" pattern Sparrow Wallet and other desktop wallets use to scan a WIF
 * key from a phone screen. Never used for the mnemonic/seed words
 * themselves, which stay off every QR path in the app.
 *
 * A multisig output descriptor can run past 2000 characters for a large
 * cosigner set — long enough that QR encoding can fail outright rather
 * than just looking dense. qrBitmap returns null on that failure instead
 * of throwing, and this composable shows a plain-text fallback rather
 * than crash the screen; the copy button next to every QR in this app
 * remains the reliable way to move the same value.
 */
@Composable
fun MegaQrCode(
    content: String,
    contentDescription: String = "QR code",
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(content) { qrBitmap(content) }
    val boxModifier = modifier
        .fillMaxWidth()
        .aspectRatio(1f)
        .background(Color.White)
        .padding(12.dp)

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = boxModifier,
        )
    } else {
        // Same footprint as the real QR code so surrounding layout doesn't
        // jump depending on whether encoding happened to succeed.
        Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
            Text(
                text = "This value is too long to display as a QR code — use the copy button above instead, or import it manually.",
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Explicit encoding hints rather than relying on zxing's own defaults:
 * lowest error-correction level (highest data capacity — appropriate
 * here since these are long alphanumeric-ish strings like xpubs and
 * multisig descriptors scanned in good conditions, not something that
 * needs to survive a smudged or damaged physical printout) and a small,
 * fixed quiet-zone margin, so a future zxing version bump can't silently
 * change how dense/large a rendered code is.
 */
private val QR_HINTS: Map<EncodeHintType, Any> = mapOf(
    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
    EncodeHintType.MARGIN to 1,
)

/**
 * Returns null (never throws) when [content] cannot be encoded at the
 * given size/error-correction level — WriterException is zxing's own
 * signal for "too much data for this QR version", which a sufficiently
 * long multisig descriptor can genuinely trigger. IllegalArgumentException
 * covers zxing's other rejection path (e.g. empty content) the same way.
 * Any other exception type is unexpected and is allowed to propagate
 * rather than being silently treated as "just show the fallback text".
 */
internal fun qrBitmap(content: String, sizePx: Int = 512): Bitmap? {
    val matrix = try {
        QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, QR_HINTS)
    } catch (e: WriterException) {
        return null
    } catch (e: IllegalArgumentException) {
        return null
    }
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap
}
