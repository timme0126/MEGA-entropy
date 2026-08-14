package org.mega.entropycore

/**
 * The outcome of attempting to sign a PSBT as a specific, user-claimed
 * cosigner. [FingerprintMismatch] means the supplied seed's own BIP32
 * master fingerprint does not equal the fingerprint the caller claimed
 * for this cosigner — signing is refused entirely in that case (no
 * partial signature is added, nothing is attempted against the PSBT at
 * all) since attempting to sign with the wrong key would either produce
 * a signature nothing in the PSBT's own derivation data will ever
 * recognize as belonging to any cosigner, or (far worse) coincidentally
 * matching a DIFFERENT cosigner slot than the one the user intended to
 * act as.
 */
sealed class SignForCosignerResult {
    data class Signed(val psbtBytes: ByteArray) : SignForCosignerResult()
    data class FingerprintMismatch(val expectedFingerprint: String, val actualFingerprint: String) : SignForCosignerResult()
}

/**
 * The BIP32 master fingerprint of the key derivable from [mnemonicWords]
 * + [passphrase], as 8 lowercase hex characters (the same format
 * [normalizeMasterFingerprint] validates and returns) — i.e. this is
 * "what fingerprint would this seed's master key have", independent of
 * any specific derivation path used from it afterward.
 */
fun masterKeyFingerprint(mnemonicWords: List<String>, passphrase: String = ""): String {
    return bip32MasterKeyFromSeed(deriveSeed(mnemonicWords, passphrase).bytes).fingerprint().toHex()
}

/**
 * Signs [psbtBytes] as the specific cosigner whose stored BIP32 master
 * fingerprint is [expectedMasterFingerprint] (normalized/validated via
 * [normalizeMasterFingerprint] — an invalid fingerprint string throws
 * [IllegalArgumentException] from that call, same as every other caller
 * of it in this module), using [mnemonicWords]/[passphrase] as the
 * candidate signing seed.
 *
 * Fails CLOSED: if the seed's own master fingerprint (see
 * [masterKeyFingerprint]) does not exactly equal the expected one, this
 * returns [SignForCosignerResult.FingerprintMismatch] WITHOUT calling
 * [signAndFinalizePsbt] or touching [psbtBytes] in any way — no partial
 * signature is ever added for a seed that doesn't match the claimed
 * cosigner. Only on a match does it delegate to the existing, already
 * fingerprint-scoped [signAndFinalizePsbt] (which itself only signs PSBT
 * input derivations whose OWN per-input master fingerprint matches this
 * same master key — see its call into `signPsbt`), returning
 * [SignForCosignerResult.Signed] with the result.
 *
 * Always signs with [FingerprintTrustPolicy.STRICT] — explicitly, not just
 * by relying on its default — since a saved-vault cosigner slot's identity
 * must come from a verified fingerprint match, never from an unrecorded
 * (00000000) fingerprint plus a merely-plausible pubkey match. See
 * [FingerprintTrustPolicy]'s doc for why: the same seed can be a cosigner
 * in multiple different vaults, each expecting a different fingerprint, so
 * this is the one call site that must never be relaxed.
 */
fun signPsbtForCosigner(
    psbtBytes: ByteArray,
    expectedMasterFingerprint: String,
    mnemonicWords: List<String>,
    passphrase: String = "",
): SignForCosignerResult {
    val normalizedExpected = normalizeMasterFingerprint(expectedMasterFingerprint)
    val actual = masterKeyFingerprint(mnemonicWords, passphrase)

    if (normalizedExpected != actual) {
        return SignForCosignerResult.FingerprintMismatch(normalizedExpected, actual)
    }

    return SignForCosignerResult.Signed(
        signAndFinalizePsbt(psbtBytes, mnemonicWords, passphrase, FingerprintTrustPolicy.STRICT),
    )
}
