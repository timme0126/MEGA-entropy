package org.mega.entropy.ui.advancedmode.backupshares

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.SecureScreen

/**
 * Picks the m-of-n split before any share is generated: [threshold] shares
 * (out of [totalShares] produced) will be enough to recover the seed.
 * Splitting only happens once "Generate Shares" is tapped — nothing here
 * touches entropy yet, this screen is pure form input.
 */
@Composable
fun BackupSharesSetupScreen(
    viewModel: BackupSharesViewModel,
    allowScreenshots: Boolean,
    onBack: () -> Unit,
    onContinueToReveal: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)
    val uiState by viewModel.uiState.collectAsState()

    MegaInfoScaffold(title = "Backup Shares Setup", onBack = onBack) {
        MegaCard(title = "How Many Shares Are Needed To Recover?") {
            Text(
                "This many shares, out of the total you create, will be enough to recover the seed.",
                style = MaterialTheme.typography.bodyMedium,
            )
            NumberStepperRow(
                label = "Threshold",
                value = uiState.threshold,
                onDecrement = { viewModel.setThreshold(uiState.threshold - 1) },
                onIncrement = { viewModel.setThreshold(uiState.threshold + 1) },
                minValue = 2,
                maxValue = uiState.totalShares,
            )
        }

        MegaCard(title = "How Many Shares In Total?") {
            Text(
                "Create this many shares. Store each one somewhere different — losing all but the " +
                    "threshold above still lets you recover the seed.",
                style = MaterialTheme.typography.bodyMedium,
            )
            NumberStepperRow(
                label = "Total Shares",
                value = uiState.totalShares,
                onDecrement = { viewModel.setTotalShares(uiState.totalShares - 1) },
                onIncrement = { viewModel.setTotalShares(uiState.totalShares + 1) },
                minValue = uiState.threshold,
                maxValue = 255,
            )
        }

        MegaCard {
            Text(
                "\"${uiState.threshold} of ${uiState.totalShares}\": any ${uiState.threshold} of the " +
                    "${uiState.totalShares} shares you're about to create will recover this seed. Fewer " +
                    "than that recovers nothing.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        uiState.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        MegaPrimaryButton(text = "Generate Shares", onClick = onContinueToReveal)
    }
}

@Composable
private fun NumberStepperRow(
    label: String,
    value: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    minValue: Int,
    maxValue: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = androidx.compose.ui.Modifier.width(96.dp))
        IconButton(onClick = onDecrement, enabled = value > minValue) {
            Icon(Icons.Filled.Remove, contentDescription = "Decrease $label")
        }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = androidx.compose.ui.Modifier.width(40.dp),
        )
        IconButton(onClick = onIncrement, enabled = value < maxValue) {
            Icon(Icons.Filled.Add, contentDescription = "Increase $label")
        }
    }
}
