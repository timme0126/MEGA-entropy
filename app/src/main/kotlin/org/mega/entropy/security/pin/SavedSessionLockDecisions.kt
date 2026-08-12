package org.mega.entropy.security.pin

/**
 * Pure decision logic for saved-session auto-lock timing, extracted out of
 * MegaNavGraph's composable body so it can be unit-tested without a
 * Composition — the actual timing source (SystemClock.elapsedRealtime())
 * and mutable state (remembered vars, AppLockViewModel) stay in
 * MegaNavGraph, which owns every side effect; these functions only decide,
 * they never mutate anything.
 *
 * Two independent expiry mechanisms exist, matching MegaNavGraph's own
 * design: [isSavedSessionUnlockStillValid] bounds total access duration by
 * elapsed time since the last successful PIN entry, regardless of whether
 * the app stayed foregrounded the whole time; [hasSavedSessionGraceWindowExpired]
 * separately bounds how long access survives after actually leaving Saved
 * Sessions (backgrounding the app, or navigating back out). Either one
 * expiring re-locks access.
 */

/**
 * Whether a saved-session PIN unlock is still valid for gating a NEW
 * access attempt (e.g. a second "+ Add Saved Session Key" tap in the same
 * multisig flow) without re-prompting.
 *
 * [millisSinceUnlock] is the elapsed time since the unlock timestamp was
 * actually recorded — null whenever that recording didn't happen (no
 * unlock yet this session, or an unlock path that failed to record one;
 * this is exactly the shape the PIN_SETUP onPinSet bug took: unlocking via
 * a direct AppLockViewModel.unlock() call instead of the wrapper that
 * records the timestamp). A null here always means "not valid" — there is
 * no timestamp to trust, so this never assumes an unlock is still good
 * just because the lock bit happens to be false.
 */
fun isSavedSessionUnlockStillValid(
    isLocked: Boolean,
    pinEnabled: Boolean,
    timeoutMillis: Long,
    millisSinceUnlock: Long?,
): Boolean {
    if (isLocked) return false
    if (!pinEnabled) return true
    if (timeoutMillis == 0L) return false
    val elapsed = millisSinceUnlock ?: return false
    return elapsed < timeoutMillis
}

/**
 * Whether the grace window since leaving Saved Sessions (backgrounding the
 * app, or navigating back out of the Saved Sessions screen) has expired.
 * [millisSinceLeft] is null when saved-session access was never actually
 * left since the last unlock — in that case the window can't have expired.
 */
fun hasSavedSessionGraceWindowExpired(timeoutMillis: Long, millisSinceLeft: Long?): Boolean {
    if (millisSinceLeft == null) return false
    return millisSinceLeft >= timeoutMillis
}
