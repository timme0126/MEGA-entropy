package org.mega.entropy.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Renders [content] as a QR code. Purely local rendering (zxing's encoder
 * runs entirely on-device, no network) — used for public account data (an
 * xpub/ypub/zpub), and, in the Danger Zone's explicit WIF private-key
 * export flow only (behind allowPrivateKeyExport + its own confirmation
 * dialog), a single derived private key — the same "Sweep Private Key via
 * QR" pattern Sparrow Wallet and other desktop wallets use to scan a WIF
 * key from a phone screen. Never used for the mnemonic/seed words
 * themselves, which stay off every QR path in the app.
 */
@Composable
fun MegaQrCode(
    content: String,
    contentDescription: String = "QR code",
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(content) { qrBitmap(content) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color.White)
            .padding(12.dp),
    )
}

private fun qrBitmap(content: String, sizePx: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap
}
