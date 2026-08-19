package org.mega.entropy.ui.advancedmode.structuretx

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.mega.entropycore.PsbtInputPlan
import org.mega.entropycore.PsbtOutputPlan
import org.mega.entropycore.WalletNetwork
import org.mega.entropycore.buildUnsignedPsbt
import org.mega.entropycore.deriveAddressFromExtendedPublicKey
import org.mega.entropycore.deriveWalletAddress
import org.mega.entropycore.estimateSplitTransactionFeeSats

/** A native SegWit output below this value isn't relayed/mined by default
 * — matches the dust limit MEGA's other native-SegWit-only tooling already
 * assumes. A computed change output below this is folded into the fee
 * instead of being created. */
private const val DUST_SATS = 546L

/** One manually-entered source UTXO, as raw (unvalidated) text — this
 * screen's whole point is that MEGA cannot look these up itself. */
data class UtxoEntry(
    val id: Long,
    val txid: String = "",
    val vout: String = "",
    val amountBtc: String = "",
    val receiveIndex: String = "",
)

enum class DestinationWalletChoice { SAME_AS_SOURCE, ANOTHER_WALLET }

data class StructureTransactionUiState(
    val network: WalletNetwork = WalletNetwork.MAINNET,
    val account: String = "0",
    val utxos: List<UtxoEntry> = listOf(UtxoEntry(id = 0)),
    val nextUtxoId: Long = 1,
    val splitAmountBtc: String = "",
    val feeRateSatsPerVByte: String = "",
    val rbf: Boolean = true,
    val startReceiveIndex: String = "0",
    val changeIndex: String = "0",
    val destinationChoice: DestinationWalletChoice = DestinationWalletChoice.SAME_AS_SOURCE,
    val destinationXpub: String = "",
    val error: String? = null,
    val isBuilding: Boolean = false,
    /** Set once "Structure Transaction" succeeds — the nav layer reads
     * this, hands it to PsbtReviewScreen, then calls [consumeBuiltPsbt]
     * so a configuration change doesn't re-navigate on its own. */
    val builtPsbtBytes: ByteArray? = null,
)

/**
 * Owns the "Structure a Transaction" form's state across navigating away
 * to scan a destination xpub and back — the same reason
 * MultisigVaultViewModel exists as a single instance scoped to
 * MegaNavGraph rather than being `remember`ed inside the form screen
 * itself, which would lose every typed field on that round trip.
 *
 * All the actual PSBT/BIP32 work is delegated to entropy-core
 * (deriveWalletAddress, deriveAddressFromExtendedPublicKey,
 * estimateSplitTransactionFeeSats, buildUnsignedPsbt) — this class only
 * owns form state and the split-count/change arithmetic, which is UI
 * business logic, not cryptography.
 */
class StructureTransactionViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(StructureTransactionUiState())
    val uiState: StateFlow<StructureTransactionUiState> = _uiState.asStateFlow()

    fun setNetwork(network: WalletNetwork) = _uiState.update { it.copy(network = network, error = null) }
    fun setAccount(text: String) = _uiState.update { it.copy(account = text.filter { c -> c.isDigit() }, error = null) }

    fun addUtxo() = _uiState.update { state ->
        state.copy(utxos = state.utxos + UtxoEntry(id = state.nextUtxoId), nextUtxoId = state.nextUtxoId + 1, error = null)
    }

    fun removeUtxo(id: Long) = _uiState.update { state ->
        state.copy(utxos = state.utxos.filterNot { it.id == id }.ifEmpty { listOf(UtxoEntry(id = state.nextUtxoId)) }, error = null)
    }

    fun updateUtxo(id: Long, update: (UtxoEntry) -> UtxoEntry) = _uiState.update { state ->
        state.copy(utxos = state.utxos.map { if (it.id == id) update(it) else it }, error = null)
    }

    fun setSplitAmountBtc(text: String) = _uiState.update { it.copy(splitAmountBtc = text, error = null) }
    fun setFeeRate(text: String) = _uiState.update { it.copy(feeRateSatsPerVByte = text, error = null) }
    fun setRbf(enabled: Boolean) = _uiState.update { it.copy(rbf = enabled) }
    fun setStartReceiveIndex(text: String) = _uiState.update { it.copy(startReceiveIndex = text.filter { c -> c.isDigit() }, error = null) }
    fun setChangeIndex(text: String) = _uiState.update { it.copy(changeIndex = text.filter { c -> c.isDigit() }, error = null) }
    fun setDestinationChoice(choice: DestinationWalletChoice) = _uiState.update { it.copy(destinationChoice = choice, error = null) }
    fun setDestinationXpub(text: String) = _uiState.update { it.copy(destinationXpub = text.trim(), error = null) }
    fun onXpubScanned(text: String) = _uiState.update { it.copy(destinationXpub = text.trim(), error = null) }
    fun consumeBuiltPsbt() = _uiState.update { it.copy(builtPsbtBytes = null) }
    fun clearError() = _uiState.update { it.copy(error = null) }

    /** Resets every field — called when the flow is entered fresh from the
     * Hub, so a previous attempt's UTXOs/amounts never leak into a new
     * one (same reasoning as MultisigVaultViewModel.resetSession). */
    fun reset() {
        _uiState.value = StructureTransactionUiState()
    }

    /**
     * Validates the whole form, plans how many split outputs fit, derives
     * every input/output script, and builds the unsigned PSBT — or sets
     * [StructureTransactionUiState.error] and does nothing else. Never
     * touches a private key beyond what [deriveWalletAddress] itself
     * needs (BIP32 derivation only — no signing happens here).
     */
    fun structureTransaction(mnemonicWords: List<String>, passphrase: String) {
        val state = _uiState.value
        val result = runCatching { plan(state, mnemonicWords, passphrase) }
        result.fold(
            onSuccess = { psbtBytes -> _uiState.update { it.copy(builtPsbtBytes = psbtBytes, error = null) } },
            onFailure = { e -> _uiState.update { it.copy(error = e.message ?: "Could not structure this transaction.") } },
        )
    }

    private fun plan(state: StructureTransactionUiState, mnemonicWords: List<String>, passphrase: String): ByteArray {
        val account = state.account.toIntOrNull() ?: throw IllegalArgumentException("Enter a valid account index.")
        val splitAmountSats = parseBtcToSats(state.splitAmountBtc, "Split amount")
        require(splitAmountSats >= DUST_SATS) { "Split amount must be at least $DUST_SATS sats (the dust limit)." }
        val feeRate = state.feeRateSatsPerVByte.toDoubleOrNull()
            ?: throw IllegalArgumentException("Enter a valid fee rate (sats/vByte).")
        require(feeRate > 0.0) { "Fee rate must be greater than zero." }
        val startIndex = state.startReceiveIndex.toIntOrNull() ?: throw IllegalArgumentException("Enter a valid starting receive index.")
        val changeIndex = state.changeIndex.toIntOrNull() ?: throw IllegalArgumentException("Enter a valid change index.")

        if (state.utxos.isEmpty()) throw IllegalArgumentException("Add at least one source UTXO.")
        val inputs = state.utxos.map { entry ->
            val txid = entry.txid.trim()
            require(txid.length == 64 && txid.all { it in "0123456789abcdefABCDEF" }) {
                "Each UTXO's txid must be 64 hex characters."
            }
            val vout = entry.vout.toLongOrNull() ?: throw IllegalArgumentException("Each UTXO needs a valid output index (vout).")
            val amountSats = parseBtcToSats(entry.amountBtc, "UTXO amount")
            val receiveIndex = entry.receiveIndex.toIntOrNull()
                ?: throw IllegalArgumentException("Each UTXO needs the receive-address index it belongs to.")
            val derived = deriveWalletAddress(mnemonicWords, passphrase, state.network, account, chain = 0, index = receiveIndex)
            PsbtInputPlan(txid = txid, vout = vout, amountSats = amountSats, scriptPubKey = derived.scriptPubKey, derivation = derived.derivation)
        }
        val duplicateOutpoints = inputs.map { "${it.txid.lowercase()}:${it.vout}" }
        require(duplicateOutpoints.toSet().size == duplicateOutpoints.size) { "The same UTXO (txid:vout) was entered more than once." }

        val totalInputSats = inputs.sumOf { it.amountSats }
        val maxSplits = (totalInputSats / splitAmountSats).toInt()
        require(maxSplits >= 1) { "Not enough balance to create even one $splitAmountSats-sat output at this fee rate." }

        // Try the largest split count first, then fall back to fewer splits
        // — each is checked both with a change output and without (folding
        // a below-dust remainder into the fee), and the first one that
        // leaves a non-negative remainder wins.
        var chosenSplitCount = 0
        var chosenChangeSats = 0L
        for (splitCount in maxSplits downTo 1) {
            val feeWithChange = estimateSplitTransactionFeeSats(inputs.size, splitCount + 1, feeRate)
            val changeWithFee = totalInputSats - splitCount.toLong() * splitAmountSats - feeWithChange
            if (changeWithFee >= DUST_SATS) {
                chosenSplitCount = splitCount
                chosenChangeSats = changeWithFee
                break
            }
            val feeNoChange = estimateSplitTransactionFeeSats(inputs.size, splitCount, feeRate)
            val remainderNoChange = totalInputSats - splitCount.toLong() * splitAmountSats - feeNoChange
            if (remainderNoChange >= 0L) {
                chosenSplitCount = splitCount
                chosenChangeSats = 0L
                break
            }
        }
        require(chosenSplitCount >= 1) { "Not enough balance to cover $maxSplits×$splitAmountSats sats plus fees at this rate — try a lower split amount or fee rate." }

        val destinationOutputs = (0 until chosenSplitCount).map { offset ->
            val index = startIndex + offset
            val derived = when (state.destinationChoice) {
                DestinationWalletChoice.SAME_AS_SOURCE ->
                    deriveWalletAddress(mnemonicWords, passphrase, state.network, account, chain = 0, index = index)
                DestinationWalletChoice.ANOTHER_WALLET -> {
                    val xpub = state.destinationXpub.trim()
                    require(xpub.isNotEmpty()) { "Enter or scan a destination extended public key." }
                    deriveAddressFromExtendedPublicKey(xpub, state.network, chain = 0, index = index)
                }
            }
            PsbtOutputPlan(amountSats = splitAmountSats, scriptPubKey = derived.scriptPubKey)
        }

        val outputs = if (chosenChangeSats > 0L) {
            val change = deriveWalletAddress(mnemonicWords, passphrase, state.network, account, chain = 1, index = changeIndex)
            destinationOutputs + PsbtOutputPlan(amountSats = chosenChangeSats, scriptPubKey = change.scriptPubKey, changeDerivation = change.derivation)
        } else {
            destinationOutputs
        }

        return buildUnsignedPsbt(inputs, outputs, state.rbf)
    }

    private fun parseBtcToSats(text: String, fieldName: String): Long {
        val trimmed = text.trim()
        val value = trimmed.toDoubleOrNull() ?: throw IllegalArgumentException("$fieldName must be a valid BTC amount.")
        require(value > 0.0) { "$fieldName must be greater than zero." }
        // Round to the nearest sat rather than truncating — a user-typed
        // decimal BTC amount (e.g. "0.25") can't always be represented
        // exactly in floating point, and truncation would systematically
        // under-count by up to a sat.
        return Math.round(value * 100_000_000.0)
    }
}
