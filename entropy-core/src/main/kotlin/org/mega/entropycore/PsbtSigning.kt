package org.mega.entropycore

/** True when [scriptPubKey] is a native P2WPKH output: OP_0 (0x00) followed
 * by a 20-byte push (0x14) of the pubkey hash — this app's single-sig
 * (BIP84) wallets always spend from this script type. */
private fun isP2wpkhScriptPubKey(scriptPubKey: ByteArray): Boolean =
    scriptPubKey.size == 22 && scriptPubKey[0] == 0x00.toByte() && scriptPubKey[1] == 0x14.toByte()

internal fun signPsbt(psbt: Psbt, masterKey: Bip32ExtendedPrivateKey): Psbt {
    val signedInputs = psbt.inputs.mapIndexed { i, inputMap ->
        // a. No witness UTXO means we cannot compute the BIP143 sighash.
        val witnessUtxo = inputMap.witnessUtxo() ?: return@mapIndexed inputMap
        // b. This app only signs P2WSH (multisig) and P2WPKH (single-sig)
        // inputs — every input its own wallets ever produce. witnessScript
        // present means P2WSH; its absence with a P2WPKH-shaped witness_utxo
        // scriptPubKey means P2WPKH. Anything else has no known scriptCode.
        val witnessScript = inputMap.witnessScript()
        if (witnessScript == null && !isP2wpkhScriptPubKey(witnessUtxo.scriptPubKey)) return@mapIndexed inputMap

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

            // scriptCode per BIP143: for P2WSH, the witnessScript prefixed with its
            // own compact-size length; for P2WPKH, the fixed P2PKH-shaped template
            // over this pubkey's hash (the 0x19 length byte is part of the template
            // itself, not a separately-added prefix).
            val scriptCode = if (witnessScript != null) {
                writeCompactSize(witnessScript.size.toLong()) + witnessScript
            } else {
                byteArrayOf(0x19, 0x76.toByte(), 0xa9.toByte(), 0x14) + hash160(derivation.pubkey) + byteArrayOf(0x88.toByte(), 0xac.toByte())
            }

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
