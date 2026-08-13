package org.mega.entropy.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay

@Composable
fun MegaAnimatedQrCode(
    frames: List<String>,
    contentDescription: String = "QR code",
    modifier: Modifier = Modifier,
) {
    require(frames.isNotEmpty())
    var index by remember(frames) { mutableStateOf(0) }

    if (frames.size > 1) {
        LaunchedEffect(frames) {
            while (true) {
                delay(400)
                index = (index + 1) % frames.size
            }
        }
    }

    Column(modifier = modifier) {
        MegaQrCode(frames[index], contentDescription, Modifier.fillMaxWidth())
        if (frames.size > 1) {
            Text(
                text = "Frame ${index + 1} of ${frames.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}
