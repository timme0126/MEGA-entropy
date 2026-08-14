package org.mega.entropy.ui.advancedmode

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import org.mega.entropycore.MultisigCosignerOrigin
import org.mega.entropycore.PsbtSummary
import org.mega.entropycore.PsbtThresholdInfo
import org.mega.entropycore.WalletNetwork
import org.mega.entropycore.computePsbtSummary
import org.mega.entropycore.parsePsbt
import org.mega.entropycore.verifyVaultChangeOutput

@Composable
fun PsbtReviewScreen(
    psbtBytes: ByteArray,
    knownNetwork: WalletNetwork?,
    deviceMasterFingerprint: String?,
    allowScreenshots: Boolean,
    // When reviewing a spend from a KNOWN saved vault, its threshold and
    // cosigner origins — enabling cryptographic verification of change
    // outputs (see verifyVaultChangeOutput) instead of trusting the PSBT's
    // own claim about which output is change. Null for the single-seed flow.
    vaultThreshold: Int? = null,
    vaultCosigners: List<MultisigCosignerOrigin>? = null,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)

    // Both computations involve BIP32 derivation, expensive enough to run
    // off the Compose main thread — see producePsbtAsync's own doc.
    val summaryState = producePsbtAsync(psbtBytes, knownNetwork, deviceMasterFingerprint) {
        runCatching { computePsbtSummary(psbtBytes, knownNetwork, deviceMasterFingerprint) }
    }
    // Parse once more for vault change verification — only when vault
    // context was supplied (the saved-vault flow). Errors here are already
    // covered by summaryResult's own failure display, so default to none.
    val verifiedChangeState = producePsbtAsync(psbtBytes, vaultThreshold, vaultCosigners, knownNetwork) {
        val threshold = vaultThreshold
        val cosigners = vaultCosigners
        if (threshold == null || cosigners == null || knownNetwork == null) return@producePsbtAsync emptySet()
        runCatching {
            val psbt = parsePsbt(psbtBytes)
            psbt.unsignedTx.outputs.mapIndexedNotNull { index, txOut ->
                if (verifyVaultChangeOutput(txOut.scriptPubKey, psbt.outputs[index], threshold, cosigners, knownNetwork)) {
                    index
                } else {
                    null
                }
            }.toSet()
        }.getOrDefault(emptySet())
    }

    val summaryResult: Result<PsbtSummary>? = when (summaryState) {
        PsbtAsyncState.Loading -> null
        is PsbtAsyncState.Success -> summaryState.value
        is PsbtAsyncState.Failed -> Result.failure(summaryState.error)
    }

    MegaInfoScaffold(title = "Review Transaction", onBack = onCancel) {
        if (summaryResult == null) {
            CircularProgressIndicator()
        } else if (summaryResult.isFailure) {
            MegaCard(title = "Could Not Parse PSBT") {
                Text(
                    text = summaryResult.exceptionOrNull()?.message ?: "This PSBT could not be read.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MegaError
                )
            }
        } else {
            val summary = summaryResult.getOrThrow()
            val verifiedChange: Set<Int> = (verifiedChangeState as? PsbtAsyncState.Success)?.value ?: emptySet()
            // Conditions under which "Confirm and Sign" must not be offered:
            // the signer would refuse (unsupported sighash) or the
            // transaction is invalid on its face (outputs exceed inputs).
            val blockingReasons = mutableListOf<String>()
            if (summary.hasUnsupportedSighashType) {
                val badInputs = summary.inputs.mapIndexedNotNull { index, input ->
                    val sighash = input.sighashType
                    if (sighash != null && sighash != 1L) {
                        "input ${index + 1} (0x${sighash.toString(16)})"
                    } else {
                        null
                    }
                }
                blockingReasons += "This transaction requests an unsupported sighash type on ${badInputs.joinToString(", ")}. " +
                    "MEGA only signs SIGHASH_ALL, where the signature commits to every output shown here. " +
                    "Signing this would authorize something different from what you see."
            }
            if (summary.feeIsNegative) {
                blockingReasons += "This transaction's outputs exceed its inputs (a negative fee) — it is invalid and cannot be broadcast."
            }

            fun formatSats(sats: Long): String = "%,d sats".format(sats)

            MegaCard(title = "Before You Sign") {
                Text(
                    text = "Confirming below will sign this transaction with this device's key. Once broadcast, this authorizes spending the funds described here. Review every detail carefully before continuing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MegaError,
                    fontWeight = FontWeight.SemiBold
                )
            }

            blockingReasons.forEach { reason ->
                MegaCard(title = "Cannot Sign This Transaction") {
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MegaError,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Non-blocking cautions — the user can still decide, but they
            // must not scroll past unnoticed.
            val feeSats = summary.feeSats
            val totalIn = summary.totalInputSats
            if (blockingReasons.isEmpty() && feeSats != null && totalIn != null && totalIn > 0 &&
                feeSats > totalIn / 10
            ) {
                MegaCard(title = "High Fee Warning") {
                    Text(
                        text = "The fee (${formatSats(feeSats)}) is more than 10% of the total input amount. " +
                            "Double-check the outputs and fee before signing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MegaError
                    )
                }
            }

            MegaCard(title = "Summary") {
                SummaryLine("Network", when (summary.network) {
                    WalletNetwork.MAINNET -> if (summary.networkWasInferred) "Mainnet (from key paths)" else "Mainnet"
                    WalletNetwork.TESTNET -> if (summary.networkWasInferred) "Testnet (from key paths)" else "Testnet"
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

            // Single-seed flow only (a saved vault always identifies its
            // cosigners by verified fingerprint — see FingerprintTrustPolicy's
            // doc for why that must stay strict). This is purely informational
            // here: it does NOT change "This device can sign" above, which
            // reflects only a verified fingerprint match. The actual
            // derived-pubkey check (the one that decides whether signing can
            // proceed) only happens, with its own explicit warning and
            // confirmation step, on the signing screen next.
            if (vaultCosigners == null && summary.hasUnverifiedOriginFingerprint) {
                MegaCard(title = "Unrecorded Origin Fingerprint") {
                    Text(
                        text = "One or more inputs have no recorded origin fingerprint (00000000) in this PSBT. " +
                            "MEGA will attempt to match them to this device's derived keys during signing, and will " +
                            "ask for explicit confirmation if a match is found — the fingerprint itself can never be verified.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    if (index in verifiedChange) {
                        Text(
                            text = "Change back to this vault (verified against its keys)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (output.isLikelyChange) {
                        Text(
                            text = if (vaultCosigners != null) {
                                "References this wallet's fingerprint — could NOT be verified as change"
                            } else {
                                "Possible change (unverified)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MegaError
                        )
                    }
                    if (index < summary.outputs.size - 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            MegaSecondaryButton(text = "Cancel", onClick = onCancel)
            if (blockingReasons.isEmpty()) {
                MegaPrimaryButton(text = "Confirm and Sign", onClick = onConfirm)
            }
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
