package org.mega.entropy.ui.loading

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.mega.entropy.ui.components.MegaLogo
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaScreenPadding
import org.mega.entropy.ui.theme.MegaSuccess

private val LOADING_ITEMS = listOf(
    "No Sign-Up",
    "100% Offline",
    "Zero Device Entropy",
    "No Random Number Generator (RNG)",
    "No Telemetry",
    "No Accounts",
    "No Data Collection",
)

private const val REVEAL_DELAY_MS = 260L

/**
 * Shown once per cold app launch, before Welcome. Purely presentational —
 * nothing here actually checks anything at runtime; it exists to put
 * MEGA's core promises in front of the user immediately, a job the old
 * badge row on Welcome used to do less prominently. Sequentially reveals
 * each item, then waits for a deliberate tap on Enter rather than
 * auto-advancing, so it never silently rushes past what it just showed.
 */
@Composable
fun LoadingScreen(onEnter: () -> Unit) {
    var revealedCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        for (index in LOADING_ITEMS.indices) {
            delay(REVEAL_DELAY_MS)
            revealedCount = index + 1
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(MegaScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MegaLogo()

        Spacer(modifier = Modifier.height(40.dp))

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            LOADING_ITEMS.forEachIndexed { index, label ->
                AnimatedVisibility(
                    visible = index < revealedCount,
                    enter = fadeIn() + slideInVertically { it / 2 },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "✓",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MegaSuccess,
                        )
                        Text(text = label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        AnimatedVisibility(visible = revealedCount == LOADING_ITEMS.size, enter = fadeIn()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MegaPrimaryButton(text = "Enter", onClick = onEnter)
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Optimized for GrapheneOS",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
