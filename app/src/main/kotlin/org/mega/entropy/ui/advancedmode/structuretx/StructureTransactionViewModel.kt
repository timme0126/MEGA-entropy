package org.mega.entropy.ui.advancedmode.structuretx

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mega.entropycore.PsbtOutputPlan
import org.mega.entropycore.deriveAddressFromExtendedPublicKey
import org.mega.entropycore.deriveWalletAddress
import org.mega.entropycore.estimateSplitTransactionFeeSats
import org.mega.entropycore.harvestOwnedInputsForStructuring
import org.mega.entropycore.restructurePsbt

/** A native SegWit output below this value isn't relayed/mined by default.
 * A leftover remainder below this is folded into the fee instead of being
 * created as its own output — Bitcoin has no way to create a sub-dust
 * output at all, so this is a hard floor, not a preference. */
private const val DUST_SATS = 546L

enum class DestinationWalletChoice { SAME_AS_SOURCE, ANOTHER_WALLET }

/** One output MEGA is about to (or just did) create, shown to the user
 * BEFORE handing off to the review/sign screens — so a starting-index
 * mistake or an unexpectedly-placed remainder is visible and checkable
 * against another wallet (e.g. Sparrow) up front, not after the fact. */
data class StructuredOutputPreview(
    val derivationIndex: Int,
    val amountSats: Long,
    /** True only for the trailing leftover-clearing output — see
     * [StructureTransactionViewModel]'s own doc for why this always goes
     * to the NEXT sequential destination index rather than a separate
     * change address. */
    val isRemainder: Boolean,
)

