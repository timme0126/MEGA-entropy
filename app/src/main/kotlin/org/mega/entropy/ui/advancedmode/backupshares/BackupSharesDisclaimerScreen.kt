package org.mega.entropy.ui.advancedmode.backupshares

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError

/**
 * Mandatory acknowledgment shown before "Create Backup Shares" splits this
 * session's entropy — the same "agree, don't just read" bar as
 * StructureTransactionDisclaimerScreen. Backup shares are an ADDITIONAL,
 * independent way to recover this exact seed (see EntropyBackupShares.kt
 * in entropy-core for the "entropy-rooted" design this implements), not a
 * replacement for writing down the seed words themselves — anyone who
 * later collects `threshold` of the shares this flow produces can recover
 * the seed just as completely as reading the words directly, so the
 * shares need the same physical security discipline, just split across
 * separate locations instead of one.
 */
@Composable
fun BackupSharesDisclaimerScreen(
    allowScreenshots: Boolean,
    onBack: () -> Unit,
    onContinueToSetup: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)
    var acknowledged by remember { mutableStateOf(false) }

    MegaInfoScaffold(title = "Before You Create Shares", onBack = onBack) {
        MegaCard(title = "Shares Recover The Same Seed") {
            Text(
                "Backup shares are a second, independent way to recover this exact seed — not a " +
                    "weaker copy of it. Anyone who collects enough shares (the threshold you choose " +
                    "next) can recover the seed completely, exactly as if they had the seed words " +
                    "themselves.",
                style = MaterialTheme.typography.bodyMedium,
                color = MegaError,
            )
        }

        MegaCard(title = "Store Shares Separately") {
            Text(
                "Shares only protect against loss if they are kept in different physical locations. " +
                    "MEGA shows each share once, one at a time, and never stores them or displays more " +
                    "than one on screen at once — recording every share you'll need, in a place " +
                    "distinct from the others and from the seed words themselves, is your " +
                    "responsibility once this screen shows it to you.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "There is no way to review a share again after you move past it in this flow. If you " +
                    "lose one before finishing, cancel and start over.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
            Text(
                "I understand each share can help recover this seed, and I'll store them separately.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (acknowledged) {
            MegaPrimaryButton(text = "Continue", onClick = onContinueToSetup)
        }
        MegaSecondaryButton(text = "Cancel", onClick = onBack)
    }
}
