package org.mega.entropy.ui.advancedmode.multisig

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaCopyIconButton
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaQrCode
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.MultisigWallet

/**
 * Renders a completed multisig vault: threshold/cosigner-count summary,
 * output descriptor, QR code, first receive address, disclaimer, and a
 * tile per cosigner (label, fingerprint, path, xpub preview, account
 * index, passphrase-used). Pure display — no ViewModel or navigation
 * access — so the same composable renders both a freshly-built vault
 * (Setup Multi-Signature Vault's Result step) and one loaded back from
 * storage (a saved vault's detail view). Meant to be placed directly
 * inside an existing scrollable Column that already spaces its children
 * (see MegaInfoScaffold), so this does not add its own spacing between
 * cards.
 */
@Composable
fun MultisigVaultResultDisplay(
    wallet: MultisigWallet,
    cosigners: List<CosignerDisplayInfo>,
    allowSeedCopy: Boolean,
) {
    MegaCard(title = "Multi-Signature Vault") {
        Text(
            text = "${wallet.threshold}-of-${wallet.cosigners.size} multisig",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }

    MegaCard(
        title = "Output descriptor",
        trailingAction = if (allowSeedCopy) {
            { MegaCopyIconButton(contentDescription = "Copy output descriptor", getTextToCopy = { wallet.descriptor }) }
        } else {
            null
        },
    ) {
        MegaMonoText(wallet.descriptor)
    }

    MegaCard(title = "QR code (descriptor)") {
        MegaQrCode(wallet.descriptor, contentDescription = "QR code for multisig wallet descriptor")
    }

    MegaCard(
        title = "First receive address (external chain, index 0)",
        trailingAction = if (allowSeedCopy) {
            { MegaCopyIconButton(contentDescription = "Copy first receive address", getTextToCopy = { wallet.firstReceiveAddress }) }
        } else {
            null
        },
    ) {
        MegaMonoText(wallet.firstReceiveAddress)
    }

    Text(
        text = "This descriptor and address are public information only — import them into a descriptor-aware wallet (e.g. Sparrow) as a watch-only multisig wallet. No private key or signing capability exists anywhere in this screen.",
        style = MaterialTheme.typography.bodyMedium,
        color = MegaError,
    )

    cosigners.forEachIndexed { index, cosigner ->
        CosignerTile(index = index + 1, cosigner = cosigner)
    }
}

@Composable
private fun CosignerTile(index: Int, cosigner: CosignerDisplayInfo) {
    MegaCard(title = "Cosigner $index") {
        Text(text = cosigner.label, style = MaterialTheme.typography.bodyMedium)

        MegaMonoText("Fingerprint: ${cosigner.masterFingerprint}")
        MegaMonoText("Path: ${cosigner.derivationPath}")

        val xpubPreview = if (cosigner.extendedPublicKey.length > 18) {
            "${cosigner.extendedPublicKey.take(12)}…${cosigner.extendedPublicKey.takeLast(6)}"
        } else {
            cosigner.extendedPublicKey
        }
        MegaMonoText(xpubPreview)

        cosigner.accountIndex?.let { accountIndex ->
            Text(
                text = "Account index: $accountIndex",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val passphraseText = when (cosigner.passphraseUsed) {
            true -> "Passphrase: used"
            false -> "Passphrase: not used"
            null -> "Passphrase: unknown (not derived on this device)"
        }
        Text(
            text = passphraseText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
