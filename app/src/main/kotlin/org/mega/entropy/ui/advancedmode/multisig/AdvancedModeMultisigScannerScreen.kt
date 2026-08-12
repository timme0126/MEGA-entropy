package org.mega.entropy.ui.advancedmode.multisig

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError

/**
 * Camera scanner for multisig cosigner QR codes. Decoding is entirely local:
 * CameraX supplies frames, ZXing reads QR content from the luminance plane,
 * and the scanned text is handed back to MultisigVaultViewModel where it is
 * parsed by the exact same code path used for pasted descriptor fragments.
 */
@Composable
fun AdvancedModeMultisigScannerScreen(
    allowScreenshots: Boolean,
    onBack: () -> Unit,
    onScanned: (String) -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)

    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    MegaInfoScaffold(title = "Scan Cosigner Key", onBack = onBack) {
        if (hasCameraPermission) {
            ScannerContent(onScanned = onScanned)
        } else {
            CameraPermissionContent(onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) })
        }
    }
}

@Composable
private fun CameraPermissionContent(onRequestPermission: () -> Unit) {
    MegaCard {
        Icon(
            imageVector = Icons.Filled.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Camera permission required",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "MEGA uses the camera only on this screen to scan a public multisig cosigner key or descriptor QR code. No image is saved, shared, or sent anywhere.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MegaPrimaryButton(text = "Allow Camera", onClick = onRequestPermission)
    }
}

@Composable
private fun ScannerContent(onScanned: (String) -> Unit) {
    var cameraError by remember { mutableStateOf<String?>(null) }

    MegaCard {
        Text(
            text = "Point the camera at a descriptor fragment or full multisig descriptor QR code.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    MegaCard {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                onScanned = onScanned,
                onCameraError = { cameraError = it },
            )
            Icon(
                imageVector = Icons.Filled.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(88.dp),
                tint = Color.White.copy(alpha = 0.78f),
            )
        }
        Text(
            text = "Nothing scanned is stored. The decoded text is parsed once and the scanner closes after the first valid QR read.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val currentError = cameraError
    if (currentError != null) {
        Text(
            text = currentError,
            style = MaterialTheme.typography.bodyMedium,
            color = MegaError,
        )
    }
}

@Composable
private fun CameraPreview(
    modifier: Modifier = Modifier,
    onScanned: (String) -> Unit,
    onCameraError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(AndroidColor.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    DisposableEffect(context, lifecycleOwner, previewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val analyzer = QrCodeAnalyzer(
            mainExecutor = mainExecutor,
            onQrScanned = onScanned,
        )
        val listener = Runnable {
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analyzerExecutor, analyzer) }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (e: Exception) {
                onCameraError(e.message ?: "Could not start the camera scanner.")
            }
        }
        cameraProviderFuture.addListener(listener, mainExecutor)

        onDispose {
            runCatching {
                if (cameraProviderFuture.isDone) cameraProviderFuture.get().unbindAll()
            }
            analyzerExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}

private class QrCodeAnalyzer(
    private val mainExecutor: Executor,
    private val onQrScanned: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val decoded = AtomicBoolean(false)
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    override fun analyze(image: ImageProxy) {
        if (decoded.get()) {
            image.close()
            return
        }

        try {
            val luminance = image.extractLuminanceBytes()
            val source = PlanarYUVLuminanceSource(
                luminance,
                image.width,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false,
            )
            // ZXing's QR finder patterns are rotation-invariant within the image
            // plane, so a single decode pass already finds a QR regardless of how
            // the frame itself is rotated. There used to be a
            // decode(source.rotateCounterClockwise()) fallback here, but
            // PlanarYUVLuminanceSource never supports that operation — it throws
            // UnsupportedOperationException unconditionally — which crashed the
            // app on the very first analyzed frame every time the scanner opened,
            // before any QR code was ever in view.
            val result = decode(source)
            val text = result?.trim().orEmpty()
            if (text.isNotEmpty() && decoded.compareAndSet(false, true)) {
                mainExecutor.execute { onQrScanned(text) }
            }
        } catch (_: Exception) {
            // A single unreadable/malformed frame must never crash or tear down
            // the scanner — ReaderException (no QR in this frame) is the normal
            // case; anything broader here is defense-in-depth so a decode-path
            // edge case on other frames/devices degrades to "try next frame"
            // instead of taking down the app, the same way the rotation bug did.
        } finally {
            reader.reset()
            image.close()
        }
    }

    private fun decode(source: com.google.zxing.LuminanceSource): String? = try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: ReaderException) {
        null
    }
}

private fun ImageProxy.extractLuminanceBytes(): ByteArray {
    val yPlane = planes.first()
    val buffer = yPlane.buffer.duplicate()
    val rowStride = yPlane.rowStride
    val pixelStride = yPlane.pixelStride
    val output = ByteArray(width * height)

    if (pixelStride == 1 && rowStride == width) {
        buffer.get(output, 0, min(output.size, buffer.remaining()))
        return output
    }

    val row = ByteArray(rowStride)
    var outputOffset = 0
    for (y in 0 until height) {
        val rowStart = y * rowStride
        if (rowStart >= buffer.limit()) break
        buffer.position(rowStart)
        val bytesToRead = min(rowStride, buffer.remaining())
        buffer.get(row, 0, bytesToRead)
        for (x in 0 until width) {
            val rowIndex = x * pixelStride
            output[outputOffset++] = if (rowIndex < bytesToRead) row[rowIndex] else 0
        }
    }
    return output
}
