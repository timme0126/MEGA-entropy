package org.mega.entropy.ui.advancedmode.structuretx

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
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
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaPassphraseCard
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.WalletNetwork

/**
 * "Structure a Transaction" — Advanced Mode's UTXO-split builder. Every
 * field here is something MEGA cannot look up itself (it never touches
 * the blockchain): the user supplies each source UTXO by hand, and MEGA
 * derives every split/change address, plans how many equal-sized outputs
 * fit, builds the unsigned PSBT, then hands off to the SAME
 * review → sign → result screens the "Sign PSBT" flow already uses — see
 * StructureTransactionViewModel.structureTransaction and
 * buildUnsignedPsbt (entropy-core) for where the actual construction
 * happens.
 */
@Composable
fun StructureTransactionScreen(
    viewModel: StructureTransactionViewModel,
    mnemonicWords: List<String>,
    passphrase: String,
    allowScreenshots: Boolean,
    onBack: () -> Unit,
    onScanDestinationXpub: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)
    val state by viewModel.uiState.collectAsState()

    MegaInfoScaffold(title = "Structure a Transaction", onBack = onBack) {
        MegaPassphraseCard(passphrase)

        MegaCard(title = "Source Wallet") {
            Text("Network", style = MaterialTheme.typography.labelLarge)
            RadioRow("Mainnet", state.network == WalletNetwork.MAINNET) { viewModel.setNetwork(WalletNetwork.MAINNET) }
            RadioRow("Testnet", state.network == WalletNetwork.TESTNET) { viewModel.setNetwork(WalletNetwork.TESTNET) }
            OutlinedTextField(
                value = state.account,
                onValueChange = viewModel::setAccount,
                label = { Text("Account index") },
                supportingText = { Text("Usually 0, native SegWit (BIP84)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        MegaCard(title = "Source UTXOs") {
            Text(
                "MEGA has no blockchain access — enter each UTXO exactly as shown by your watch-only " +
                    "wallet (e.g. Sparrow's UTXOs tab): its txid, output index (vout), amount, and which " +
                    "receive-address index that UTXO belongs to.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.utxos.forEachIndexed { index, utxo ->
                UtxoRow(
                    index = index,
                    utxo = utxo,
                    canRemove = state.utxos.size > 1,
                    onChange = { update -> viewModel.updateUtxo(utxo.id, update) },
                    onRemove = { viewModel.removeUtxo(utxo.id) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = viewModel::addUtxo),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Add another UTXO", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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

        if (state.isBuilding) {
            CircularProgressIndicator()
        } else {
            MegaPrimaryButton(
                text = "Structure Transaction",
                onClick = { viewModel.structureTransaction(mnemonicWords, passphrase) },
            )
        }
        MegaSecondaryButton(text = "Cancel", onClick = onBack)
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

@Composable
private fun UtxoRow(
    index: Int,
    utxo: UtxoEntry,
    canRemove: Boolean,
    onChange: ((UtxoEntry) -> UtxoEntry) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("UTXO ${index + 1}", style = MaterialTheme.typography.labelLarge)
        if (canRemove) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove UTXO ${index + 1}")
            }
        }
    }
    OutlinedTextField(
        value = utxo.txid,
        onValueChange = { text -> onChange { it.copy(txid = text.trim()) } },
        label = { Text("txid") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = utxo.vout,
        onValueChange = { text -> onChange { it.copy(vout = text.filter { c -> c.isDigit() }) } },
        label = { Text("vout") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = utxo.amountBtc,
        onValueChange = { text -> onChange { it.copy(amountBtc = text) } },
        label = { Text("Amount (BTC)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = utxo.receiveIndex,
        onValueChange = { text -> onChange { it.copy(receiveIndex = text.filter { c -> c.isDigit() }) } },
        label = { Text("Receive-address index this UTXO belongs to") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
