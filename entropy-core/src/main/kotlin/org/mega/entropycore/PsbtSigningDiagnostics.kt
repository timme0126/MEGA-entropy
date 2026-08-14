package org.mega.entropycore

/**
 * Coarse, safe-to-display classification of an input's resolved UTXO —
 * never the actual script bytes, only this classification. UNRESOLVED
 * means neither witness_utxo nor non_witness_utxo could be turned into a
 * spendable output (missing, malformed, or internally contradictory).
 */
enum class PsbtInputScriptKind { P2WPKH, P2WSH_MULTISIG, UNRECOGNIZED, UNRESOLVED }

/**
 * One PSBT_IN_BIP32_DERIVATION entry's relationship to this device's key —
 * see [FingerprintMatchStatus]. [fingerprintHex] and [path] are copied
 * verbatim from the scanned PSBT — both are plaintext fields BIP174 puts
 * there for exactly this purpose, not secrets.
 */
data class PsbtInputKeyDiagnostic(
    val fingerprintHex: String,
    val path: List<Long>,
    val matchStatus: FingerprintMatchStatus,
)

/**
 * Everything safe to report about why one PSBT input was, or was not,
 * signed by this device — computed read-only, without ever deriving or
 * touching a signature. Every field here is derived ONLY from PSBT bytes
 * that are already public (the file the user just scanned) or from this
 * device's own already-computed BIP32 master fingerprint (a public,
 * non-secret identifier, not a seed/passphrase/private key) — never from
 * the mnemonic, passphrase, private key material, or full PSBT contents.
 * Safe to show in a UI message or write to a log.
 */
data class PsbtInputSigningDiagnostic(
    val inputIndex: Int,
    val hasWitnessUtxo: Boolean,
    val hasNonWitnessUtxo: Boolean,
    val utxoResolved: Boolean,
    val scriptKind: PsbtInputScriptKind,
    val utxoMatchesDeclaredScript: Boolean,
    val loadedMasterFingerprintHex: String,
    val alreadyFinalized: Boolean,
    val sighashSupported: Boolean,
    val keys: List<PsbtInputKeyDiagnostic>,
    val existingPartialSigCount: Int,
    /** True when a [FingerprintMatchStatus.VERIFIED_MATCH] key alone is
     * enough to sign this input — i.e. true under [FingerprintTrustPolicy.STRICT],
     * the only policy the saved-vault/cosigner flow ever uses. */
    val wouldAddSignature: Boolean,
    /** True when this input would ONLY gain a signature by additionally
     * trusting a [FingerprintMatchStatus.UNKNOWN_FINGERPRINT_PUBKEY_MATCH]
     * key — i.e. [wouldAddSignature] is false, but signing WOULD succeed
     * under [FingerprintTrustPolicy.ALLOW_UNKNOWN_FINGERPRINT_WITH_KEY_MATCH].
     * Single-seed Advanced Mode is the only caller ever allowed to act on
     * this — see PsbtSignResultScreen's explicit warning/confirmation gate. */
    val wouldAddSignatureWithUnverifiedFingerprint: Boolean,
)

/**
 * Read-only prediction of what [signPsbt] will decide for every input of
 * [psbt], given [masterKey] — computed independently of, and without any
 * effect on, the real signing path, so it can be shown to the user
 * (or logged) as a safe explanation even when signing throws or silently
 * signs nothing. Reports FACTS (each key's [FingerprintMatchStatus], UTXO/
 * script/sighash checks) rather than a policy-filtered verdict — see
 * [PsbtInputSigningDiagnostic.wouldAddSignature] vs
 * [PsbtInputSigningDiagnostic.wouldAddSignatureWithUnverifiedFingerprint]
 * for the two policies this app ever applies. This function itself never
 * throws: any per-input resolution failure (malformed non_witness_utxo,
 * txid mismatch, disagreeing UTXO representations, etc.) is reported as
 * `utxoResolved = false` rather than propagating, since the whole point of
 * this function is to explain a failure, not risk becoming a second one.
 */
/**
 * One derivation entry's classification, computed exactly ONCE and reused
 * everywhere it's needed. BIP32 child derivation is pure-Kotlin BigInteger
 * elliptic-curve arithmetic — expensive enough (tens of milliseconds per
 * derivation path on real hardware) that recomputing it 3-4x per entry, as
 * an earlier version of this function did (once building [keys], then
 * again per candidate signing policy), measurably added up across a
 * several-input PSBT. [pubkeyVerified] is true when this derivation's
 * pubkey is CONFIRMED to be exactly what [masterKey] derives along its own
 * stated path — always true for [FingerprintMatchStatus.UNKNOWN_FINGERPRINT_PUBKEY_MATCH]
 * (guaranteed by [classifyFingerprintMatch]'s own definition, so never
 * re-derived here), computed once for [FingerprintMatchStatus.VERIFIED_MATCH]
 * (which does NOT itself confirm pubkey derivation), and irrelevant
 * (always false, never checked) for MISMATCH/MALFORMED.
 */
private class DerivationDiagnosis(
    val derivation: PsbtBip32Derivation,
    val status: FingerprintMatchStatus,
    val pubkeyVerified: Boolean,
)

