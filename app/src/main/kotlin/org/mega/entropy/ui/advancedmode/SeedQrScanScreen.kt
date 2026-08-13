package org.mega.entropy.ui.advancedmode

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.util.Size
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import org.mega.entropy.seedqr.SeedQrResult
import org.mega.entropy.seedqr.decodeSeedQr
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError

/**
 * Scans a Standard or Compact SeedQR — SeedSigner's format for encoding a
 * BIP39 mnemonic as a QR code, also read by Sparrow Wallet and similar
 * tools — using the device camera. Decoding (zxing.MultiFormatReader on
 * each camera frame's luminance plane) and SeedQR parsing
 * (org.mega.entropy.seedqr.decodeSeedQr) both happen entirely on-device;
 * nothing the camera sees is written to disk or sent anywhere.
 *
 * Shares its camera plumbing (PreviewView setup, DisposableEffect
 * teardown, per-frame ZXing decode) with AdvancedModeMultisigScannerScreen
 * rather than reintroducing a second implementation — this feature was
 * previously shelved specifically because its own, separately-written
 * scanner didn't reliably decode and crashed on the back button; reusing
 * the plumbing that's since been battle-tested (and had its own rotation
 * crash found and fixed) avoids repeating either bug. The one real
 * difference from the multisig scanner: this one (a) also needs ZXing's
 * BYTE_SEGMENTS result metadata, for Compact SeedQR's raw-byte payload,
 * and (b) only stops scanning once [decodeSeedQr] actually SUCCEEDS —
 * unlike a cosigner/descriptor QR, a failed decode here (garbage QR,
 * wrong content) shouldn't force the user back out to reopen the
 * scanner; they should be able to just try again while it's still open.
 */
@Composable
fun SeedQrScanScreen(
    allowScreenshots: Boolean,
    onBack: () -> Unit,
    onScanned: (words: List<String>) -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)

    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    MegaInfoScaffold(title = "Import via SeedQR", onBack = onBack) {
        if (hasCameraPermission) {
            SeedQrScannerContent(onScanned = onScanned)
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
            text = "MEGA uses the camera only on this screen to scan a Standard or Compact SeedQR code. No image is saved, shared, or sent anywhere.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MegaPrimaryButton(text = "Allow Camera", onClick = onRequestPermission)
    }
}

@Composable
private fun SeedQrScannerContent(onScanned: (words: List<String>) -> Unit) {
    var cameraError by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    // Guards against acting on a decode that arrives after this screen
    // already found a valid SeedQR and asked to leave — CameraX keeps
    // analyzing frames for the brief window before navigation actually
    // tears the camera down.
    var finished by remember { mutableStateOf(false) }

    fun handleDecoded(text: String, byteSegments: List<ByteArray>?) {
        if (finished) return
        when (val result = decodeSeedQr(text, byteSegments)) {
            is SeedQrResult.Success -> {
                finished = true
                scanError = null
                onScanned(result.words)
            }
            // A found-but-unrecognized QR is routine here (the user's
            // camera could easily land on some other code first) — show
            // why it didn't work and keep scanning, rather than forcing
            // them back out to reopen the scanner to try again.
            is SeedQrResult.Failure -> scanError = result.reason
        }
    }

    MegaCard {
        Text(
            text = "Point the camera at a Standard or Compact SeedQR code.",
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
            SeedQrCameraPreview(
                modifier = Modifier.fillMaxSize(),
                onDecoded = ::handleDecoded,
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
            text = "Nothing scanned is stored. Hold the code steady, well-lit, and about 15-20cm away rather than close up.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val currentError = cameraError ?: scanError
    if (currentError != null) {
        Text(
            text = currentError,
            style = MaterialTheme.typography.bodyMedium,
            color = MegaError,
        )
    }
}

@Composable
private fun SeedQrCameraPreview(
    modifier: Modifier = Modifier,
    onDecoded: (text: String, byteSegments: List<ByteArray>?) -> Unit,
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
        val analyzer = SeedQrAnalyzer(mainExecutor = mainExecutor, onDecoded = onDecoded)
        val listener = Runnable {
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // CameraX's own default analysis resolution can be as low
                    // as 640x480, which under-samples a Compact SeedQR's
                    // denser code (32 bytes of entropy needs a higher QR
                    // version than a 12-word Standard SeedQR's digit string)
                    // enough that zxing can locate the finder pattern — the
                    // preview still looks sharp, since Preview is a separate,
                    // higher-res stream — but never actually decode it.
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                            )
                            .build(),
                    )
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

private class SeedQrAnalyzer(
    private val mainExecutor: Executor,
    private val onDecoded: (text: String, byteSegments: List<ByteArray>?) -> Unit,
) : ImageAnalysis.Analyzer {
    // Dedupes identical CONSECUTIVE frames only, not a one-shot gate — the
    // composable (not this class) decides when a decode actually counts as
    // "done" (decodeSeedQr succeeding), since an unrecognized QR should
    // leave the scanner running for another attempt.
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
            val text = result?.text?.trim().orEmpty()
            if (text.isNotEmpty() && lastDecodedText.getAndSet(text) != text) {
                @Suppress("UNCHECKED_CAST")
                val byteSegments = result?.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<ByteArray>
                mainExecutor.execute { onDecoded(text, byteSegments) }
            }
        } catch (_: Exception) {
            // A single unreadable/malformed frame must never crash or tear down
            // the scanner — ReaderException (no QR in this frame) is the normal
            // case; anything broader here is defense-in-depth.
        } finally {
            reader.reset()
            image.close()
        }
    }

    private fun decode(source: com.google.zxing.LuminanceSource): com.google.zxing.Result? = try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
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
