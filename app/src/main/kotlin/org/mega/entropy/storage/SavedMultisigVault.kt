package org.mega.entropy.storage

import org.mega.entropycore.MultisigCosignerOrigin
import org.mega.entropycore.MultisigScriptType
import org.mega.entropycore.WalletNetwork

/**
 * One cosigner as stored in a saved multisig vault — the same three fields
 * [MultisigCosignerOrigin] carries (fingerprint/path/xpub, all public key
 * material by design), plus a user-facing [label] and [passphraseUsed],
 * display-only facts about how this cosigner was derived. Kept separate from MultisigCosignerOrigin
 * itself rather than added onto it — entropy-core's type is pure crypto
 * data, this is UI/storage metadata layered on top.
 */
data class SavedMultisigCosigner(
    val label: String,
    val masterFingerprint: String,
    val derivationPath: String,
    val extendedPublicKey: String,
    /** null when unknown — a pasted or scanned cosigner (including one
     * completed through the bare-xpub helper) has no way to know whether a
     * passphrase was used on the device it came from. Only ever non-null
     * for a cosigner derived on THIS device from a saved session, where the
     * derive screen knows exactly what was typed. */
    val passphraseUsed: Boolean?,
) {
    fun toOrigin(): MultisigCosignerOrigin = MultisigCosignerOrigin(masterFingerprint, derivationPath, extendedPublicKey)
}

/**
 * A saved "Setup Multi-Signature Vault" result. Deliberately stores only
 * the inputs buildMultisigWallet needs (threshold/cosigners/network) rather
 * than its computed descriptor/address — those are cheap to recompute on
 * load and recomputing means a saved vault automatically benefits from any
 * future fix to that logic instead of being stuck with a stale value.
 *
 * Every field here is public information by construction: a threshold, a
 * network/script-type choice, and per-cosigner fingerprint/path/xpub data
 * that is the whole point of an extended PUBLIC key. Nothing here can move
 * funds or reveal a private key, which is why this is stored unencrypted
 * (see MultisigVaultSerializer) unlike SessionRepository's mnemonic-bearing
 * sessions.
 */
data class SavedMultisigVault(
    val id: String,
    val createdAtEpochMillis: Long,
    val label: String,
    val threshold: Int,
    val network: WalletNetwork,
    val scriptType: MultisigScriptType,
    val cosigners: List<SavedMultisigCosigner>,
)
