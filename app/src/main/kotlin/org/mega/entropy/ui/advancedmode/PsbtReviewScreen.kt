package org.mega.entropy.ui.advancedmode

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.PsbtThresholdInfo
import org.mega.entropycore.WalletNetwork
import org.mega.entropycore.computePsbtSummary

@Composable
fun PsbtReviewScreen(
    psbtBytes: ByteArray,
    knownNetwork: WalletNetwork?,
    deviceMasterFingerprint: String?,
    allowScreenshots: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)

    val summaryResult = remember(psbtBytes, knownNetwork, deviceMasterFingerprint) {
        runCatching { computePsbtSummary(psbtBytes, knownNetwork, deviceMasterFingerprint) }
    }

    MegaInfoScaffold(title = "Review Transaction", onBack = onCancel) {
        if (summaryResult.isFailure) {
            MegaCard(title = "Could Not Parse PSBT") {
                Text(
                    text = summaryResult.exceptionOrNull()?.message ?: "This PSBT could not be read.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MegaError
                )
            }
        } else {
            val summary = summaryResult.getOrThrow()

            fun formatSats(sats: Long): String = "%,d sats".format(sats)

            MegaCard(title = "Before You Sign") {
                Text(
                    text = "Confirming below will sign this transaction with this device's key. Once broadcast, this authorizes spending the funds described here. Review every detail carefully before continuing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MegaError,
                    fontWeight = FontWeight.SemiBold
                )
            }

            MegaCard(title = "Summary") {
                SummaryLine("Network", when (summary.network) {
                    WalletNetwork.MAINNET -> "Mainnet"
                    WalletNetwork.TESTNET -> "Testnet"
                    null -> null
                })
                SummaryLine("Inputs", summary.inputCount.toString())
                SummaryLine("Outputs", summary.outputCount.toString())
                SummaryLine("Total input amount", summary.totalInputSats?.let { formatSats(it) })
                SummaryLine("Total output amount", formatSats(summary.totalOutputSats))
                SummaryLine("Fee", summary.feeSats?.let { formatSats(it) })
                SummaryLine("Estimated fee rate", summary.estimatedFeeRateSatsPerVByte?.let { "%.1f sats/vB (estimated)".format(it) })
                SummaryLine(
                    "Already partially signed",
                    if (summary.isAlreadyPartiallySigned) {
                        "Yes (${summary.existingSignatureCount} existing signature${if (summary.existingSignatureCount == 1) "" else "s"})"
                    } else {
                        "No"
                    }
                )
                SummaryLine("Required signatures", when (val t = summary.requiredThreshold) {
                    is PsbtThresholdInfo.Known -> "${t.threshold} of ${t.cosignerCount}"
                    PsbtThresholdInfo.Varies -> "Varies across inputs"
                    PsbtThresholdInfo.Unknown -> null
                })
                SummaryLine("This device can sign", when (summary.deviceCanSignAnyInput) {
                    true -> "Yes"
                    false -> "No — this device cannot sign any input in this transaction"
                    null -> null
                })
                SummaryLine("If you sign", when (summary.willFinalizeIfSigned) {
                    true -> "This transaction will be fully signed and ready to broadcast"
                    false -> "This transaction will still need more signatures afterward"
                    null -> null
                })
            }

            if (summary.deviceCanSignAnyInput == false) {
                MegaCard {
                    Text(
                        text = "This device does not appear able to sign any input in this transaction. Signing may have no effect.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MegaError
                    )
                }
            }

            MegaCard(title = "Outputs (${summary.outputCount})") {
                summary.outputs.forEachIndexed { index, output ->
                    Text(
                        text = "Output ${index + 1}: ${formatSats(output.amountSats)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    MegaMonoText(text = output.address ?: output.scriptPubKeyHex)
                    if (output.isLikelyChange) {
                        Text(
                            text = "Likely change (pays back to this wallet)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (index < summary.outputs.size - 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            MegaSecondaryButton(text = "Cancel", onClick = onCancel)
            MegaPrimaryButton(text = "Confirm and Sign", onClick = onConfirm)
        }
    }
}

/** One "Label: value" summary line — [value] of `null` renders as "Unknown"
 * in a visually de-emphasized color, so an honestly-undeterminable field
 * reads clearly as "we don't know" rather than blending in with a real
 * answer. */
@Composable
private fun SummaryLine(label: String, value: String?) {
    Text(
        text = "$label: ${value ?: "Unknown"}",
        style = MaterialTheme.typography.bodyMedium,
        color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
    )
}
