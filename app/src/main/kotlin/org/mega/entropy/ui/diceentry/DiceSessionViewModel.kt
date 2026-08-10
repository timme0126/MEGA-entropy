package org.mega.entropy.ui.diceentry

import androidx.lifecycle.ViewModel
import java.math.BigInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.mega.entropycore.MnemonicLength
import org.mega.entropycore.MnemonicResult
import org.mega.entropycore.RejectionResult
import org.mega.entropycore.accumulate
import org.mega.entropycore.calculateChunk
import org.mega.entropycore.calculateXDirect
import org.mega.entropycore.checkAcceptance
import org.mega.entropycore.deriveMnemonic
import org.mega.entropycore.mapRollsToBase6

const val ROLLS_PER_BATCH = 5

/** Everything shown on-screen for one finished 5-roll batch, so the UI can
 * display the full worked calculation per spec section 6. */
data class CompletedBatch(
    val batchNumber: Int, // 1-based
    val physicalRolls: List<Int>,
    val base6Digits: List<Int>,
    val chunk: Long,
    val previousX: BigInteger,
    val newX: BigInteger,
)

data class DiceSessionUiState(
    val mnemonicLength: MnemonicLength = MnemonicLength.TWENTY_FOUR_WORDS,
    val completedBatches: List<CompletedBatch> = emptyList(),
    val currentBatchRolls: List<Int> = emptyList(),
    val mnemonicResult: MnemonicResult? = null,
    // MnemonicResult.Success doesn't retain the X/T/6^N/2^bits comparison
    // (only MnemonicResult.Rejected does), but the Bias Check screen needs
    // to show that comparison either way — so it's tracked here too,
    // computed via the same public checkAcceptance() call the pipeline
    // itself uses. This never changes which branch was taken.
    val rejectionResult: RejectionResult? = null,
    // Non-null while a save is waiting on the user to set up a MEGA PIN
    // first (spec: saving any data requires a PIN to already exist).
    // MegaNavGraph sets this before routing to PIN_SETUP and reads/clears
    // it once setup completes, to perform the deferred save. The label
    // (mandatory, already validated non-blank by MegaLabelSessionDialog
    // before requestPendingSave is ever called) rides alongside it.
    val pendingSaveWithMnemonic: Boolean? = null,
    val pendingSaveLabel: String = "",
) {
    val totalRolls: Int get() = mnemonicLength.rollCount
    val totalBatches: Int get() = totalRolls / ROLLS_PER_BATCH
    val rollsEntered: Int get() = completedBatches.size * ROLLS_PER_BATCH + currentBatchRolls.size
    val currentBatchNumber: Int get() = (completedBatches.size + 1).coerceAtMost(totalBatches)
    val isSessionComplete: Boolean get() = completedBatches.size == totalBatches
    val allRolls: List<Int> get() = completedBatches.flatMap { it.physicalRolls } + currentBatchRolls
    val runningX: BigInteger get() = completedBatches.lastOrNull()?.newX ?: BigInteger.ZERO
}

/**
 * Holds one dice-rolling session's state in memory only — per spec section
 * 16, a session is ephemeral by default and is never written to disk (or to
 * SavedStateHandle/logs) unless the user explicitly chooses to save it later.
 * Every derived value (chunks, X, the final mnemonic) is recomputed from the
 * raw rolls by calling straight into :entropy-core, never invented here.
 *
 * Activity-scoped (see MegaNavGraph) rather than scoped to the dice-flow
 * nested graph, so the mnemonic-length choice screen — which sits just
 * outside that nested graph — can set it via [selectLength] before the
 * dice-entry screens (inside the nested graph) ever compose. Callers are
 * responsible for calling [resetSession] when starting a fresh flow;
 * [selectLength] itself resets, so choosing a length always starts clean.
 */
class DiceSessionViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DiceSessionUiState())
    val uiState: StateFlow<DiceSessionUiState> = _uiState.asStateFlow()

    fun selectLength(length: MnemonicLength) {
        _uiState.value = DiceSessionUiState(mnemonicLength = length)
    }

    /** Records one physical die outcome (1..6). Completes and appends a
     * batch once 5 rolls have accumulated; computes the final mnemonic once
     * all rolls for the chosen mnemonic length are in. */
    fun onRollEntered(physicalRoll: Int) {
        require(physicalRoll in 1..6) { "physicalRoll must be 1..6" }
        _uiState.update { state ->
            if (state.isSessionComplete) return@update state

            val newCurrentBatch = state.currentBatchRolls + physicalRoll
            if (newCurrentBatch.size < ROLLS_PER_BATCH) {
                return@update state.copy(currentBatchRolls = newCurrentBatch)
            }

            val base6Digits = mapRollsToBase6(newCurrentBatch)
            val chunk = calculateChunk(base6Digits)
            val previousX = state.runningX
            val newX = accumulate(previousX, chunk)
            val completed = CompletedBatch(
                batchNumber = state.completedBatches.size + 1,
                physicalRolls = newCurrentBatch,
                base6Digits = base6Digits,
                chunk = chunk,
                previousX = previousX,
                newX = newX,
            )
            val newCompletedBatches = state.completedBatches + completed
            val isNowComplete = newCompletedBatches.size == state.totalBatches
            val allRolls = newCompletedBatches.flatMap { it.physicalRolls }
            val result = if (isNowComplete) deriveMnemonic(allRolls, state.mnemonicLength) else null
            val rejection = if (isNowComplete) {
                checkAcceptance(
                    calculateXDirect(mapRollsToBase6(allRolls)),
                    state.mnemonicLength.rollCount,
                    state.mnemonicLength.entropyBits,
                )
            } else {
                null
            }
            state.copy(
                completedBatches = newCompletedBatches,
                currentBatchRolls = emptyList(),
                mnemonicResult = result,
                rejectionResult = rejection,
            )
        }
    }

    /** Removes the most recently entered roll, whether it's in the current
     * in-progress batch or the last completed batch (reopening it). Per spec
     * section 7: never silently alter an entered roll, only ever undo the
     * single most recent one. */
    fun undoLastRoll() {
        _uiState.update { state ->
            if (state.currentBatchRolls.isNotEmpty()) {
                state.copy(currentBatchRolls = state.currentBatchRolls.dropLast(1))
            } else if (state.completedBatches.isNotEmpty()) {
                val lastBatch = state.completedBatches.last()
                state.copy(
                    completedBatches = state.completedBatches.dropLast(1),
                    currentBatchRolls = lastBatch.physicalRolls.dropLast(1),
                    mnemonicResult = null,
                    rejectionResult = null,
                )
            } else {
                state
            }
        }
    }

    fun clearCurrentBatch() {
        _uiState.update { it.copy(currentBatchRolls = emptyList()) }
    }

    /** Discards every completed batch back to (and re-opening) the given
     * 1-indexed batch number, so its rolls can be re-entered. Per spec
     * section 7 this is the only supported way to "edit an earlier batch" —
     * MEGA never lets you silently overwrite a value in place, and warns
     * (in the UI layer) that this changes every value computed after it. */
    fun reopenBatch(batchNumber: Int) {
        _uiState.update { state ->
            val keep = state.completedBatches.filter { it.batchNumber < batchNumber }
            val reopened = state.completedBatches.firstOrNull { it.batchNumber == batchNumber }
            state.copy(
                completedBatches = keep,
                currentBatchRolls = reopened?.physicalRolls ?: state.currentBatchRolls,
                mnemonicResult = null,
                rejectionResult = null,
            )
        }
    }

    fun resetSession() {
        _uiState.value = DiceSessionUiState(mnemonicLength = _uiState.value.mnemonicLength)
    }

    fun requestPendingSave(withMnemonic: Boolean, label: String) {
        _uiState.update { it.copy(pendingSaveWithMnemonic = withMnemonic, pendingSaveLabel = label) }
    }

    fun clearPendingSave() {
        _uiState.update { it.copy(pendingSaveWithMnemonic = null, pendingSaveLabel = "") }
    }
}
