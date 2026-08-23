package org.mega.entropy.ui.advancedmode.backupshares

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaCopyIconButton
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError

/**
 * Shows exactly ONE backup share at a time — "share 2 of 5" — with an
 * explicit "I've recorded this share" confirmation gating the button that
 * advances to the next one. There is deliberately no way back to a
 * previous share once advanced past (the top bar's back action, like
 * Structure a Transaction's Cancel, abandons the whole flow rather than
 * stepping backward): the entire point of splitting the seed is that no
 * single place ever holds more than one share, and a "go back" affordance
 * would make it trivial to end up with the full set visible again in one
 * sitting.
 *
 * No QR code here, unlike MEGA's other public-data screens (xpubs,
 * descriptors) — see MegaQrCode's own doc: this app deliberately keeps
 * the seed/mnemonic off every QR path, and a backup share is exactly as
 * sensitive (any [threshold] of them recovers the seed). Copy-to-
 * clipboard plus on-screen text (to transcribe by hand) are the only ways
 * to move a share off this screen.
 */
@Composable
fun BackupShareRevealScreen(
    viewModel: BackupSharesViewModel,
    allowScreenshots: Boolean,
    onBack: () -> Unit,
    onAllSharesRevealed: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.step) {
        if (uiState.step == BackupSharesStep.DONE) {
            onAllSharesRevealed()
        }
    }

    val shareText = uiState.currentShareText
    MegaInfoScaffold(title = "Share ${uiState.currentShareNumber} of ${uiState.totalShares}", onBack = onBack) {
        MegaCard(title = "Write This Down Before Continuing") {
            Text(
                "Record this exact text somewhere separate from your other shares and from the seed " +
                    "words themselves. You will not be able to see it again after continuing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MegaError,
            )
        }

        if (shareText != null) {
            MegaCard(
                title = "Share ${uiState.currentShareNumber} of ${uiState.totalShares}",
                trailingAction = {
                    MegaCopyIconButton(contentDescription = "Copy this share") { shareText }
                },
            ) {
                MegaMonoText(shareText)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = uiState.currentShareRecorded,
                onCheckedChange = { if (it) viewModel.confirmCurrentShareRecorded() },
            )
            Text("I've recorded share ${uiState.currentShareNumber}.", style = MaterialTheme.typography.bodyMedium)
        }

        val isLastShare = uiState.currentShareNumber == uiState.totalShares
        MegaPrimaryButton(
            text = if (isLastShare) "Finish" else "Next Share",
            enabled = uiState.currentShareRecorded,
            onClick = { viewModel.advanceToNextShare() },
        )
    }
}
