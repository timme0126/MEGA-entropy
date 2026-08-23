package org.mega.entropy.ui.advancedmode.backupshares

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.mega.entropycore.BackupShare
import org.mega.entropycore.ManualMnemonicValidation
import org.mega.entropycore.encodeBackupShare
import org.mega.entropycore.entropyToBackupShares
import org.mega.entropycore.validateManualMnemonic

enum class BackupSharesStep { SETUP, REVEALING, DONE }

data class BackupSharesUiState(
    val step: BackupSharesStep = BackupSharesStep.SETUP,
    val threshold: Int = 2,
    val totalShares: Int = 3,
    /** 1-based - "share 2 of 5", matching how it's shown to the user. */
    val currentShareNumber: Int = 1,
    val currentShareText: String? = null,
    /** Gates the Next button - set only by an explicit "I've recorded
     * this share" tap, never implicitly, so advancing never happens
     * without the user confirming they actually captured the one on
     * screen (write it down, photograph it, whatever they intend). */
    val currentShareRecorded: Boolean = false,
    val error: String? = null,
)

/**
 * Owns the "Create Backup Shares" flow's state: pick threshold/total
 * shares, split this session's entropy, then step through the resulting
 * shares one at a time. Deliberately never exposes more than the single
 * current share's text to the UI (see [uiState].currentShareText) — the
 * whole point of splitting a secret is that the pieces end up in
 * physically separate places, which a screen (or export) showing them all
 * together would quietly undermine. The full generated share set is kept
 * in [shares], not in [uiState] itself, purely so this class can walk
 * forward through indices without re-splitting (which would use fresh
 * random coefficients and hand out a DIFFERENT, inconsistent set); it is
 * discarded the moment the reveal finishes or [reset] is called, the same
 * as [org.mega.entropy.ui.advancedmode.structuretx.StructureTransactionViewModel]
 * discards its built PSBT once consumed.
 */
class BackupSharesViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(BackupSharesUiState())
    val uiState: StateFlow<BackupSharesUiState> = _uiState.asStateFlow()

    private var shares: List<BackupShare> = emptyList()

    fun setThreshold(value: Int) = _uiState.update {
        val clamped = value.coerceIn(2, it.totalShares)
        it.copy(threshold = clamped, error = null)
    }

    fun setTotalShares(value: Int) = _uiState.update {
        val clamped = value.coerceIn(it.threshold, 255)
        it.copy(totalShares = clamped, error = null)
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    /** Resets every field, called when the flow is entered fresh from the
     * Hub — same reasoning as StructureTransactionViewModel.reset(). */
    fun reset() {
        shares = emptyList()
        _uiState.value = BackupSharesUiState()
    }

    /** Splits [mnemonicWords]' entropy per the currently chosen
     * threshold/totalShares and shows the first share. [mnemonicWords]
     * is expected to already be a valid, checksum-passing BIP39 mnemonic
     * — true of every path that can reach Advanced Mode's Hub (manual
     * entry validates it, saved-session and SeedQR imports only ever
     * produce well-formed words) — but is re-validated here regardless
     * via [validateManualMnemonic], the same function Advanced Mode's own
     * manual-entry screen uses, rather than assuming.
     */
    fun beginReveal(mnemonicWords: List<String>) {
        val state = _uiState.value
        val validation = validateManualMnemonic(mnemonicWords)
        val entropy = (validation as? ManualMnemonicValidation.Valid)?.entropy
        if (entropy == null) {
            _uiState.update { it.copy(error = "Could not read this session's seed entropy.") }
            return
        }

        val generatedShares = entropyToBackupShares(entropy, state.threshold, state.totalShares)
        shares = generatedShares
        _uiState.update {
            it.copy(
                step = BackupSharesStep.REVEALING,
                currentShareNumber = 1,
                currentShareText = encodeBackupShare(generatedShares[0]),
                currentShareRecorded = false,
                error = null,
            )
        }
    }

    fun confirmCurrentShareRecorded() = _uiState.update { it.copy(currentShareRecorded = true) }

    /** Advances to the next share, or to [BackupSharesStep.DONE] (and
     * discards [shares]) once every share has been shown. No-ops if the
     * current share hasn't been confirmed recorded yet — the Next button
     * in the UI is disabled in that case too, this is the belt-and-braces
     * check on the state itself. */
    fun advanceToNextShare() {
        val state = _uiState.value
        if (!state.currentShareRecorded) return
        val nextShareNumber = state.currentShareNumber + 1
        if (nextShareNumber > state.totalShares) {
            shares = emptyList()
            _uiState.update { it.copy(step = BackupSharesStep.DONE, currentShareText = null) }
            return
        }
        _uiState.update {
            it.copy(
                currentShareNumber = nextShareNumber,
                currentShareText = encodeBackupShare(shares[nextShareNumber - 1]),
                currentShareRecorded = false,
            )
        }
    }
}
