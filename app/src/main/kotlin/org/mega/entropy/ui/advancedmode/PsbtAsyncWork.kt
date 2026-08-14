package org.mega.entropy.ui.advancedmode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Outcome of a background PSBT computation ([producePsbtAsync]) shown in
 * Compose. Exactly one of these is ever the "current" value for a given
 * call site — see [producePsbtAsync]'s own doc for why this is safe
 * against duplicate concurrent work and torn/corrupted UI state.
 */
sealed class PsbtAsyncState<out T> {
    /** Work is in flight (or hasn't started yet this composition). The
     * caller should show a loading indicator, not stale content — there is
     * deliberately no "previous value" carried over here, so a screen can
     * never display a diagnosis or signing result computed for a
     * different PSBT/seed/confirmation state than the one currently active. */
    object Loading : PsbtAsyncState<Nothing>()
    data class Success<T>(val value: T) : PsbtAsyncState<T>()
    data class Failed(val error: Throwable) : PsbtAsyncState<Nothing>()
}

/**
 * Runs [compute] — expected to be expensive, synchronous, pure crypto/PSBT
 * work (BIP32 derivation, ECDSA, PSBT parsing/signing/diagnosis) — on
 * [Dispatchers.Default], off the Compose main thread, memoized by [keys]
 * the same way `remember(keys) { ... }` memoizes cheap work. Building this
 * on top of [produceState] (not a hand-rolled coroutine) means the
 * following are Compose's own structured-concurrency guarantees, not
 * something reimplemented here:
 *
 * - [compute] runs AT MOST ONCE per distinct [keys] combination. Changing
 *   any key cancels the in-flight coroutine (if any, via ordinary
 *   [CancellationException] propagation out of [withContext]) and starts
 *   exactly one new one — never two running concurrently for the same
 *   call site.
 * - Leaving composition (the screen is navigated away from, or this call
 *   site stops being part of the tree) cancels any in-flight computation
 *   automatically — a stale [compute] can never land a result after its
 *   caller is gone.
 * - The returned [PsbtAsyncState] is written from exactly ONE coroutine
 *   (produceState's own) to exactly ONE Compose State, read from exactly
 *   ONE place by the caller — no possibility of two concurrent writers
 *   interleaving into the same displayed text.
 *
 * [compute] itself must be a pure function of whatever [keys] captures:
 * never touch Compose state, never log or otherwise surface secret
 * material, and be safe to run entirely off the main thread.
 */
@Composable
fun <T> producePsbtAsync(vararg keys: Any?, compute: suspend () -> T): PsbtAsyncState<T> {
    val state by produceState<PsbtAsyncState<T>>(initialValue = PsbtAsyncState.Loading, keys = keys) {
        value = PsbtAsyncState.Loading
        value = try {
            PsbtAsyncState.Success(withContext(Dispatchers.Default) { compute() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PsbtAsyncState.Failed(e)
        }
    }
    return state
}
