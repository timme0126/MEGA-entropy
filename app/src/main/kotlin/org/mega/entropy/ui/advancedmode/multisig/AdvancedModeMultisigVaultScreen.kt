package org.mega.entropy.ui.advancedmode.multisig

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaLabelSessionDialog
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaSavedConfirmationCard
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.BareCosignerExtendedKey
import org.mega.entropycore.MultisigScriptType
import org.mega.entropycore.WalletNetwork
import org.mega.entropycore.cosignerAccountIndex
import org.mega.entropycore.defaultCosignerDerivationPath

@Composable
fun AdvancedModeMultisigVaultScreen(
    uiState: MultisigVaultUiState,
    allowScreenshots: Boolean,
    allowSeedCopy: Boolean,
    onBack: () -> Unit,
    onSetN: (Int) -> Unit,
    onSetM: (Int) -> Unit,
    onSetNetwork: (WalletNetwork) -> Unit,
    onSetScriptType: (MultisigScriptType) -> Unit,
    onConfirmPolicy: () -> Unit,
    onBackToPolicy: () -> Unit,
    onBeginFillSlot: (index: Int) -> Unit,
    onScanSlot: (index: Int) -> Unit,
    onScanFullDescriptor: () -> Unit,
    onPasteIntoSlot: (index: Int, text: String) -> Unit,
    onPasteFullDescriptor: (text: String) -> Unit,
    onClearSlot: (index: Int) -> Unit,
    onEditFingerprint: (index: Int, fingerprint: String) -> Unit,
    onCompleteBareXpubCosigner: (fingerprint: String, accountIndex: Int?, customPath: String?) -> Unit,
    onCancelBareXpubHelper: () -> Unit,
    onBuildVault: () -> Unit,
    onBackToSlots: () -> Unit,
    onBeginSaveVault: () -> Unit,
    onCancelSaveVault: () -> Unit,
    onConfirmSaveVault: (label: String) -> Unit,
    onDismissSavedVaultConfirmation: () -> Unit,
    onConfirmDescriptorImport: () -> Unit,
    onCancelDescriptorImport: () -> Unit,
    onGoHome: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)

    when (uiState.step) {
        MultisigSetupStep.POLICY -> MegaInfoScaffold(title = "Setup Multi-Signature Vault", onBack = onBack) {
            PolicyStepContent(
                uiState = uiState,
                onSetN = onSetN,
                onSetM = onSetM,
                onSetNetwork = onSetNetwork,
                onSetScriptType = onSetScriptType,
                onConfirmPolicy = onConfirmPolicy,
            )
        }
        MultisigSetupStep.SLOTS -> MegaInfoScaffold(
            title = "Cosigners",
            onBack = onBackToPolicy,
            actions = {
                IconButton(onClick = onScanFullDescriptor) {
                    Icon(imageVector = Icons.Filled.CameraAlt, contentDescription = "Scan Full Descriptor QR")
                }
            },
        ) {
            SlotsStepContent(
                uiState = uiState,
                onBeginFillSlot = onBeginFillSlot,
                onScanSlot = onScanSlot,
                onPasteIntoSlot = onPasteIntoSlot,
                onPasteFullDescriptor = onPasteFullDescriptor,
                onClearSlot = onClearSlot,
                onEditFingerprint = onEditFingerprint,
                onBuildVault = onBuildVault,
                allowSeedCopy = allowSeedCopy,
            )

            val pendingBareXpub = uiState.pendingBareXpub
            if (pendingBareXpub != null) {
                CompleteCosignerInfoDialog(
                    pending = pendingBareXpub,
                    vaultNetwork = uiState.network,
                    scriptType = uiState.scriptType,
                    error = uiState.bareXpubError,
                    onComplete = onCompleteBareXpubCosigner,
                    onCancel = onCancelBareXpubHelper,
                )
            }

            val pendingDescriptorImport = uiState.pendingDescriptorImport
            if (pendingDescriptorImport != null) {
                DescriptorImportConfirmationDialog(
                    pending = pendingDescriptorImport,
                    onConfirm = onConfirmDescriptorImport,
                    onCancel = onCancelDescriptorImport,
                )
            }
        }
        MultisigSetupStep.RESULT -> MegaInfoScaffold(
            title = "Vault Ready",
            onBack = onBackToSlots,
            actions = {
                IconButton(onClick = onBeginSaveVault) {
                    Icon(imageVector = Icons.Filled.Save, contentDescription = "Save Vault")
                }
                val wallet = uiState.walletResult
                if (wallet != null) {
                    MultisigVaultPdfMenuButton(
                        vaultLabel = uiState.savedVaultLabel ?: "Multisig Vault",
                        wallet = wallet,
                        cosigners = uiState.slots.mapNotNull { it.toCosignerDisplayInfo() },
                    )
                }
                // Only once the vault is actually saved (labeled) is there
                // somewhere meaningful to "go home" TO — before that, the
                // vault only exists in this screen's in-memory state, and
                // leaving would discard it with no confirmation, no
                // different from Back. Gating on savedVaultLabel keeps this
                // from appearing until saving has actually happened.
                if (uiState.savedVaultLabel != null) {
                    IconButton(onClick = onGoHome) {
                        Icon(imageVector = Icons.Filled.Home, contentDescription = "Back to Advanced Mode")
                    }
                }
            },
        ) {
            ResultStepContent(uiState = uiState, allowSeedCopy = allowSeedCopy)

            val savedVaultLabel = uiState.savedVaultLabel
            if (savedVaultLabel != null) {
                MegaSavedConfirmationCard(label = savedVaultLabel, onDismissed = onDismissSavedVaultConfirmation)
            }

            if (uiState.showSaveVaultDialog) {
                MegaLabelSessionDialog(
                    title = "Label This Vault",
                    helperText = "A label is required so this vault can be told apart from others later.",
                    onConfirm = onConfirmSaveVault,
                    onDismiss = onCancelSaveVault,
                )
            }
        }
    }
}

