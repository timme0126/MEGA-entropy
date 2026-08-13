package org.mega.entropycore

/**
 * Cryptographically verifies whether a PSBT output is a change output of a
 * specific, known multisig vault — rather than trusting the PSBT's own
 * output-level metadata, which a malicious coordinator can fabricate to make
 * ANY output look like change.
 *
 * An output is verified change iff:
 *
 * 1. Its output map carries a bip32 derivation for EVERY cosigner of the
 *    vault (matched by master fingerprint), each at a path shaped exactly
 *    m/48'/{coin}'/{account}'/2'/1/{index} — BIP48 purpose, this vault's
 *    network coin type, any account, native-segwit script type, the CHANGE
 *    chain (1), and a non-hardened address index; and
 * 2. each derivation's claimed pubkey actually equals the pubkey derived
 *    from that cosigner's stored xpub at the path's change-chain suffix;
 *    and
 * 3. the output's scriptPubKey is exactly the P2WSH program of the
 *    sortedmulti(threshold, those pubkeys) witness script — i.e. paying
 *    back to the vault itself.
 *
 * Anything missing, inconsistent, or mismatched returns false (Unknown /
 * NOT change) — never a guess. Callers must label an unverified output
 * accordingly.
 */
fun verifyVaultChangeOutput(
    outputScriptPubKey: ByteArray,
    outputMap: PsbtMap,
    threshold: Int,
    cosigners: List<MultisigCosignerOrigin>,
    network: WalletNetwork,
): Boolean {
    if (cosigners.isEmpty() || threshold < 1 || threshold > cosigners.size) return false
    val derivations = outputMap.outputBip32Derivations()
    if (derivations.isEmpty()) return false

    val derivedPubkeys = cosigners.map { cosigner ->
        val derivation = derivations.firstOrNull {
            it.masterFingerprint.toHex() == cosigner.masterFingerprint.lowercase()
        } ?: return false

        val path = derivation.path
        if (path.size != 6) return false
        // Purpose / coin / account / script-type must be hardened BIP48 for
        // this network; chain must be the non-hardened change chain (1);
        // index must be a non-hardened address index.
        if (path[0] != HARDENED_OFFSET + 48L) return false
        if (path[1] != HARDENED_OFFSET + network.coinType) return false
        if (path[2] < HARDENED_OFFSET || path[2] > 2L * HARDENED_OFFSET - 1L) return false
        if (path[3] != HARDENED_OFFSET + 2L) return false
        if (path[4] != 1L) return false
        val addressIndex = path[5]
        if (addressIndex < 0 || addressIndex >= HARDENED_OFFSET) return false

        val accountKey = try {
            parseExtendedPublicKey(cosigner.extendedPublicKey)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val expectedPubkey = try {
            accountKey.deriveChild(1L).deriveChild(addressIndex).publicKey
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (!expectedPubkey.contentEquals(derivation.pubkey)) return false
        expectedPubkey
    }

    val witnessScript = try {
        buildMultisigWitnessScript(threshold, sortPublicKeysBip67(derivedPubkeys))
    } catch (e: IllegalArgumentException) {
        return false
    }
    val expectedScriptPubKey = byteArrayOf(0x00, 0x20) + sha256(witnessScript)
    return outputScriptPubKey.contentEquals(expectedScriptPubKey)
}
