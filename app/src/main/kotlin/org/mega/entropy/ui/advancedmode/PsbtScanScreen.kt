package org.mega.entropy.ui.advancedmode

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.util.Base64
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import org.mega.entropy.bbqr.BbqrAccumulateStatus
import org.mega.entropy.bbqr.accumulateBbqrPart
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.BbqrPart
import org.mega.entropycore.assembleBbqrPartsAsBytes
import org.mega.entropycore.parseBbqrPart

@Composable
fun PsbtScanScreen(
    allowScreenshots: Boolean,
    onBack: () -> Unit,
    onScanned: (ByteArray) -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)

    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    MegaInfoScaffold(title = "Scan PSBT", onBack = onBack) {
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
            text = "MEGA uses the camera only on this screen to scan a PSBT (Partially Signed Bitcoin Transaction) QR code. No image is saved, shared, or sent anywhere.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MegaPrimaryButton(text = "Allow Camera", onClick = onRequestPermission)
    }
}

@Composable
private fun ScannerContent(onScanned: (ByteArray) -> Unit) {
    var cameraError by remember { mutableStateOf<String?>(null) }
    var bbqrParts by remember { mutableStateOf<Map<Int, BbqrPart>>(emptyMap()) }
    var bbqrError by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }

    fun handleDecodedText(text: String) {
        if (finished) return
        val part = parseBbqrPart(text)
        if (part == null) {
            try {
                val decoded = Base64.decode(text, Base64.DEFAULT)
                finished = true
                onScanned(decoded)
            } catch (_: IllegalArgumentException) {
                bbqrError = "Not a valid PSBT QR code."
                return
            }
            return
        }

        val accumulation = accumulateBbqrPart(bbqrParts, part)
        bbqrParts = accumulation.parts
        bbqrError = when (accumulation.status) {
            BbqrAccumulateStatus.ConflictingPart ->
                "Scanned two different versions of part ${part.index + 1} — hold the camera on ONE QR series only."
            else -> null
        }

        val updated = accumulation.parts
        if (updated.size == part.total) {
            try {
                val assembled = assembleBbqrPartsAsBytes(updated.values.toList())
                finished = true
                onScanned(assembled)
            } catch (e: IllegalArgumentException) {
                bbqrError = e.message ?: "Could not read this BBQr code."
                bbqrParts = emptyMap()
            }
        }
    }

    MegaCard {
        Text(
            text = "Point the camera at a PSBT QR code — either a single QR (small PSBTs, base64-encoded) or an animated BBQr series (larger PSBTs, e.g. from a hardware signer or another wallet).",
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
                onScanned = ::handleDecodedText,
                onCameraError = { cameraError = it },
            )
            Icon(
                imageVector = Icons.Filled.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(88.dp),
                tint = Color.White.copy(alpha = 0.78f),
            )
        }
        val partsInProgress = bbqrParts.values.firstOrNull()
        if (partsInProgress != null) {
            Text(
                text = "Scanned ${bbqrParts.size} of ${partsInProgress.total} parts — keep the camera on the animated QR code until every part is read.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "Nothing scanned is stored. The scanner closes once a single QR (or every part of an animated BBQr series) has been read.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val currentError = cameraError ?: bbqrError
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
    private val lastDecodedText = AtomicReference<String?>(null)
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    override fun analyze(image: ImageProxy) {
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
            val result = decode(source)
            val text = result?.trim().orEmpty()
            if (text.isNotEmpty() && lastDecodedText.getAndSet(text) != text) {
                mainExecutor.execute { onQrScanned(text) }
            }
        } catch (_: Exception) {
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
