package org.mega.entropy.ui.advancedmode.multisig

import org.mega.entropy.storage.SavedMultisigCosigner
import org.mega.entropycore.cosignerAccountIndex

/**
 * Presentation-only info for one cosigner tile — shown on the Result step
 * of "Setup Multi-Signature Vault", a saved vault's detail view, and PDF
 * export. Built from either a just-filled [MultisigSlot] or a
 * [SavedMultisigCosigner] loaded from storage, so all three UIs render
 * cosigner tiles identically regardless of where the data came from.
 */
data class CosignerDisplayInfo(
    val label: String,
    val masterFingerprint: String,
    val derivationPath: String,
    val extendedPublicKey: String,
    val passphraseUsed: Boolean?,
    val accountIndex: Int?,
)

fun MultisigSlot.toCosignerDisplayInfo(): CosignerDisplayInfo? {
    val origin = origin ?: return null
    val label = (status as? SlotStatus.Filled)?.label ?: "${origin.masterFingerprint} · ${origin.derivationPath}"
    return CosignerDisplayInfo(
        label = label,
        masterFingerprint = origin.masterFingerprint,
        derivationPath = origin.derivationPath,
        extendedPublicKey = origin.extendedPublicKey,
        passphraseUsed = passphraseUsed,
        accountIndex = cosignerAccountIndex(origin.derivationPath),
    )
}

fun SavedMultisigCosigner.toCosignerDisplayInfo(): CosignerDisplayInfo = CosignerDisplayInfo(
    label = label,
    masterFingerprint = masterFingerprint,
    derivationPath = derivationPath,
    extendedPublicKey = extendedPublicKey,
    passphraseUsed = passphraseUsed,
    accountIndex = cosignerAccountIndex(derivationPath),
)
