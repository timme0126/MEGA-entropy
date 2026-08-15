package org.mega.entropy.ui.advancedmode

import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.mega.entropy.ui.components.MegaAnimatedQrCode
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaCopyIconButton
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.FingerprintMatchStatus
import org.mega.entropycore.PsbtInputScriptKind
import org.mega.entropycore.PsbtInputSigningDiagnostic
import org.mega.entropycore.diagnosePsbtSigning
import org.mega.entropycore.encodeBbqr
import org.mega.entropycore.extractFinalTransactionHex
import org.mega.entropycore.isPsbtFullyFinalized
import org.mega.entropycore.parsePsbt
import org.mega.entropycore.partialSigs
import org.mega.entropycore.bip32Derivations

private fun hexStringToByteArray(hex: String): ByteArray {
    return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/** Informational explanation shown after signing when the PSBT carries the
 * unrecorded (00000000) origin-fingerprint placeholder. The fingerprint
 * itself is not treated as verified; the signing checks happen internally. */
private const val UNVERIFIED_FINGERPRINT_WARNING = "This PSBT did not record a master fingerprint (00000000). MEGA independently matched the derived public key, path, UTXO, and signature."

/**
 * Turns one [PsbtInputSigningDiagnostic] into a single safe, human-readable
 * line explaining the first blocking condition found for that input — every
 * value it reads is on [PsbtInputSigningDiagnostic]'s own safe-fields list
 * (input index, witness_utxo/non_witness_utxo presence, script kind,
 * fingerprints, derivation paths, match status), never seed words, a
 * passphrase, a private key, a signature, or the PSBT's full contents.
 */
private fun describeSigningDiagnostic(d: PsbtInputSigningDiagnostic): String {
    val prefix = "Input ${d.inputIndex}"
    return when {
        d.alreadyFinalized -> "$prefix: already finalized."
        !d.hasWitnessUtxo && !d.hasNonWitnessUtxo ->
            "$prefix: has neither witness_utxo nor non_witness_utxo, so MEGA cannot determine what it spends."
        !d.utxoResolved ->
            "$prefix: UTXO data is present but could not be resolved (malformed, or witness_utxo/non_witness_utxo disagree)."
        d.scriptKind == PsbtInputScriptKind.UNRECOGNIZED ->
            "$prefix: unrecognized script type — MEGA only signs native P2WPKH and bare P2WSH multisig."
        !d.utxoMatchesDeclaredScript ->
            "$prefix: the spent output's script does not match what this input claims to sign."
        !d.sighashSupported ->
            "$prefix: requests a sighash type other than SIGHASH_ALL, which MEGA refuses to sign."
        d.keys.isEmpty() ->
            "$prefix: the PSBT carries no BIP32 derivation info for this input."
        d.keys.none { it.matchStatus != FingerprintMatchStatus.MISMATCH && it.matchStatus != FingerprintMatchStatus.MALFORMED } ->
            "$prefix: no derivation matches this device's key " +
                "(loaded ${d.loadedMasterFingerprintHex}; PSBT lists ${d.keys.map { it.fingerprintHex }.distinct().joinToString()})."
        d.wouldAddSignature -> "$prefix: matched this device's key by a verified fingerprint — a signature should be added."
        d.wouldAddSignatureWithUnverifiedFingerprint ->
            "$prefix: fingerprint is the unrecorded (00000000) placeholder, but this device's derived public key " +
                "and path match exactly — signs only after explicit confirmation."
        d.existingPartialSigCount > 0 -> "$prefix: already has a signature from this device's key."
        else -> "$prefix: not signed for an unclassified reason."
    }
}

@Composable
fun PsbtSignResultScreen(
    psbtBytes: ByteArray,
    mnemonicWords: List<String>,
    passphrase: String,
    allowScreenshots: Boolean,
    allowSeedCopy: Boolean,
    expectedCosignerFingerprint: String? = null,
    onBack: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)

    // Start signing immediately. entropy-core still performs every required
    // fingerprint, derivation, UTXO-binding, and signature check internally.
    // Diagnostics are a failure explanation only; they never gate signing.
    val signOutcomeState = producePsbtAsync(psbtBytes, mnemonicWords, passphrase, expectedCosignerFingerprint) {
        attemptPsbtSign(psbtBytes, mnemonicWords, passphrase, expectedCosignerFingerprint)
    }
    val signedBytes = ((signOutcomeState as? PsbtAsyncState.Success<PsbtSignOutcome>)?.value as? PsbtSignOutcome.Signed)?.psbtBytes
    val failedOutcome = ((signOutcomeState as? PsbtAsyncState.Success<PsbtSignOutcome>)?.value as? PsbtSignOutcome.Failed)
    val diagnosticsState = if (failedOutcome != null) {
        producePsbtAsync(psbtBytes, mnemonicWords, passphrase, "failure-diagnostics") {
            diagnosePsbtSigning(psbtBytes, mnemonicWords, passphrase)
        }
    } else null
    val diagnostics = (diagnosticsState as? PsbtAsyncState.Success<List<PsbtInputSigningDiagnostic>>)?.value
    val hasUnverifiedFingerprint = remember(signedBytes) {
        signedBytes?.let { bytes ->
            runCatching {
                parsePsbt(bytes).inputs.any { input ->
                    input.bip32Derivations().any { derivation -> derivation.masterFingerprint.all { it == 0.toByte() } }
                }
            }.getOrDefault(false)
        } ?: false
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingSaveBytes by remember(psbtBytes) { mutableStateOf<ByteArray?>(null) }
    val saveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destination ->
        val bytes = pendingSaveBytes
        if (destination != null && bytes != null) {
            val saved = runCatching {
                context.contentResolver.openOutputStream(destination)?.use { output -> output.write(bytes) } != null
            }.getOrDefault(false)
            if (!saved) android.widget.Toast.makeText(context, "Could not save the signed PSBT.", android.widget.Toast.LENGTH_LONG).show()
        }
        pendingSaveBytes = null
    }

    MegaInfoScaffold(
        title = "Sign PSBT",
        onBack = onBack,
        actions = {
            if (signedBytes != null) {
                androidx.compose.material3.IconButton(onClick = {
                    pendingSaveBytes = signedBytes
                    saveLauncher.launch("mega-signed.psbt")
                }) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Filled.Save,
                        contentDescription = "Save signed PSBT"
                    )
                }
            }
        }
    ) {
        when {
            signOutcomeState is PsbtAsyncState.Loading -> {
                MegaCard(title = "Preparing PSBT") {
                    Column {
                        CircularProgressIndicator()
                        Text(text = "Signing…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            signOutcomeState is PsbtAsyncState.Failed -> {
                MegaCard(title = "Could Not Sign PSBT") {
                    Text(text = signOutcomeState.error.message ?: "This PSBT could not be processed by this device.", style = MaterialTheme.typography.bodyMedium, color = MegaError)
                }
            }
            else -> {
                val outcome = (signOutcomeState as PsbtAsyncState.Success<PsbtSignOutcome>).value
                when (outcome) {
                    is PsbtSignOutcome.CosignerMismatch -> {
                        MegaCard(title = "Wrong Cosigner") {
                            Text(text = "This saved session key does not match the selected cosigner. Signing has been refused.", style = MaterialTheme.typography.bodyMedium, color = MegaError)
                        }
                    }
                    is PsbtSignOutcome.Failed -> {
                        MegaCard(title = "Could Not Sign PSBT") {
                            Text(text = outcome.message, style = MaterialTheme.typography.bodyMedium, color = MegaError)
                        }
                        if (diagnosticsState is PsbtAsyncState.Loading) CircularProgressIndicator()
                        if (diagnostics != null) {
                            MegaCard(title = "Diagnostic Detail") {
                                Column { diagnostics.forEach { d -> MegaMonoText(describeSigningDiagnostic(d)) } }
                            }
                        }
                    }
                    is PsbtSignOutcome.Signed -> {
                        val signedPsbtBytes = outcome.psbtBytes
                val fullyFinalized = remember(signedPsbtBytes) { isPsbtFullyFinalized(signedPsbtBytes) }
                val beforeSignatureCount = remember(psbtBytes) { runCatching { parsePsbt(psbtBytes).inputs.sumOf { it.partialSigs().size } }.getOrDefault(0) }
                val afterSignatureCount = remember(signedPsbtBytes) { runCatching { parsePsbt(signedPsbtBytes).inputs.sumOf { it.partialSigs().size } }.getOrDefault(0) }

                if (fullyFinalized) {
                    val txHex = remember(signedPsbtBytes) { extractFinalTransactionHex(signedPsbtBytes) }

                    if (txHex == null) {
                        MegaCard {
                            Text(
                                text = "Could not extract the final transaction from this PSBT.",
                                color = MegaError,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        MegaCard(title = "Transaction Fully Signed") {
                            Text(
                                text = "This is the final, signed transaction. Broadcasting it will move funds — only proceed if you're sure this is what you intend to send.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MegaError
                            )
                        }
                        if (hasUnverifiedFingerprint) {
                            MegaCard(title = "Unverified Master Fingerprint") {
                                Text(
                                    text = UNVERIFIED_FINGERPRINT_WARNING,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }


                        MegaCard(title = "Broadcast QR") {
                            MegaAnimatedQrCode(
                                frames = remember(txHex) { encodeBbqr('T', hexStringToByteArray(txHex)) },
                                contentDescription = "Animated QR code of the signed transaction, to scan into a broadcasting wallet"
                            )
                        }

                        MegaCard(
                            title = "Transaction (hex)",
                            trailingAction = if (allowSeedCopy) {
                                {
                                    MegaCopyIconButton(
                                        contentDescription = "Copy transaction hex",
                                        getTextToCopy = { txHex }
                                    )
                                }
                            } else null
                        ) {
                            Column {
                                txHex.chunked(32).forEach { line ->
                                    MegaMonoText(line)
                                }
                            }
                        }

                        MegaPrimaryButton(text = "Done", onClick = onBack)
                    }
                } else if (afterSignatureCount <= beforeSignatureCount) {
                    MegaCard(title = "Could Not Sign PSBT") {
                        Text(
                            text = "MEGA did not add a valid signature to this PSBT. Check that the Sparrow wallet uses the same seed, passphrase, derivation path, and fingerprint, and that each input includes valid UTXO data.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MegaError
                        )
                    }
                    if (diagnostics != null) {
                        MegaCard(title = "Diagnostic Detail") {
                            Column {
                                diagnostics.forEach { d ->
                                    MegaMonoText(describeSigningDiagnostic(d))
                                }
                            }
                        }
                    }
                } else {
                    MegaCard(title = "Signed — More Cosigners Needed") {
                        Text(
                            text = "This device's signature has been added, but the transaction still needs more signatures before it can be broadcast. Export the updated PSBT below and hand it to the next cosigner.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (hasUnverifiedFingerprint) {
                        MegaCard(title = "Unverified Master Fingerprint") {
                            Text(
                                text = UNVERIFIED_FINGERPRINT_WARNING,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    MegaCard(
                        title = "Updated PSBT",
                        trailingAction = if (allowSeedCopy) {
                            {
                                MegaCopyIconButton(
                                    contentDescription = "Copy PSBT as base64",
                                    getTextToCopy = { Base64.encodeToString(signedPsbtBytes, Base64.NO_WRAP) }
                                )
                            }
                        } else null
                    ) {
                        MegaAnimatedQrCode(
                            frames = remember(signedPsbtBytes) { encodeBbqr('P', signedPsbtBytes) },
                            contentDescription = "Animated QR code of the partially-signed PSBT, to scan into the next cosigner's wallet"
                        )
                    }


                    MegaPrimaryButton(text = "Done", onClick = onBack)
                }
            }
        }
}
}
}
}
