package org.mega.entropy.ui.advancedmode

import android.util.Base64
import androidx.compose.foundation.layout.Column
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
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.FingerprintMatchStatus
import org.mega.entropycore.FingerprintTrustPolicy
import org.mega.entropycore.PsbtInputScriptKind
import org.mega.entropycore.PsbtInputSigningDiagnostic
import org.mega.entropycore.SignForCosignerResult
import org.mega.entropycore.diagnosePsbtSigning
import org.mega.entropycore.encodeBbqr
import org.mega.entropycore.extractFinalTransactionHex
import org.mega.entropycore.isPsbtFullyFinalized
import org.mega.entropycore.parsePsbt
import org.mega.entropycore.partialSigs
import org.mega.entropycore.signAndFinalizePsbt
import org.mega.entropycore.signPsbtForCosigner

private fun hexStringToByteArray(hex: String): ByteArray {
    return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/** The exact warning shown before MEGA will sign an input whose PSBT
 * fingerprint is the unrecorded (00000000) placeholder — see
 * FingerprintTrustPolicy.ALLOW_UNKNOWN_FINGERPRINT_WITH_KEY_MATCH. Never
 * describes the fingerprint itself as verified: only the derived public
 * key, path, script binding, UTXO, and signature are. */
private const val UNVERIFIED_FINGERPRINT_WARNING = "Master fingerprint is unknown in this PSBT. MEGA matched this " +
    "device's derived public key and path, but cannot independently verify the origin fingerprint."

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

    // Read-only, safe-to-display explanation of what happened per input —
    // computed independently of any signing attempt, so it's available
    // even before signing runs (used to decide whether the unverified-
    // fingerprint confirmation gate below applies) or if signing itself
    // throws. Never touches or derives a signature.
    val diagnostics: List<PsbtInputSigningDiagnostic>? = remember(psbtBytes, mnemonicWords, passphrase) {
        runCatching { diagnosePsbtSigning(psbtBytes, mnemonicWords, passphrase) }.getOrNull()
    }

    // Single-seed Advanced Mode ONLY (expectedCosignerFingerprint == null):
    // if the ONLY way any input gains a signature is by trusting a PSBT's
    // unrecorded (00000000) fingerprint placeholder plus a matching derived
    // pubkey, MEGA must not sign until the user has explicitly seen the
    // warning below and confirmed. The saved-vault/cosigner flow never
    // reaches this — it always signs with FingerprintTrustPolicy.STRICT,
    // for which this signal is irrelevant (see FingerprintTrustPolicy's doc
    // for why cosigner identity must stay strictly fingerprint-verified).
    val hasUnverifiedFingerprintOpportunity = expectedCosignerFingerprint == null &&
        diagnostics?.any { it.wouldAddSignatureWithUnverifiedFingerprint } == true
    var unverifiedFingerprintConfirmed by remember(psbtBytes) { mutableStateOf(false) }
    val awaitingUnverifiedFingerprintConfirmation = hasUnverifiedFingerprintOpportunity && !unverifiedFingerprintConfirmed

    // Calls signPsbtForCosigner AT MOST ONCE per distinct set of inputs — it
    // performs real cryptographic signing, so it must not run again on every
    // recomposition. mismatch and signResult below both read this single
    // cached attempt rather than invoking signPsbtForCosigner a second time.
    val cosignerAttempt: Result<SignForCosignerResult>? = if (expectedCosignerFingerprint != null) {
        remember(psbtBytes, mnemonicWords, passphrase, expectedCosignerFingerprint) {
            runCatching { signPsbtForCosigner(psbtBytes, expectedCosignerFingerprint, mnemonicWords, passphrase) }
        }
    } else {
        null
    }
    val mismatch = cosignerAttempt?.getOrNull() as? SignForCosignerResult.FingerprintMismatch

    // Null while awaiting the unverified-fingerprint confirmation above —
    // signing must not be attempted at all until then, not even with
    // FingerprintTrustPolicy.STRICT (a PSBT with additional, independently
    // signable inputs would otherwise partially sign without the user ever
    // seeing the warning for the ones that needed it).
    val signResult: Result<ByteArray>? = if (awaitingUnverifiedFingerprintConfirmation) {
        null
    } else {
        remember(psbtBytes, mnemonicWords, passphrase, expectedCosignerFingerprint, unverifiedFingerprintConfirmed) {
            if (expectedCosignerFingerprint == null) {
                val policy = if (unverifiedFingerprintConfirmed) {
                    FingerprintTrustPolicy.ALLOW_UNKNOWN_FINGERPRINT_WITH_KEY_MATCH
                } else {
                    FingerprintTrustPolicy.STRICT
                }
                runCatching { signAndFinalizePsbt(psbtBytes, mnemonicWords, passphrase, policy) }
            } else {
                val attempt = cosignerAttempt!!
                if (attempt.isFailure) {
                    Result.failure(attempt.exceptionOrNull() ?: IllegalStateException("This PSBT could not be signed by this device."))
                } else {
                    when (val outcome = attempt.getOrThrow()) {
                        is SignForCosignerResult.Signed -> Result.success(outcome.psbtBytes)
                        is SignForCosignerResult.FingerprintMismatch -> Result.failure(IllegalStateException("Cosigner fingerprint mismatch"))
                    }
                }
            }
        }
    }

    MegaInfoScaffold(title = "Sign PSBT", onBack = onBack) {
            if (mismatch != null) {
                MegaCard(title = "Wrong Cosigner") {
                    Text(
                        text = "This saved session's key does not match the selected cosigner (expected fingerprint ${mismatch.expectedFingerprint}, this session's key is ${mismatch.actualFingerprint}). Signing has been refused.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MegaError
                    )
                }
            } else if (awaitingUnverifiedFingerprintConfirmation) {
                // diagnostics is provably non-null here: hasUnverifiedFingerprintOpportunity
                // (which awaitingUnverifiedFingerprintConfirmation depends on) is only ever
                // true when diagnostics?.any {...} == true, which requires diagnostics != null.
                MegaCard(title = "Unverified Master Fingerprint") {
                    Text(
                        text = UNVERIFIED_FINGERPRINT_WARNING,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MegaError
                    )
                }
                MegaCard(title = "Diagnostic Detail") {
                    Column {
                        diagnostics.forEach { d ->
                            MegaMonoText(describeSigningDiagnostic(d))
                        }
                    }
                }
                MegaSecondaryButton(text = "Cancel", onClick = onBack)
                MegaPrimaryButton(text = "Sign Anyway", onClick = { unverifiedFingerprintConfirmed = true })
            } else if (signResult != null && signResult.isFailure) {
                MegaCard(title = "Could Not Sign PSBT") {
                    Text(
                        text = signResult.exceptionOrNull()?.message ?: "This PSBT could not be signed by this device.",
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
                // Provably non-null: the only case signResult is null is
                // awaitingUnverifiedFingerprintConfirmation, already handled above.
                val signedBytes = signResult!!.getOrThrow()
                val fullyFinalized = remember(signedBytes) { isPsbtFullyFinalized(signedBytes) }
                val beforeSignatureCount = remember(psbtBytes) { runCatching { parsePsbt(psbtBytes).inputs.sumOf { it.partialSigs().size } }.getOrDefault(0) }
                val afterSignatureCount = remember(signedBytes) { runCatching { parsePsbt(signedBytes).inputs.sumOf { it.partialSigs().size } }.getOrDefault(0) }

                if (fullyFinalized) {
                    val txHex = remember(signedBytes) { extractFinalTransactionHex(signedBytes) }

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
                        if (unverifiedFingerprintConfirmed) {
                            MegaCard(title = "Unverified Master Fingerprint") {
                                Text(
                                    text = UNVERIFIED_FINGERPRINT_WARNING,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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

                        MegaCard(title = "Broadcast QR") {
                            MegaAnimatedQrCode(
                                frames = remember(txHex) { encodeBbqr('T', hexStringToByteArray(txHex)) },
                                contentDescription = "Animated QR code of the signed transaction, to scan into a broadcasting wallet"
                            )
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
                    if (unverifiedFingerprintConfirmed) {
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
                                    getTextToCopy = { Base64.encodeToString(signedBytes, Base64.NO_WRAP) }
                                )
                            }
                        } else null
                    ) {
                        MegaAnimatedQrCode(
                            frames = remember(signedBytes) { encodeBbqr('P', signedBytes) },
                            contentDescription = "Animated QR code of the partially-signed PSBT, to scan into the next cosigner's wallet"
                        )
                    }

                    MegaPrimaryButton(text = "Done", onClick = onBack)
                }
            }
        }
}
