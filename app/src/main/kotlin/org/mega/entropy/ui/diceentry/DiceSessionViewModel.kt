package org.mega.entropy.ui.diceentry

import androidx.lifecycle.ViewModel
import java.math.BigInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.mega.entropycore.MnemonicResult
import org.mega.entropycore.RejectionResult
import org.mega.entropycore.accumulate
import org.mega.entropycore.calculateChunk
import org.mega.entropycore.calculateXDirect
import org.mega.entropycore.checkAcceptance
import org.mega.entropycore.deriveMnemonic
import org.mega.entropycore.mapRollsToBase6

const val ROLLS_PER_BATCH = 5
const val TOTAL_BATCHES = 20
const val TOTAL_ROLLS = ROLLS_PER_BATCH * TOTAL_BATCHES

/** Everything shown on-screen for one finished 5-roll batch, so the UI can
 * display the full worked calculation per spec section 6. */
data class CompletedBatch(
    val batchNumber: Int, // 1..20
    val physicalRolls: List<Int>,
    val base6Digits: List<Int>,
    val chunk: Long,
    val previousX: BigInteger,
    val newX: BigInteger,
)

data class DiceSessionUiState(
    val completedBatches: List<CompletedBatch> = emptyList(),
    val currentBatchRolls: List<Int> = emptyList(),
    val mnemonicResult: MnemonicResult? = null,
    // MnemonicResult.Success doesn't retain the X/T/6^100/2^256 comparison
    // (only MnemonicResult.Rejected does), but the Bias Check screen needs
    // to show that comparison either way — so it's tracked here too,
    // computed via the same public checkAcceptance() call the pipeline
    // itself uses. This never changes which branch was taken.
    val rejectionResult: RejectionResult? = null,
) {
    val rollsEntered: Int get() = completedBatches.size * ROLLS_PER_BATCH + currentBatchRolls.size
    val currentBatchNumber: Int get() = (completedBatches.size + 1).coerceAtMost(TOTAL_BATCHES)
    val isSessionComplete: Boolean get() = completedBatches.size == TOTAL_BATCHES
    val allRolls: List<Int> get() = completedBatches.flatMap { it.physicalRolls } + currentBatchRolls
    val runningX: BigInteger get() = completedBatches.lastOrNull()?.newX ?: BigInteger.ZERO
}

/**
 * Holds one dice-rolling session's state in memory only — per spec section
 * 16, a session is ephemeral by default and is never written to disk (or to
 * SavedStateHandle/logs) unless the user explicitly chooses to save it later.
 * Every derived value (chunks, X, the final mnemonic) is recomputed from the
 * raw rolls by calling straight into :entropy-core, never invented here.
 */
class DiceSessionViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DiceSessionUiState())
    val uiState: StateFlow<DiceSessionUiState> = _uiState.asStateFlow()

    /** Records one physical die outcome (1..6). Completes and appends a
     * batch once 5 rolls have accumulated; computes the final mnemonic once
     * all 100 rolls are in. */
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
            val isNowComplete = newCompletedBatches.size == TOTAL_BATCHES
            val allRolls = newCompletedBatches.flatMap { it.physicalRolls }
            val result = if (isNowComplete) deriveMnemonic(allRolls) else null
            val rejection = if (isNowComplete) {
                checkAcceptance(calculateXDirect(mapRollsToBase6(allRolls)))
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
        _uiState.value = DiceSessionUiState()
    }
}