data class StructureTransactionUiState(
    val splitAmountBtc: String = "",
    val feeRateSatsPerVByte: String = "",
    val rbf: Boolean = true,
    val startReceiveIndex: String = "0",
    val destinationChoice: DestinationWalletChoice = DestinationWalletChoice.SAME_AS_SOURCE,
    val destinationXpub: String = "",
    val error: String? = null,
    /** True while [structureTransaction]'s work (harvesting + deriving
     * every output's address, potentially dozens of BIP32 derivations) is
     * running off the main thread — see that function's own doc for why
     * this exists: without it, a large split (dozens of outputs) freezes
     * the UI long enough to trigger Android's "app isn't responding"
     * dialog. */
    val isBuilding: Boolean = false,
    /** Set once "Structure Transaction" succeeds, alongside
     * [outputPreview] — the screen shows the preview and requires an
     * explicit "Continue to Review" tap before the nav layer hands
     * [builtPsbtBytes] to PsbtReviewScreen and calls [consumeBuiltPsbt],
     * so a configuration change can't silently re-navigate on its own. */
    val builtPsbtBytes: ByteArray? = null,
    val outputPreview: List<StructuredOutputPreview> = emptyList(),
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
 * parameters (how much per output, fee rate, start index, destination)
 * and the resulting split-count/remainder arithmetic — UI business logic,
 * not cryptography, which stays in entropy-core
 * (harvestOwnedInputsForStructuring, deriveWalletAddress,
 * deriveAddressFromExtendedPublicKey, estimateSplitTransactionFeeSats,
 * restructurePsbt).
 *
 * There is deliberately NO separate change-address concept here: any
 * leftover after the largest possible number of equal-sized outputs is
 * itself just one more output, at the NEXT sequential index in the same
 * destination chain — whether the destination is the source wallet
 * itself (self-split) or a different one entirely (consolidation into a
 * new wallet). This fully clears the source wallet's balance rather than
 * leaving a residual change output behind in it, and keeps every output
 * — including the leftover — visible as one uniform, ordered list in
 * [StructureTransactionUiState.outputPreview] instead of a separately-
 * tracked "change" that's easy to lose track of.
 */
class StructureTransactionViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(StructureTransactionUiState())
    val uiState: StateFlow<StructureTransactionUiState> = _uiState.asStateFlow()

    fun setSplitAmountBtc(text: String) = _uiState.update { it.copy(splitAmountBtc = text, error = null) }
    fun setFeeRate(text: String) = _uiState.update { it.copy(feeRateSatsPerVByte = text, error = null) }
    fun setRbf(enabled: Boolean) = _uiState.update { it.copy(rbf = enabled) }
    fun setStartReceiveIndex(text: String) = _uiState.update { it.copy(startReceiveIndex = text.filter { c -> c.isDigit() }, error = null) }
    fun setDestinationChoice(choice: DestinationWalletChoice) = _uiState.update { it.copy(destinationChoice = choice, error = null) }
    fun setDestinationXpub(text: String) = _uiState.update { it.copy(destinationXpub = text.trim(), error = null) }
    fun onXpubScanned(text: String) = _uiState.update { it.copy(destinationXpub = text.trim(), error = null) }
    fun consumeBuiltPsbt() = _uiState.update { it.copy(builtPsbtBytes = null, outputPreview = emptyList()) }
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
     * fit, derives every destination script, and builds a restructured
     * unsigned PSBT — or sets [StructureTransactionUiState.error] and
     * does nothing else. Never touches a private key beyond what
     * harvesting/deriving themselves need (BIP32 derivation only — no
     * signing happens here).
     *
     * Runs on [Dispatchers.Default], off the Compose main thread: a large
     * split (the field test that prompted this — 66 outputs) means dozens
     * of independent BIP32 derivations (this app's from-scratch,
     * non-hardware-accelerated secp256k1 implementation), which is
     * comfortably enough synchronous work on the main thread to trigger
     * Android's "app isn't responding" dialog. [MutableStateFlow.update]
     * is safe to call from any thread, so no dispatch back to Main is
     * needed for the result.
     */
    fun structureTransaction(originalPsbtBytes: ByteArray, mnemonicWords: List<String>, passphrase: String) {
        val stateAtSubmit = _uiState.value
        _uiState.update { it.copy(isBuilding = true, error = null) }
        viewModelScope.launch(Dispatchers.Default) {
            val result = runCatching { plan(originalPsbtBytes, stateAtSubmit, mnemonicWords, passphrase) }
            result.fold(
                onSuccess = { planResult ->
                    _uiState.update {
                        it.copy(builtPsbtBytes = planResult.psbtBytes, outputPreview = planResult.outputPreview, error = null, isBuilding = false)
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(error = e.message ?: "Could not structure this transaction.", isBuilding = false) }
                },
            )
        }
    }

    private class PlanResult(val psbtBytes: ByteArray, val outputPreview: List<StructuredOutputPreview>)

    private fun plan(originalPsbtBytes: ByteArray, state: StructureTransactionUiState, mnemonicWords: List<String>, passphrase: String): PlanResult {
        val harvested = harvestOwnedInputsForStructuring(originalPsbtBytes, mnemonicWords, passphrase)
        val network = harvested.network
        val account = harvested.account

        val splitAmountSats = parseBtcToSats(state.splitAmountBtc, "Split amount")
        require(splitAmountSats >= DUST_SATS) { "Split amount must be at least $DUST_SATS sats (the dust limit)." }
        val feeRate = state.feeRateSatsPerVByte.toDoubleOrNull()
            ?: throw IllegalArgumentException("Enter a valid fee rate (sats/vByte).")
        require(feeRate > 0.0) { "Fee rate must be greater than zero." }
        val startIndex = state.startReceiveIndex.toIntOrNull() ?: throw IllegalArgumentException("Enter a valid starting receive index.")

        val totalInputSats = harvested.totalAmountSats
        val inputCount = harvested.inputs.size
        val maxSplits = (totalInputSats / splitAmountSats).toInt()
        require(maxSplits >= 1) { "Not enough balance to create even one $splitAmountSats-sat output at this fee rate." }

        // Try the largest split count first, then fall back to fewer
        // splits — each is checked both with a trailing remainder output
        // and without (folding a below-dust leftover into the fee), and
        // the first one that leaves a non-negative amount wins.
        var chosenSplitCount = 0
        var remainderSats = 0L
        for (splitCount in maxSplits downTo 1) {
            val feeWithRemainder = estimateSplitTransactionFeeSats(inputCount, splitCount + 1, feeRate)
            val remainder = totalInputSats - splitCount.toLong() * splitAmountSats - feeWithRemainder
            if (remainder >= DUST_SATS) {
                chosenSplitCount = splitCount
                remainderSats = remainder
                break
            }
            val feeNoRemainder = estimateSplitTransactionFeeSats(inputCount, splitCount, feeRate)
            val leftoverFoldedIntoFee = totalInputSats - splitCount.toLong() * splitAmountSats - feeNoRemainder
            if (leftoverFoldedIntoFee >= 0L) {
                chosenSplitCount = splitCount
                remainderSats = 0L
                break
            }
        }
        require(chosenSplitCount >= 1) { "Not enough balance to cover $maxSplits×$splitAmountSats sats plus fees at this rate — try a lower split amount or fee rate." }

        val hasRemainder = remainderSats > 0L
        val totalOutputCount = chosenSplitCount + if (hasRemainder) 1 else 0

        val outputsWithPreview = (0 until totalOutputCount).map { offset ->
            val index = startIndex + offset
            val isRemainder = hasRemainder && offset == chosenSplitCount
            val amount = if (isRemainder) remainderSats else splitAmountSats
            val derived = when (state.destinationChoice) {
                DestinationWalletChoice.SAME_AS_SOURCE ->
                    deriveWalletAddress(mnemonicWords, passphrase, network, account, chain = 0, index = index)
                DestinationWalletChoice.ANOTHER_WALLET -> {
                    val xpub = state.destinationXpub.trim()
                    require(xpub.isNotEmpty()) { "Enter or scan a destination extended public key." }
                    deriveAddressFromExtendedPublicKey(xpub, network, chain = 0, index = index)
                }
            }
            // Only mark the trailing remainder as "change" for display
            // purposes, and only when it actually pays back to THIS
            // device's own key (self-split) — deriveAddressFromExtendedPublicKey's
            // derivation is a placeholder never meant to be read (see its
            // own doc), so it must never be attached here regardless of
            // isRemainder.
            val changeDerivation = if (isRemainder && state.destinationChoice == DestinationWalletChoice.SAME_AS_SOURCE) {
                derived.derivation
            } else {
                null
            }
            PsbtOutputPlan(amountSats = amount, scriptPubKey = derived.scriptPubKey, changeDerivation = changeDerivation) to
                StructuredOutputPreview(derivationIndex = index, amountSats = amount, isRemainder = isRemainder)
        }

        val psbtBytes = restructurePsbt(harvested, outputsWithPreview.map { it.first }, state.rbf)
        return PlanResult(psbtBytes, outputsWithPreview.map { it.second })
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
