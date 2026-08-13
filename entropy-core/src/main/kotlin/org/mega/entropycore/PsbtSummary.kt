package org.mega.entropycore

/** Everything safely known about one PSBT input, before any signing. */
data class PsbtInputSummary(
    val amountSats: Long?,               // null if this input's witness_utxo is missing — amount genuinely unknown
    val existingSignatureCount: Int,      // partial_sig entries already present, before this device signs anything
    val cosignerCount: Int?,              // total distinct PSBT_IN_BIP32_DERIVATION entries on this input (how many different cosigner keys this input's script involves) — null if there are none at all (nothing to derive a count from)
)

/** Everything safely known about one PSBT output. */
data class PsbtOutputSummary(
    val amountSats: Long,                 // always known — comes directly from the PSBT's own unsigned transaction, not from signing
    val address: String?,                 // null when the network is unknown OR the scriptPubKey isn't a recognized standard shape
    val scriptPubKeyHex: String,          // always available as a fallback display when address is null — lowercase hex, no separators
    val isLikelyChange: Boolean,          // true ONLY when this output has an OUTPUT-level bip32 derivation whose masterFingerprint also appears in at least one of the transaction's OWN INPUTS' bip32 derivations (this wallet's own key paying itself back) — false (not "unknown") whenever that can't be established; this field is a conservative heuristic, not a certainty, and must never be described as more than that by its own name
)

/** Whether every multisig input in this PSBT agrees on the same M-of-N
 * signing threshold — genuinely differs per input in unusual PSBTs (e.g.
 * mixing script types), so this is reported as a sealed outcome rather
 * than silently picking one input's value. */
sealed class PsbtThresholdInfo {
    data class Known(val threshold: Int, val cosignerCount: Int) : PsbtThresholdInfo()
    object Varies : PsbtThresholdInfo()   // multiple multisig inputs present but they disagree with each other
    object Unknown : PsbtThresholdInfo()  // no input has a parseable bare-multisig witness_script (e.g. all P2WPKH, or scripts in a non-standard shape)
}

data class PsbtSummary(
    val network: WalletNetwork?,                  // as given by the caller (this function cannot determine network from PSBT bytes alone — there is no network field in a PSBT, and raw scriptPubKey bytes are identical across networks; only the caller, if it already knows which wallet/vault this PSBT is for, can supply this)
    val inputCount: Int,
    val outputCount: Int,
    val inputs: List<PsbtInputSummary>,
    val outputs: List<PsbtOutputSummary>,
    val totalInputSats: Long?,                    // null if ANY input's amount is unknown (never silently sum only the known ones and present a partial total as if it were the whole picture)
    val totalOutputSats: Long,                    // always known
    val feeSats: Long?,                           // totalInputSats - totalOutputSats; null whenever totalInputSats is null
    val estimatedFeeRateSatsPerVByte: Double?,     // see calculation below; null whenever feeSats is null
    val isAlreadyPartiallySigned: Boolean,         // true if existingSignatureCount > 0 for any input, BEFORE this device signs anything
    val existingSignatureCount: Int,               // sum of every input's existingSignatureCount
    val requiredThreshold: PsbtThresholdInfo,
    val deviceCanSignAnyInput: Boolean?,           // null if deviceMasterFingerprint (the function parameter) was null; otherwise true iff at least one input has a PSBT_IN_BIP32_DERIVATION whose masterFingerprint matches deviceMasterFingerprint AND doesn't already have a partial_sig for that same pubkey
    val willFinalizeIfSigned: Boolean?,            // best-effort prediction of whether signing with deviceMasterFingerprint would bring EVERY input to its own threshold — null if deviceMasterFingerprint is null, or if any input's threshold can't be determined (see below); true only if confident every input reaches enough signatures
)

