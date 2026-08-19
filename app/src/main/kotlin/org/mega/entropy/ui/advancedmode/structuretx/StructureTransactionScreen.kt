package org.mega.entropy.ui.advancedmode.structuretx

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.advancedmode.PsbtAsyncState
import org.mega.entropy.ui.advancedmode.producePsbtAsync
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPassphraseCard
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.HarvestedPsbtInputs
import org.mega.entropycore.WalletNetwork
import org.mega.entropycore.harvestOwnedInputsForStructuring

/**
 * "Structure a Transaction" — the second half of "Sign PSBT" when its
 * "Structure this transaction" checkbox was checked on the Hub. The PSBT
 * just scanned (an ordinary transaction built in Sparrow, or any
 * watch-only wallet tracking this same seed — which, unlike MEGA, has
 * real blockchain access and already knows exactly which UTXOs exist) is
 * never signed as-is: its real input(s) are harvested here
 * (harvestOwnedInputsForStructuring — no manual txid/vout/amount entry),
 * its original outputs are discarded, and new split outputs are built
 * instead. The result then flows into the SAME review → sign → result
 * screens the ordinary "Sign PSBT" flow uses.
 */
@Composable
fun StructureTransactionScreen(
    viewModel: StructureTransactionViewModel,
    originalPsbtBytes: ByteArray,
    mnemonicWords: List<String>,
    passphrase: String,
    allowScreenshots: Boolean,
    onBack: () -> Unit,
    onScanDestinationXpub: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)
    val state by viewModel.uiState.collectAsState()

    // BIP32 derivation for every input, expensive enough to run off the
    // Compose main thread — see producePsbtAsync's own doc.
    val harvestState = producePsbtAsync(originalPsbtBytes, mnemonicWords, passphrase) {
        runCatching { harvestOwnedInputsForStructuring(originalPsbtBytes, mnemonicWords, passphrase) }
    }
    val harvestResult: Result<HarvestedPsbtInputs>? = when (harvestState) {
        PsbtAsyncState.Loading -> null
        is PsbtAsyncState.Success -> harvestState.value
        is PsbtAsyncState.Failed -> Result.failure(harvestState.error)
    }

    MegaInfoScaffold(title = "Structure a Transaction", onBack = onBack) {
        MegaPassphraseCard(passphrase)

        when {
            harvestResult == null -> {
                MegaCard(title = "Reading Scanned Transaction") {
                    CircularProgressIndicator()
                }
            }
            harvestResult.isFailure -> {
                MegaCard(title = "Could Not Use This Transaction") {
                    Text(
                        text = harvestResult.exceptionOrNull()?.message
                            ?: "None of this transaction's inputs belong to this device's key.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MegaError,
                    )
                }
                MegaSecondaryButton(text = "Back", onClick = onBack)
            }
            else -> {
                val harvested = harvestResult.getOrThrow()
                MegaCard(title = "Source (from scanned transaction)") {
                    Text(
                        "${harvested.inputs.size} input(s) found, totaling ${"%.8f".format(harvested.totalAmountSats / 100_000_000.0)} BTC.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    MegaMonoText("Account ${harvested.account} · ${if (harvested.network == WalletNetwork.MAINNET) "Mainnet" else "Testnet"}")
                    Text(
                        "This transaction's own outputs will be discarded and replaced by the split below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (harvested.hasUnverifiedOriginFingerprint) {
                    MegaCard(title = "Unverified Master Fingerprint") {
                        Text(
                            "One or more inputs did not record a master fingerprint (00000000) — MEGA " +
                                "independently matched the derived public key and path instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                MegaCard(title = "Split") {
                    OutlinedTextField(
                        value = state.splitAmountBtc,
                        onValueChange = viewModel::setSplitAmountBtc,
                        label = { Text("Split amount (BTC per output)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.feeRateSatsPerVByte,
                        onValueChange = viewModel::setFeeRate,
                        label = { Text("Fee rate (sats/vByte)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.rbf, onCheckedChange = viewModel::setRbf)
                        Text("Enable Replace-By-Fee (RBF)", style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedTextField(
                        value = state.startReceiveIndex,
                        onValueChange = viewModel::setStartReceiveIndex,
                        label = { Text("Starting receive-address index for the split outputs") },
                        supportingText = { Text("E.g. 0 fills indices 0..N-1; 9 fills 10..N+9, skipping 0-9") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.changeIndex,
                        onValueChange = viewModel::setChangeIndex,
                        label = { Text("Change-address index") },
                        supportingText = { Text("Use the next unused CHANGE index shown under Addresses in Sparrow") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                MegaCard(title = "Destination Wallet") {
                    RadioRow("Same as Source Wallet (self-split)", state.destinationChoice == DestinationWalletChoice.SAME_AS_SOURCE) {
                        viewModel.setDestinationChoice(DestinationWalletChoice.SAME_AS_SOURCE)
                    }
                    RadioRow("Another wallet (xpub)", state.destinationChoice == DestinationWalletChoice.ANOTHER_WALLET) {
                        viewModel.setDestinationChoice(DestinationWalletChoice.ANOTHER_WALLET)
                    }
                    if (state.destinationChoice == DestinationWalletChoice.ANOTHER_WALLET) {
                        OutlinedTextField(
                            value = state.destinationXpub,
                            onValueChange = viewModel::setDestinationXpub,
                            label = { Text("Destination account xpub/zpub") },
                            singleLine = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable(onClick = onScanDestinationXpub),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Filled.CameraAlt, contentDescription = null)
                            Text("Scan xpub QR code", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                val currentError = state.error
                if (currentError != null) {
                    MegaCard {
                        Text(currentError, style = MaterialTheme.typography.bodyMedium, color = MegaError)
                    }
                }

                MegaPrimaryButton(
                    text = "Structure Transaction",
                    onClick = { viewModel.structureTransaction(originalPsbtBytes, mnemonicWords, passphrase) },
                )
                MegaSecondaryButton(text = "Cancel", onClick = onBack)
            }
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
