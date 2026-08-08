package org.mega.entropy.ui.biascheck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaScreenPadding
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropy.ui.theme.MegaSuccess
import org.mega.entropycore.RejectionResult

/**
 * Spec section 9, "Final rejection test screen". Never shows the mnemonic —
 * a Rejected result has no entropy/checksum/words to show in the first
 * place, since :entropy-core stops computing at rejection.
 */
@Composable
fun BiasCheckScreen(
    rejectionResult: RejectionResult?,
    onContinueToEntropy: () -> Unit,
    onStartNewSequence: () -> Unit,
) {
    SecureScreen()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(MegaScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Bias Check", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "BIP39 needs exactly 256 bits, but 100 dice rolls carry slightly " +
                "more raw information than that (~258.5 bits). Rejection " +
                "sampling discards the small slice of sequences that would " +
                "otherwise bias the result — see How It Works for why.",
            style = MaterialTheme.typography.bodyMedium,
        )

        MegaCard(title = "The comparison") {
            if (rejectionResult != null) {
                MegaMonoText("X            = ${rejectionResult.x}")
                MegaMonoText("6^100        = ${rejectionResult.sixPow100}")
                MegaMonoText("2^256        = ${rejectionResult.twoPow256}")
                MegaMonoText("T = 5×2^256  = ${rejectionResult.thresholdT}")
                val isAccepted = rejectionResult is RejectionResult.Accepted
                MegaMonoText("X < T        = $isAccepted")
            } else {
                Text("Calculating…", style = MaterialTheme.typography.bodyMedium)
            }
        }

        when (rejectionResult) {
            is RejectionResult.Accepted -> {
                ResultBanner(
                    passed = true,
                    headline = "PASS — Your 100-roll sequence falls inside the unbiased range.",
                )
                Text(
                    "Next, E = X mod 2^256 extracts exactly 256 bits from X.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                MegaPrimaryButton(text = "Continue", onClick = onContinueToEntropy)
            }
            is RejectionResult.Rejected -> {
                ResultBanner(
                    passed = false,
                    headline = "REJECT — Start a new 100-roll sequence",
                )
                Text(
                    "Nothing is wrong with your dice. This rejection is " +
                        "necessary to keep the result uniformly random — " +
                        "about 1 in 8 valid sequences land here by design.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                MegaPrimaryButton(text = "Start New Sequence", onClick = onStartNewSequence)
            }
            null -> Unit
        }
    }
}

@Composable
private fun ResultBanner(passed: Boolean, headline: String) {
    val color = if (passed) MegaSuccess else MegaError
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            imageVector = if (passed) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = if (passed) "Accepted" else "Rejected",
            tint = color,
        )
        Text(headline, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
    }
}
