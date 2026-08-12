package org.mega.entropy.ui.components

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * QrCode.kt's own qrBitmap()/MegaQrCode() cannot be unit-tested directly —
 * both ultimately touch android.graphics.Bitmap, a real Android framework
 * class unavailable in a plain JVM test without Robolectric (not a
 * dependency of this project). What CAN be verified here, deterministically
 * and without any Android dependency, is the actual precondition the fix
 * depends on: that zxing really does throw WriterException for content too
 * large to encode at the hints/size QrCode.kt uses — proving that catch
 * clause targets a real, reachable failure mode rather than dead code.
 *
 * The full path (qrBitmap returning null, MegaQrCode rendering the
 * fallback Text instead of crashing) needs manual or on-device
 * verification — e.g. pasting an oversized multisig descriptor into the
 * app and confirming the fallback message appears instead of a crash.
 */
class QrCodeTest {

    private val sameHintsAsQrCodeDotKt: Map<EncodeHintType, Any> = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to 1,
    )

    @Test
    fun `zxing throws WriterException for content too large to encode at MEGA's QR size`() {
        // Comfortably past a 15-cosigner multisig descriptor's real-world length.
        val oversizedContent = "a".repeat(20_000)
        assertThrows(WriterException::class.java) {
            QRCodeWriter().encode(oversizedContent, BarcodeFormat.QR_CODE, 512, 512, sameHintsAsQrCodeDotKt)
        }
    }
}
