package org.mega.entropy.ui.advancedmode

import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.mega.entropy.ui.components.MegaAnimatedQrCode
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaCopyIconButton
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
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

/**
 * Turns one [PsbtInputSigningDiagnostic] into a single safe, human-readable
 * line explaining the first blocking condition found for that input — every
 * value it reads is on [PsbtInputSigningDiagnostic]'s own safe-fields list
 * (input index, witness_utxo/non_witness_utxo presence, script kind,
 * fingerprints, derivation paths, match booleans), never seed words, a
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
        d.keys.none { it.fingerprintMatchesLoadedKey } ->
            "$prefix: no derivation's master fingerprint matches this device's loaded key " +
                "(loaded ${d.loadedMasterFingerprintHex}; PSBT lists ${d.keys.map { it.fingerprintHex }.distinct().joinToString()})."
        d.keys.none { it.fingerprintMatchesLoadedKey && it.derivedPubkeyMatchesClaimed } ->
            "$prefix: fingerprint matches this device, but the key derived along the PSBT's stated path " +
                "does not match its claimed public key — check the seed, passphrase, and derivation path."
        d.wouldAddSignature -> "$prefix: matched this device's key — a signature should be added."
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

    val signResult = remember(psbtBytes, mnemonicWords, passphrase, expectedCosignerFingerprint) {
        if (expectedCosignerFingerprint == null) {
            runCatching { signAndFinalizePsbt(psbtBytes, mnemonicWords, passphrase) }
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

    // Read-only, safe-to-display explanation of what happened per input —
    // computed independently of signResult above, so it's available even
    // when signing itself threw. Never touches or derives a signature.
    val diagnostics: List<PsbtInputSigningDiagnostic>? = remember(psbtBytes, mnemonicWords, passphrase) {
        runCatching { diagnosePsbtSigning(psbtBytes, mnemonicWords, passphrase) }.getOrNull()
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
            } else if (signResult.isFailure) {
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
                val signedBytes = signResult.getOrThrow()
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