internal fun diagnosePsbtInputSigning(psbt: Psbt, masterKey: Bip32ExtendedPrivateKey): List<PsbtInputSigningDiagnostic> {
    val loadedFingerprintHex = masterKey.fingerprint().toHex()

    return psbt.inputs.mapIndexed { index, inputMap ->
        val hasWitnessUtxo = inputMap.entries.any { it.keyType == 0x01 }
        val hasNonWitnessUtxo = inputMap.entries.any { it.keyType == 0x00 }
        val alreadyFinalized = inputMap.finalScriptWitness() != null

        val resolvedUtxo = if (alreadyFinalized) {
            null
        } else {
            try {
                resolveInputUtxo(psbt.unsignedTx, index, inputMap)
            } catch (e: Exception) {
                null
            }
        }

        val witnessScript = inputMap.witnessScript()
        val scriptKind: PsbtInputScriptKind
        val utxoMatchesDeclaredScript: Boolean
        when {
            resolvedUtxo == null -> {
                scriptKind = PsbtInputScriptKind.UNRESOLVED
                utxoMatchesDeclaredScript = false
            }
            witnessScript != null -> {
                scriptKind = PsbtInputScriptKind.P2WSH_MULTISIG
                val expected = byteArrayOf(0x00, 0x20) + sha256(witnessScript)
                utxoMatchesDeclaredScript = resolvedUtxo.scriptPubKey.contentEquals(expected)
            }
            isP2wpkhScriptPubKey(resolvedUtxo.scriptPubKey) -> {
                scriptKind = PsbtInputScriptKind.P2WPKH
                utxoMatchesDeclaredScript = true
            }
            else -> {
                scriptKind = PsbtInputScriptKind.UNRECOGNIZED
                utxoMatchesDeclaredScript = false
            }
        }

        val sighashType = try {
            inputMap.sighashType()
        } catch (e: Exception) {
            null
        }
        val sighashSupported = sighashType == null || sighashType == 1L

        val existingPubkeys = inputMap.partialSigs().map { it.pubkey.toList() }.toSet()

        // Classify + (when needed) verify pubkey derivation EXACTLY ONCE per
        // derivation entry — see DerivationDiagnosis's own doc for why this
        // matters (BIP32 derivation is not cheap, and this is on the hot
        // path for every PSBT scan).
        val derivationDiagnoses = inputMap.bip32Derivations().map { derivation ->
            val status = classifyFingerprintMatch(derivation, masterKey)
            val pubkeyVerified = when (status) {
                FingerprintMatchStatus.UNKNOWN_FINGERPRINT_PUBKEY_MATCH -> true
                FingerprintMatchStatus.VERIFIED_MATCH -> derivedPubkeyMatchesClaimed(derivation, masterKey)
                FingerprintMatchStatus.MISMATCH, FingerprintMatchStatus.MALFORMED -> false
            }
            DerivationDiagnosis(derivation, status, pubkeyVerified)
        }

        val keys = derivationDiagnoses.map { dd ->
            PsbtInputKeyDiagnostic(
                fingerprintHex = dd.derivation.masterFingerprint.toHex(),
                path = dd.derivation.path,
                matchStatus = dd.status,
            )
        }

        val baseEligible = !alreadyFinalized && resolvedUtxo != null && utxoMatchesDeclaredScript && sighashSupported

        fun isSignableUnder(dd: DerivationDiagnosis, requiredStatus: FingerprintMatchStatus): Boolean {
            if (dd.derivation.pubkey.toList() in existingPubkeys) return false
            if (dd.status != requiredStatus) return false
            if (!dd.pubkeyVerified) return false
            // For P2WPKH, the UTXO program must also hash to THIS specific
            // derived pubkey — parallels signPsbt step (e)/(f).
            return scriptKind != PsbtInputScriptKind.P2WPKH ||
                resolvedUtxo!!.scriptPubKey.contentEquals(byteArrayOf(0x00, 0x14) + hash160(dd.derivation.pubkey))
        }

        val wouldAddSignature = baseEligible &&
            derivationDiagnoses.any { isSignableUnder(it, FingerprintMatchStatus.VERIFIED_MATCH) }
        val wouldAddSignatureWithUnverifiedFingerprint = !wouldAddSignature && baseEligible &&
            derivationDiagnoses.any { isSignableUnder(it, FingerprintMatchStatus.UNKNOWN_FINGERPRINT_PUBKEY_MATCH) }

        PsbtInputSigningDiagnostic(
            inputIndex = index,
            hasWitnessUtxo = hasWitnessUtxo,
            hasNonWitnessUtxo = hasNonWitnessUtxo,
            utxoResolved = resolvedUtxo != null,
            scriptKind = scriptKind,
            utxoMatchesDeclaredScript = utxoMatchesDeclaredScript,
            loadedMasterFingerprintHex = loadedFingerprintHex,
            alreadyFinalized = alreadyFinalized,
            sighashSupported = sighashSupported,
            keys = keys,
            existingPartialSigCount = inputMap.partialSigs().size,
            wouldAddSignature = wouldAddSignature,
            wouldAddSignatureWithUnverifiedFingerprint = wouldAddSignatureWithUnverifiedFingerprint,
        )
    }
}
