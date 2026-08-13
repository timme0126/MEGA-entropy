package org.mega.entropycore

internal fun signPsbt(psbt: Psbt, masterKey: Bip32ExtendedPrivateKey): Psbt {
    val signedInputs = psbt.inputs.mapIndexed { i, inputMap ->
        // a. No witness UTXO means we cannot compute the BIP143 sighash.
        val witnessUtxo = inputMap.witnessUtxo() ?: return@mapIndexed inputMap
        // b. Only P2WSH inputs are supported by this app's multisig.
        val witnessScript = inputMap.witnessScript() ?: return@mapIndexed inputMap

        // c. Track already-signed pubkeys to avoid duplicate signatures.
        // ByteArray lacks structural equals/hashCode, so we convert to List for Set membership.
        val signedPubkeys = inputMap.partialSigs().map { it.pubkey.toList() }.toSet()

        // d. Start with existing entries to preserve all other PSBT fields.
        var newEntries = inputMap.entries

        // e. Iterate over BIP-32 derivations to find keys we control.
        for (derivation in inputMap.bip32Derivations()) {
            // Skip if this derivation belongs to a different device/master key.
            if (!derivation.masterFingerprint.contentEquals(masterKey.fingerprint())) continue

            // Skip if this pubkey is already signed in this input.
            if (derivation.pubkey.toList() in signedPubkeys) continue

            // Derive the child key along the stated path from our master key.
            var child = masterKey
            for (rawIndex in derivation.path) {
                val hardened = rawIndex >= HARDENED_OFFSET
                val index = if (hardened) rawIndex - HARDENED_OFFSET else rawIndex
                child = child.deriveChild(index, hardened)
            }

            // Defensive check: ensure the derived pubkey matches the one claimed in the PSBT.
            // Signing for a mismatched pubkey would misattribute the signature.
            if (!child.compressedPublicKey().contentEquals(derivation.pubkey)) continue

            // P2WSH scriptCode per BIP143: witnessScript prefixed with its own compact-size length.
            val scriptCode = writeCompactSize(witnessScript.size.toLong()) + witnessScript

            // Default to SIGHASH_ALL (0x01) if not specified.
            val sighashType = (inputMap.sighashType() ?: 1L).toInt()

            // Compute the BIP143 sighash over the unsigned transaction and input.
            val sighash = computeSegwitSighash(psbt.unsignedTx, i, scriptCode, witnessUtxo.valueSats, sighashType)

            // Sign the sighash and append the sighash type byte to form the partial_sig value.
            val signature = signEcdsaDer(child.privateKey, sighash) + byteArrayOf(sighashType.toByte())

            // Append the new partial signature entry.
            newEntries = newEntries + PsbtKeyValue(keyType = 0x02, keyData = derivation.pubkey, value = signature)
        }

        // f. Return the updated PsbtMap for this input.
        PsbtMap(newEntries)
    }

    // Return a new Psbt instance with the signed inputs, preserving immutability.
    return psbt.copy(inputs = signedInputs)
}