/**
 * Computes a full pre-signing review summary for [psbtBytes]. Never signs,
 * never derives or touches a private key, never logs anything.
 *
 * [knownNetwork] — pass the network the caller already knows this PSBT is
 * for (e.g. a saved vault's own recorded network), or null if genuinely
 * unknown (there is no reliable way to determine it from PSBT bytes
 * alone). Used only to pick the bech32 HRP ("bc"/"tb") for address display.
 *
 * [deviceMasterFingerprint] — pass this device's already-computed BIP32
 * master fingerprint (8 lowercase hex chars, e.g. from
 * [masterKeyFingerprint] or an already-verified cosigner's stored value)
 * if known at review time, or null if not yet determined. This function
 * NEVER derives this itself — it only compares the given string (if any)
 * against fingerprint bytes already embedded in the PSBT. Passing null
 * means [PsbtSummary.deviceCanSignAnyInput] and [PsbtSummary.willFinalizeIfSigned]
 * both come back null (Unknown) rather than guessed.
 *
 * Throws whatever [parsePsbt] throws for a malformed PSBT — this function
 * does not itself catch parse failures; the caller is expected to.
 */
fun computePsbtSummary(
    psbtBytes: ByteArray,
    knownNetwork: WalletNetwork? = null,
    deviceMasterFingerprint: String? = null,
): PsbtSummary {
    // Parse the PSBT. We delegate all structural validation to parsePsbt
    // so this review function stays purely focused on display logic.
    val psbt = parsePsbt(psbtBytes)
    val inputCount = psbt.inputs.size
    val outputCount = psbt.outputs.size

    // 1. Per-input summary
    // We zip the unsigned transaction's inputs with the PSBT's input maps
    // because BIP174 guarantees they are always the same length.
    val inputSummaries = psbt.unsignedTx.inputs.zip(psbt.inputs).map { (txIn, inputMap) ->
        PsbtInputSummary(
            amountSats = inputMap.witnessUtxo()?.valueSats,
            existingSignatureCount = inputMap.partialSigs().size,
            cosignerCount = inputMap.bip32Derivations().size.let { if (it == 0) null else it }
        )
    }

    // 2. Collect input fingerprints early for change detection.
    // We normalize to lowercase hex strings so we can safely compare
    // against output derivations without worrying about case or byte order.
    val inputFingerprintSet = psbt.inputs
        .flatMap { it.bip32Derivations() }
        .map { it.masterFingerprint.toLowerHex() }
        .toSet()

    // 3. Per-output summary
    val outputSummaries = psbt.unsignedTx.outputs.mapIndexed { index, txOut ->
        val outputMap = psbt.outputs[index]
        val scriptPubKeyHex = txOut.scriptPubKey.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val address = knownNetwork?.let { network ->
            // We only attempt address decoding when the network is known.
            // MEGA's wallets only produce native segwit outputs, so we strictly
            // check for P2WPKH and P2WSH shapes. Anything else is genuinely
            // undecodable here without pulling in legacy base58 logic.
            scriptPubKeyToAddress(txOut.scriptPubKey, network)
        }

        // isLikelyChange is a conservative heuristic: true only when the output
        // explicitly references a master fingerprint that already appears in
        // at least one input's derivation. This catches "self-pay" change
        // outputs reliably without guessing.
        val isLikelyChange = outputMap.outputBip32Derivations()
            .any { it.masterFingerprint.toLowerHex() in inputFingerprintSet }

        PsbtOutputSummary(
            amountSats = txOut.valueSats,
            address = address,
            scriptPubKeyHex = scriptPubKeyHex,
            isLikelyChange = isLikelyChange
        )
    }

    // 4. Totals
    val totalOutputSats = psbt.unsignedTx.outputs.sumOf { it.valueSats }
    // We return null for the total input amount if ANY input lacks a witness_utxo.
    // Silently summing only known inputs would misrepresent the transaction's true cost.
    val totalInputSats = if (inputSummaries.any { it.amountSats == null }) null
    else inputSummaries.sumOf { it.amountSats!! }

    // 5. Fee
    val feeSats = totalInputSats?.let { it - totalOutputSats }

    // 6. Estimated fee rate
    // This is explicitly an ESTIMATE. Pre-signature fee rates are inherently
    // approximate because signature sizes vary slightly, and we don't yet
    // know the exact witness weight. We calculate it honestly and label it
    // as such so the user understands it's a projection, not a guarantee.
    val estimatedFeeRateSatsPerVByte = feeSats?.let { fee ->
        // Base weight: unsigned transaction bytes * 4 (standard weight unit conversion)
        val baseWeightUnits = serializeTransaction(psbt.unsignedTx).size * 4

        // Witness weight estimate:
        // For each input, we estimate the witness size based on the threshold.
        // Multisig inputs use their parsed threshold * 72 bytes (typical DER sig + sighash).
        // Non-multisig or unparseable inputs default to 1 * 72 bytes.
        // Witness data gets a 4x discount in weight, but since we're summing
        // witness bytes directly into weight units (1 byte witness = 1 weight unit),
        // we just add them to the base weight.
        // Iterates psbt.inputs directly by position — NOT via
        // inputSummaries.indexOf(inputSummary), which would silently
        // mismatch whenever two inputs happen to produce equal
        // PsbtInputSummary values (a real risk: e.g. two inputs that both
        // have amountSats=null, existingSignatureCount=0, cosignerCount=null
        // are indistinguishable by value, and indexOf would return the
        // first match for both, double-counting one input's witness
        // script and skipping the other's).
        val estimatedWitnessBytes = psbt.inputs.sumOf { inputMap ->
            val ws = inputMap.witnessScript()
            val threshold = if (ws != null && isStandardMultisig(ws)) parseThreshold(ws) else 1
            threshold * 72
        }

        val estimatedWeightUnits = baseWeightUnits + estimatedWitnessBytes
        val estimatedVBytes = (estimatedWeightUnits + 3) / 4.0

        // Defensive: avoid division by zero or infinity for malformed/empty txs
        if (estimatedVBytes > 0.0) fee.toDouble() / estimatedVBytes else null
    }

    // 7. Threshold determination
    // We parse each input's witness_script independently. If an input has
    // no witness_script (e.g., P2WPKH), it contributes nothing to this
    // determination — it's simply not a multisig input. We only look at
    // inputs that actually carry a parseable bare-multisig script.
    val multisigThresholds = psbt.inputs.mapNotNull { inputMap ->
        val ws = inputMap.witnessScript()
        if (ws != null && isStandardMultisig(ws)) {
            val threshold = parseThreshold(ws)
            val cosignerCount = parseCosignerCount(ws)
            Pair(threshold, cosignerCount)
        } else {
            null
        }
    }.toSet()

    val requiredThreshold = when {
        multisigThresholds.isEmpty() -> PsbtThresholdInfo.Unknown
        multisigThresholds.size == 1 -> {
            val (t, c) = multisigThresholds.first()
            PsbtThresholdInfo.Known(t, c)
        }
        else -> PsbtThresholdInfo.Varies
    }

    // 8. Signing status
    val existingSignatureCount = inputSummaries.sumOf { it.existingSignatureCount }
    val isAlreadyPartiallySigned = existingSignatureCount > 0

    // 9. Device signing capability & finalization prediction
    // We only attempt these checks if the caller provided a fingerprint.
    // Guessing would violate the "never invent or approximate silently" rule.
    val normalizedFp = deviceMasterFingerprint?.lowercase()
    val deviceCanSignAnyInput = normalizedFp?.let { fpHex ->
        psbt.inputs.any { inputMap ->
            inputMap.bip32Derivations().any { derivation ->
                derivation.matchesFingerprint(fpHex) &&
                    inputMap.partialSigs().none { it.pubkey.contentEquals(derivation.pubkey) }
            }
        }
    }

    val willFinalizeIfSigned = if (deviceCanSignAnyInput == true) {
        // We only predict finalization if we can confidently determine the
        // required threshold for EVERY input. An input whose threshold is
        // unparseable must make the WHOLE prediction null (Unknown) — folding
        // it into "false" would be a silent guess ("this will stay partially
        // signed") dressed up as a definite answer, exactly what this
        // function must never do. So each input maps to a nullable Boolean
        // first, and only once none of them are null do we reduce to a
        // single true/false.
        val perInputWillReachThreshold = psbt.inputs.map { inputMap ->
            val threshold = inputThreshold(inputMap) ?: return@map null
            // Only count this device as contributing a NEW signature if it has
            // a matching derivation for a pubkey that doesn't already have a
            // partial_sig — otherwise, if this device already signed this
            // input in an earlier round, its existing signature is already
            // included in partialSigs().size and must not be counted twice.
            val weWouldAddASignatureHere = inputMap.bip32Derivations().any { derivation ->
                derivation.matchesFingerprint(normalizedFp!!) &&
                    inputMap.partialSigs().none { it.pubkey.contentEquals(derivation.pubkey) }
            }
            val sigsAfter = inputMap.partialSigs().size + if (weWouldAddASignatureHere) 1 else 0
            sigsAfter >= threshold
        }
        if (perInputWillReachThreshold.any { it == null }) null else perInputWillReachThreshold.all { it == true }
    } else {
        null
    }

    return PsbtSummary(
        network = knownNetwork,
        inputCount = inputCount,
        outputCount = outputCount,
        inputs = inputSummaries,
        outputs = outputSummaries,
        totalInputSats = totalInputSats,
        totalOutputSats = totalOutputSats,
        feeSats = feeSats,
        estimatedFeeRateSatsPerVByte = estimatedFeeRateSatsPerVByte,
        isAlreadyPartiallySigned = isAlreadyPartiallySigned,
        existingSignatureCount = existingSignatureCount,
        requiredThreshold = requiredThreshold,
        deviceCanSignAnyInput = deviceCanSignAnyInput,
        willFinalizeIfSigned = willFinalizeIfSigned
    )
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

/**
 * Converts a scriptPubKey to a bech32 address if it matches a recognized
 * native segwit shape. Returns null for non-segwit or unknown networks.
 * We intentionally avoid legacy base58 decoding here because MEGA's wallets
 * never produce P2PKH/P2SH outputs, and pulling in version-byte logic would
 * unnecessarily expand this module's dependencies.
 */
private fun scriptPubKeyToAddress(scriptPubKey: ByteArray, network: WalletNetwork): String? {
    val hrp = hrpFor(network)
    return when {
        // P2WPKH: 0x00 0x14 <20-byte pubkey hash>
        scriptPubKey.size == 22 && scriptPubKey[0] == 0x00.toByte() && scriptPubKey[1] == 0x14.toByte() ->
            encodeSegwitV0Address(hrp, scriptPubKey.copyOfRange(2, 22))
        // P2WSH: 0x00 0x20 <32-byte script hash>
        scriptPubKey.size == 34 && scriptPubKey[0] == 0x00.toByte() && scriptPubKey[1] == 0x20.toByte() ->
            encodeSegwitV0Address(hrp, scriptPubKey.copyOfRange(2, 34))
        else -> null
    }
}

/** Returns the bech32 human-readable part for the given network. */
private fun hrpFor(network: WalletNetwork): String = when (network) {
    WalletNetwork.MAINNET -> "bc"
    WalletNetwork.TESTNET -> "tb"
}

/**
 * Checks if a witness script follows the standard bare-multisig layout:
 * OP_<M> <pubkey1>...<pubkeyN> OP_<N> OP_CHECKMULTISIG
 * We verify the first and second-to-last bytes are valid OP_1..OP_16 codes.
 */
private fun isStandardMultisig(witnessScript: ByteArray): Boolean =
    witnessScript.size >= 3 &&
    witnessScript[0].toUByte().toInt() in 0x51..0x60 &&
    witnessScript[witnessScript.size - 2].toUByte().toInt() in 0x51..0x60

/**
 * Extracts the threshold M from a standard bare-multisig witness script.
 * OP_<k> is encoded as 0x50 + k, so we subtract 0x50 to get the integer.
 */
private fun parseThreshold(witnessScript: ByteArray): Int =
    witnessScript[0].toUByte().toInt() - 0x50

/**
 * Extracts the cosigner count N from a standard bare-multisig witness script.
 * Located at the second-to-last byte (right before OP_CHECKMULTISIG).
 */
private fun parseCosignerCount(witnessScript: ByteArray): Int =
    witnessScript[witnessScript.size - 2].toUByte().toInt() - 0x50

/**
 * Determines the required signature count for an input.
 * Returns null if the input carries a witness script that isn't a parseable
 * bare-multisig (meaning we genuinely cannot determine the threshold).
 * Returns 1 for P2WPKH or inputs without a witness script.
 */
private fun inputThreshold(inputMap: PsbtMap): Int? {
    val ws = inputMap.witnessScript() ?: return 1
    return if (isStandardMultisig(ws)) parseThreshold(ws) else null
}

/** Extension to compare a derivation's master fingerprint against a hex string. */
private fun PsbtBip32Derivation.matchesFingerprint(hex: String): Boolean =
    this.masterFingerprint.toLowerHex() == hex.lowercase()

/** Converts a byte array to a lowercase hex string for safe comparison. */
private fun ByteArray.toLowerHex(): String = joinToString("") { "%02x".format(it) }
