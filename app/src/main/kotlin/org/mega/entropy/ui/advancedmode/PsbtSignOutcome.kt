package org.mega.entropy.ui.advancedmode

import org.mega.entropycore.FingerprintTrustPolicy
import org.mega.entropycore.SignForCosignerResult
import org.mega.entropycore.signAndFinalizePsbt
import org.mega.entropycore.signPsbtForCosigner

/**
 * Outcome of [attemptPsbtSign]. Pure data, no Compose dependency — directly
 * unit-testable from a plain JVM test.
 */
internal sealed class PsbtSignOutcome {
    data class Signed(val psbtBytes: ByteArray) : PsbtSignOutcome()
    data class CosignerMismatch(val expectedFingerprint: String, val actualFingerprint: String) : PsbtSignOutcome()
    data class Failed(val message: String) : PsbtSignOutcome()
}

/**
 * Pure signing attempt, extracted out of [PsbtSignResultScreen] so it can be
 * unit-tested without Compose. The saved-vault/cosigner flow
 * ([expectedCosignerFingerprint] non-null) always signs with
 * [FingerprintTrustPolicy.STRICT]. Single-seed signing uses the liberal
 * unknown-fingerprint policy internally; the derived pubkey, derivation path,
 * UTXO binding, and signature checks remain enforced by entropy-core. The
 * unknown fingerprint is informational UI state, never a user approval gate.
 */
internal fun attemptPsbtSign(
    psbtBytes: ByteArray,
    mnemonicWords: List<String>,
    passphrase: String,
    expectedCosignerFingerprint: String?,
): PsbtSignOutcome {
    if (expectedCosignerFingerprint != null) {
        val attempt = runCatching {
            signPsbtForCosigner(psbtBytes, expectedCosignerFingerprint, mnemonicWords, passphrase)
        }
        val outcome = attempt.getOrElse {
            return PsbtSignOutcome.Failed(it.message ?: "This PSBT could not be signed by this device.")
        }
        return when (outcome) {
            is SignForCosignerResult.Signed -> PsbtSignOutcome.Signed(outcome.psbtBytes)
            is SignForCosignerResult.FingerprintMismatch ->
                PsbtSignOutcome.CosignerMismatch(outcome.expectedFingerprint, outcome.actualFingerprint)
        }
    }

    val policy = FingerprintTrustPolicy.ALLOW_UNKNOWN_FINGERPRINT_WITH_KEY_MATCH
    val result = runCatching { signAndFinalizePsbt(psbtBytes, mnemonicWords, passphrase, policy) }
    return result.fold(
        onSuccess = { PsbtSignOutcome.Signed(it) },
        onFailure = { PsbtSignOutcome.Failed(it.message ?: "This PSBT could not be signed by this device.") },
    )
}
