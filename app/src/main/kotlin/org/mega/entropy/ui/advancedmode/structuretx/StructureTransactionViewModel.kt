package org.mega.entropy.ui.advancedmode.structuretx

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.mega.entropycore.PsbtOutputPlan
import org.mega.entropycore.deriveAddressFromExtendedPublicKey
import org.mega.entropycore.deriveWalletAddress
import org.mega.entropycore.estimateSplitTransactionFeeSats
import org.mega.entropycore.harvestOwnedInputsForStructuring
import org.mega.entropycore.restructurePsbt

/** A native SegWit output below this value isn't relayed/mined by default.
 * A computed change output below this is folded into the fee instead of
 * being created. */
private const val DUST_SATS = 546L

enum class DestinationWalletChoice { SAME_AS_SOURCE, ANOTHER_WALLET }

data class StructureTransactionUiState(
    val splitAmountBtc: String = "",
    val feeRateSatsPerVByte: String = "",
    val rbf: Boolean = true,
    val startReceiveIndex: String = "0",
    val changeIndex: String = "0",
    val destinationChoice: DestinationWalletChoice = DestinationWalletChoice.SAME_AS_SOURCE,
    val destinationXpub: String = "",
    val error: String? = null,
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
 * This flow's whole point is that MEGA never needs the user to type a
 * source UTXO's txid/vout/amount/index by hand: the source transaction
 * was already scanned (an ordinary transaction built in Sparrow, which —
 * unlike MEGA — has real blockchain access), so its real inputs are
 * harvested directly from that PSBT via entropy-core's
 * harvestOwnedInputsForStructuring. This class only owns the SPLIT
 * parameters (how much per output, fee rate, indices, destination) and
 * the resulting split-count/change arithmetic — UI business logic, not
 * cryptography, which stays in entropy-core (harvestOwnedInputsForStructuring,
 * deriveWalletAddress, deriveAddressFromExtendedPublicKey,
 * estimateSplitTransactionFeeSats, restructurePsbt).
 */
class StructureTransactionViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(StructureTransactionUiState())
    val uiState: StateFlow<StructureTransactionUiState> = _uiState.asStateFlow()

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
     * Hub, so a previous attempt's amounts never leak into a new one
     * (same reasoning as MultisigVaultViewModel.resetSession). */
    fun reset() {
        _uiState.value = StructureTransactionUiState()
    }

    /**
     * Harvests [originalPsbtBytes]'s real inputs (this device's own, per
     * [harvestOwnedInputsForStructuring]), plans how many split outputs
     * fit, derives every destination/change script, and builds a
     * restructured unsigned PSBT — or sets
     * [StructureTransactionUiState.error] and does nothing else. Never
     * touches a private key beyond what harvesting/deriving themselves
     * need (BIP32 derivation only — no signing happens here).
     */
    fun structureTransaction(originalPsbtBytes: ByteArray, mnemonicWords: List<String>, passphrase: String) {
        val state = _uiState.value
        val result = runCatching { plan(originalPsbtBytes, state, mnemonicWords, passphrase) }
        result.fold(
            onSuccess = { psbtBytes -> _uiState.update { it.copy(builtPsbtBytes = psbtBytes, error = null) } },
            onFailure = { e -> _uiState.update { it.copy(error = e.message ?: "Could not structure this transaction.") } },
        )
    }

    private fun plan(originalPsbtBytes: ByteArray, state: StructureTransactionUiState, mnemonicWords: List<String>, passphrase: String): ByteArray {
        val harvested = harvestOwnedInputsForStructuring(originalPsbtBytes, mnemonicWords, passphrase)
        val network = harvested.network
        val account = harvested.account

        val splitAmountSats = parseBtcToSats(state.splitAmountBtc, "Split amount")
        require(splitAmountSats >= DUST_SATS) { "Split amount must be at least $DUST_SATS sats (the dust limit)." }
        val feeRate = state.feeRateSatsPerVByte.toDoubleOrNull()
            ?: throw IllegalArgumentException("Enter a valid fee rate (sats/vByte).")
        require(feeRate > 0.0) { "Fee rate must be greater than zero." }
        val startIndex = state.startReceiveIndex.toIntOrNull() ?: throw IllegalArgumentException("Enter a valid starting receive index.")
        val changeIndex = state.changeIndex.toIntOrNull() ?: throw IllegalArgumentException("Enter a valid change index.")

        val totalInputSats = harvested.totalAmountSats
        val inputCount = harvested.inputs.size
        val maxSplits = (totalInputSats / splitAmountSats).toInt()
        require(maxSplits >= 1) { "Not enough balance to create even one $splitAmountSats-sat output at this fee rate." }

        // Try the largest split count first, then fall back to fewer splits
        // — each is checked both with a change output and without (folding
        // a below-dust remainder into the fee), and the first one that
        // leaves a non-negative remainder wins.
        var chosenSplitCount = 0
        var chosenChangeSats = 0L
        for (splitCount in maxSplits downTo 1) {
            val feeWithChange = estimateSplitTransactionFeeSats(inputCount, splitCount + 1, feeRate)
            val changeWithFee = totalInputSats - splitCount.toLong() * splitAmountSats - feeWithChange
            if (changeWithFee >= DUST_SATS) {
                chosenSplitCount = splitCount
                chosenChangeSats = changeWithFee
                break
            }
            val feeNoChange = estimateSplitTransactionFeeSats(inputCount, splitCount, feeRate)
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
                    deriveWalletAddress(mnemonicWords, passphrase, network, account, chain = 0, index = index)
                DestinationWalletChoice.ANOTHER_WALLET -> {
                    val xpub = state.destinationXpub.trim()
                    require(xpub.isNotEmpty()) { "Enter or scan a destination extended public key." }
                    deriveAddressFromExtendedPublicKey(xpub, network, chain = 0, index = index)
                }
            }
            PsbtOutputPlan(amountSats = splitAmountSats, scriptPubKey = derived.scriptPubKey)
        }

        val outputs = if (chosenChangeSats > 0L) {
            val change = deriveWalletAddress(mnemonicWords, passphrase, network, account, chain = 1, index = changeIndex)
            destinationOutputs + PsbtOutputPlan(amountSats = chosenChangeSats, scriptPubKey = change.scriptPubKey, changeDerivation = change.derivation)
        } else {
            destinationOutputs
        }

        return restructurePsbt(harvested, outputs, state.rbf)
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