@Composable
private fun PolicyStepContent(
    uiState: MultisigVaultUiState,
    onSetN: (Int) -> Unit,
    onSetM: (Int) -> Unit,
    onSetNetwork: (WalletNetwork) -> Unit,
    onSetScriptType: (MultisigScriptType) -> Unit,
    onConfirmPolicy: () -> Unit,
) {
    MegaCard {
        Text(
            "Choose how many signatures are required to spend (M) and how many cosigners your vault has (N), then fill in each cosigner's public key.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    IntOptionRow(
        label = "Signatures required (M)",
        options = listOf(1, 2, 3, 4, 5),
        selected = uiState.m,
        onClick = onSetM,
    )

    // N can't be chosen before M — its own valid range depends on it: N can
    // never be smaller than the M-of-N threshold already chosen, and a
    // multisig vault always needs at least 2 cosigners even when M is 1.
    val m = uiState.m
    if (m != null) {
        IntOptionRow(
            label = "Number of cosigners (N)",
            options = (maxOf(m, 2)..5).toList(),
            selected = uiState.n,
            onClick = onSetN,
        )
    }

    if (uiState.n != null && uiState.m != null) {
        Text(
            "${uiState.m} of ${uiState.n}",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }

    MegaCard(title = "Network") {
        WalletNetwork.entries.forEach { network ->
            NetworkOption(
                label = if (network == WalletNetwork.MAINNET) "Mainnet" else "Testnet",
                selected = uiState.network == network,
                onClick = { onSetNetwork(network) },
            )
        }
    }

    MegaCard(title = "Script type") {
        MultisigScriptType.entries.forEach { type ->
            MultisigScriptTypeOption(
                label = type.displayName,
                selected = uiState.scriptType == type,
                onClick = { onSetScriptType(type) },
            )
        }
    }

    MegaPrimaryButton(
        text = "Continue",
        enabled = uiState.n != null && uiState.m != null,
        onClick = onConfirmPolicy,
    )
}

@Composable
private fun SlotsStepContent(
    uiState: MultisigVaultUiState,
    onBeginFillSlot: (Int) -> Unit,
    onScanSlot: (Int) -> Unit,
    onPasteIntoSlot: (Int, String) -> Unit,
    onPasteFullDescriptor: (String) -> Unit,
    onClearSlot: (Int) -> Unit,
    onEditFingerprint: (Int, String) -> Unit,
    onBuildVault: () -> Unit,
    allowSeedCopy: Boolean,
) {
    MegaCard {
        Text(
            "${uiState.m} of ${uiState.n} — fill in every cosigner below.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    uiState.slots.forEachIndexed { index, slot ->
        MultisigSlotCard(
            index = index,
            slot = slot,
            onBeginFillSlot = { onBeginFillSlot(index) },
            onScanSlot = { onScanSlot(index) },
            onPasteIntoSlot = { text -> onPasteIntoSlot(index, text) },
            onClearSlot = { onClearSlot(index) },
            onEditFingerprint = { fingerprint -> onEditFingerprint(index, fingerprint) },
        )
    }

    // Transient UI-only state (which dialog is open, what's typed into it) —
    // not part of the vault's own persisted-in-memory state, so it stays
    // local rather than living in MultisigVaultUiState.
    var showFullDescriptorDialog by remember { mutableStateOf(false) }
    var fullDescriptorText by remember { mutableStateOf("") }

    if (showFullDescriptorDialog) {
        AlertDialog(
            onDismissRequest = { showFullDescriptorDialog = false },
            title = { Text("Paste Full Descriptor") },
            text = {
                OutlinedTextField(
                    value = fullDescriptorText,
                    onValueChange = { fullDescriptorText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("wsh(sortedmulti(...))") },
                    minLines = 3,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onPasteFullDescriptor(fullDescriptorText)
                    showFullDescriptorDialog = false
                    fullDescriptorText = ""
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showFullDescriptorDialog = false }) { Text("Cancel") }
            },
        )
    }

    MegaSecondaryButton(text = "Paste Full Descriptor", onClick = { showFullDescriptorDialog = true })

    val currentWalletError = uiState.walletError
    if (currentWalletError != null) {
        Text(currentWalletError, style = MaterialTheme.typography.bodyMedium, color = MegaError)
    }

    MegaPrimaryButton(text = "Build Vault", enabled = uiState.allSlotsFilled, onClick = onBuildVault)
}

@Composable
private fun ResultStepContent(uiState: MultisigVaultUiState, allowSeedCopy: Boolean) {
    val wallet = uiState.walletResult ?: return
    val cosigners = uiState.slots.mapNotNull { it.toCosignerDisplayInfo() }
    MultisigVaultResultDisplay(wallet = wallet, cosigners = cosigners, allowSeedCopy = allowSeedCopy)
}

@Composable
private fun IntOptionRow(label: String, options: List<Int>, selected: Int?, onClick: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            Row(
                modifier = Modifier.weight(1f).clickable { onClick(option) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RadioButton(selected = selected == option, onClick = { onClick(option) })
                Text(option.toString(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun NetworkOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MultisigScriptTypeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MultisigSlotCard(
    index: Int,
    slot: MultisigSlot,
    onBeginFillSlot: () -> Unit,
    onScanSlot: () -> Unit,
    onPasteIntoSlot: (text: String) -> Unit,
    onClearSlot: () -> Unit,
    onEditFingerprint: (fingerprint: String) -> Unit,
) {
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var showEditFingerprintDialog by remember { mutableStateOf(false) }

    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("Paste Descriptor Fragment") },
            text = {
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("[fingerprint/path]xpub... or a full descriptor") },
                    minLines = 3,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onPasteIntoSlot(pasteText)
                    showPasteDialog = false
                    pasteText = ""
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) { Text("Cancel") }
            },
        )
    }

    MegaCard(title = "Cosigner ${index + 1}") {
        when (val status = slot.status) {
            is SlotStatus.Empty -> {
                Text("Empty", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SlotActionIcons(onBeginFillSlot, onScanSlot, { showPasteDialog = true })
            }
            is SlotStatus.Filled -> {
                Text(status.label, style = MaterialTheme.typography.bodyMedium)
                val origin = slot.origin
                if (origin != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MegaMonoText("Fingerprint: ${origin.masterFingerprint}")
                        IconButton(
                            onClick = { showEditFingerprintDialog = true },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "Edit fingerprint",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    if (showEditFingerprintDialog) {
                        EditFingerprintDialog(
                            currentValue = origin.masterFingerprint,
                            onConfirm = { newFingerprint ->
                                onEditFingerprint(newFingerprint)
                                showEditFingerprintDialog = false
                            },
                            onCancel = { showEditFingerprintDialog = false },
                        )
                    }
                    MegaMonoText("Path: ${origin.derivationPath}")
                    // Short glance preview only — never the authoritative display of
                    // this key, which the Result step's full descriptor still shows
                    // in full; this is just so N slot cards don't each carry a full
                    // ~111-character xpub on screen at once.
                    val preview = if (origin.extendedPublicKey.length > 18) {
                        "${origin.extendedPublicKey.take(12)}…${origin.extendedPublicKey.takeLast(6)}"
                    } else {
                        origin.extendedPublicKey
                    }
                    MegaMonoText(preview)
                    val accountIndex = cosignerAccountIndex(origin.derivationPath)
                    if (accountIndex != null) {
                        Text(
                            "Account index: $accountIndex",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        when (slot.passphraseUsed) {
                            true -> "Passphrase: used"
                            false -> "Passphrase: not used"
                            null -> "Passphrase: unknown (not derived on this device)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onClearSlot) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Remove cosigner",
                            tint = MegaError,
                        )
                    }
                }
            }
            is SlotStatus.Invalid -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MegaError,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(status.message, style = MaterialTheme.typography.bodyMedium, color = MegaError)
                }
                Spacer(modifier = Modifier.height(8.dp))
                SlotActionIcons(onBeginFillSlot, onScanSlot, { showPasteDialog = true })
            }
        }
    }
}

/**
 * Shown when scanned/pasted text turns out to be a bare extended public key
 * (no [fingerprint/path] origin) — the common shape a wallet like Sparrow
 * exports when a user copies "just the xpub". Rather than a raw parser
 * failure, this asks the user to supply the missing fingerprint (never
 * invented — see completeBareCosignerExtendedKey) and either an account
 * index (filled into the vault's own 48'/coin'/account'/2' policy path) or
 * a fully custom path, then runs the completed fragment through the exact
 * same parseCosignerDescriptorFragment validation as any pasted fragment.
 * A SLIP-132 key or a network mismatch is caught immediately, before
 * asking for any of that — both are already known from the key alone, so
 * there's no point making the user fill in a fingerprint first.
 */
@Composable
private fun CompleteCosignerInfoDialog(
    pending: BareCosignerExtendedKey,
    vaultNetwork: WalletNetwork,
    scriptType: MultisigScriptType,
    error: String?,
    onComplete: (fingerprint: String, accountIndex: Int?, customPath: String?) -> Unit,
    onCancel: () -> Unit,
) {
    fun networkLabel(network: WalletNetwork) = if (network == WalletNetwork.MAINNET) "mainnet" else "testnet"

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Complete Cosigner Info") },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Detected key type: ${pending.displayPrefix} (${networkLabel(pending.network)})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "This QR contains only an extended public key. Multisig wallets also need the " +
                        "cosigner fingerprint and derivation path. Check these values against the " +
                        "exporting wallet before adding this cosigner.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (!pending.isPlainXpub) {
                    Text(
                        "MEGA multisig currently accepts plain xpub/tpub for BIP48 cosigners. Export " +
                            "the descriptor or plain xpub/tpub from Sparrow.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MegaError,
                    )
                } else if (pending.network != vaultNetwork) {
                    Text(
                        "This ${pending.displayPrefix} is for ${networkLabel(pending.network)}, but this " +
                            "vault is set to ${networkLabel(vaultNetwork)} — cancel and change the vault's " +
                            "network in the Policy step, or scan/paste a matching key.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MegaError,
                    )
                } else {
                    // Defaults to the "unknown origin" placeholder rather than
                    // starting empty — a bare xpub genuinely cannot carry its
                    // own master fingerprint (see completeBareCosignerExtendedKey's
                    // doc comment), so requiring the user to type one before
                    // they can even add the cosigner blocks the common case
                    // where they don't have it handy yet. Sparrow does the
                    // same: importing a bare xpub there defaults to
                    // [00000000/...] and lets the user fill in the real
                    // fingerprint later. The field stays fully editable, and
                    // MultisigSlotCard's pencil icon lets it be corrected
                    // after the cosigner is already added.
                    var fingerprint by remember { mutableStateOf("00000000") }
                    var accountText by remember { mutableStateOf("0") }
                    var customPath by remember { mutableStateOf("") }

                    OutlinedTextField(
                        value = fingerprint,
                        onValueChange = { value ->
                            fingerprint = value.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(8)
                        },
                        label = { Text("Master fingerprint") },
                        placeholder = { Text("8 hex characters, e.g. 73c5da0a") },
                        supportingText = {
                            Text("Defaults to 00000000 (unknown) if you don't know it yet — edit it here, or later on the cosigner's own card.")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = accountText,
                        onValueChange = { value ->
                            accountText = value.filter { it.isDigit() }.trimStart('0').ifEmpty { "0" }
                        },
                        label = { Text("Account index") },
                        supportingText = { Text("Usually 0 unless you're intentionally using a separate account") },
                        singleLine = true,
                        enabled = customPath.isBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    val previewPath = accountText.toIntOrNull()
                        ?.let { runCatching { defaultCosignerDerivationPath(vaultNetwork, scriptType, it) }.getOrNull() }
                    if (customPath.isBlank() && previewPath != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        MegaMonoText("Path: $previewPath")
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = customPath,
                        onValueChange = { customPath = it },
                        label = { Text("Custom derivation path (advanced, optional)") },
                        placeholder = { Text("48'/0'/0'/2'") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (error != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(error, style = MaterialTheme.typography.bodyMedium, color = MegaError)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    MegaPrimaryButton(
                        text = "Add Cosigner",
                        enabled = fingerprint.length == 8,
                        onClick = {
                            onComplete(fingerprint, accountText.toIntOrNull(), customPath.takeIf { it.isNotBlank() })
                        },
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                MegaSecondaryButton(text = "Cancel", onClick = onCancel)
            }
        },
        confirmButton = {},
    )
}

/**
 * Lets a filled slot's master fingerprint be corrected after the fact —
 * the counterpart to CompleteCosignerInfoDialog defaulting a bare-xpub
 * import's fingerprint to the "00000000" unknown-origin placeholder
 * instead of requiring it up front. The xpub and derivation path aren't
 * editable here; only the fingerprint, since that's the one field that
 * genuinely can't be derived from the key itself (see
 * completeBareCosignerExtendedKey's doc comment).
 */
@Composable
private fun EditFingerprintDialog(
    currentValue: String,
    onConfirm: (fingerprint: String) -> Unit,
    onCancel: () -> Unit,
) {
    var fingerprint by remember { mutableStateOf(currentValue) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Edit Fingerprint") },
        text = {
            Column {
                Text(
                    "The master fingerprint identifies which signing device this cosigner belongs to. " +
                        "Check it against the exporting wallet or hardware device before changing it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { value ->
                        fingerprint = value.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(8)
                    },
                    label = { Text("Master fingerprint") },
                    placeholder = { Text("8 hex characters, e.g. 73c5da0a") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(fingerprint) },
                enabled = fingerprint.length == 8,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}

@Composable
private fun SlotActionIcons(onBeginFillSlot: () -> Unit, onScanSlot: () -> Unit, onPaste: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBeginFillSlot) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Add saved session key",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onScanSlot) {
            Icon(
                imageVector = Icons.Filled.CameraAlt,
                contentDescription = "Scan extended public key or descriptor QR",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onPaste) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Paste descriptor fragment",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
