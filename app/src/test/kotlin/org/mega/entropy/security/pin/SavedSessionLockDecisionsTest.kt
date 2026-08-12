package org.mega.entropy.security.pin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FIFTEEN_MINUTES_MILLIS = 15 * 60 * 1000L

class SavedSessionLockDecisionsTest {

    // --- isSavedSessionUnlockStillValid ---

    @Test
    fun `unlock is valid moments after unlocking, well within a 15-minute timeout`() {
        // The exact scenario from the original bug report: unlock, then a
        // second saved-session access attempt (e.g. filling a second
        // multisig cosigner slot) a few seconds later must not re-prompt.
        assertTrue(
            isSavedSessionUnlockStillValid(
                isLocked = false,
                pinEnabled = true,
                timeoutMillis = FIFTEEN_MINUTES_MILLIS,
                millisSinceUnlock = 10_000L,
            ),
        )
    }

    @Test
    fun `unlock is no longer valid once the configured timeout has elapsed`() {
        assertFalse(
            isSavedSessionUnlockStillValid(
                isLocked = false,
                pinEnabled = true,
                timeoutMillis = FIFTEEN_MINUTES_MILLIS,
                millisSinceUnlock = FIFTEEN_MINUTES_MILLIS + 1,
            ),
        )
    }

    @Test
    fun `unlock is valid at exactly the timeout boundary but not one millisecond past it`() {
        assertTrue(
            isSavedSessionUnlockStillValid(
                isLocked = false,
                pinEnabled = true,
                timeoutMillis = FIFTEEN_MINUTES_MILLIS,
                millisSinceUnlock = FIFTEEN_MINUTES_MILLIS - 1,
            ),
        )
        assertFalse(
            isSavedSessionUnlockStillValid(
                isLocked = false,
                pinEnabled = true,
                timeoutMillis = FIFTEEN_MINUTES_MILLIS,
                millisSinceUnlock = FIFTEEN_MINUTES_MILLIS,
            ),
        )
    }

    @Test
    fun `a null millisSinceUnlock is never valid, even though the lock bit says unlocked`() {
        // This is exactly the shape the PIN_SETUP onPinSet regression took:
        // unlocking via a direct AppLockViewModel.unlock() call that never
        // recorded an unlock timestamp. A null timestamp must never be
        // silently treated as "just unlocked" or "unlock never expires".
        assertFalse(
            isSavedSessionUnlockStillValid(
                isLocked = false,
                pinEnabled = true,
                timeoutMillis = FIFTEEN_MINUTES_MILLIS,
                millisSinceUnlock = null,
            ),
        )
    }

    @Test
    fun `already locked is never valid regardless of elapsed time`() {
        assertFalse(
            isSavedSessionUnlockStillValid(
                isLocked = true,
                pinEnabled = true,
                timeoutMillis = FIFTEEN_MINUTES_MILLIS,
                millisSinceUnlock = 0L,
            ),
        )
    }

    @Test
    fun `no PIN configured is always valid, independent of timing`() {
        assertTrue(
            isSavedSessionUnlockStillValid(
                isLocked = false,
                pinEnabled = false,
                timeoutMillis = FIFTEEN_MINUTES_MILLIS,
                millisSinceUnlock = null,
            ),
        )
    }

    @Test
    fun `an Immediately (zero) timeout is never valid even moments after unlocking`() {
        assertFalse(
            isSavedSessionUnlockStillValid(
                isLocked = false,
                pinEnabled = true,
                timeoutMillis = 0L,
                millisSinceUnlock = 1L,
            ),
        )
    }

    // --- hasSavedSessionGraceWindowExpired ---

    @Test
    fun `grace window has not expired well within the timeout`() {
        assertFalse(hasSavedSessionGraceWindowExpired(FIFTEEN_MINUTES_MILLIS, millisSinceLeft = 10_000L))
    }

    @Test
    fun `grace window has expired once the timeout has elapsed`() {
        assertTrue(hasSavedSessionGraceWindowExpired(FIFTEEN_MINUTES_MILLIS, millisSinceLeft = FIFTEEN_MINUTES_MILLIS))
    }

    @Test
    fun `grace window cannot have expired if saved-session access was never left`() {
        assertFalse(hasSavedSessionGraceWindowExpired(FIFTEEN_MINUTES_MILLIS, millisSinceLeft = null))
    }

    // --- The exact end-to-end scenario named in the task: Saved Sessions
    // -> back (arms the grace window) -> Advanced Mode -> Import from
    // Saved Sessions (checks unlock validity). Modeled here as the two
    // pure decisions in sequence, since the actual timing/state live in
    // MegaNavGraph and can't be driven end-to-end without a Composition. ---

    @Test
    fun `leaving Saved Sessions and returning to a different gated flow within the timeout does not re-prompt`() {
        val millisSinceLeft = 5_000L
        assertFalse(hasSavedSessionGraceWindowExpired(FIFTEEN_MINUTES_MILLIS, millisSinceLeft))
        // Grace window still open -> the lock bit this models never flipped
        // to locked, so unlock is still considered valid too.
        assertTrue(
            isSavedSessionUnlockStillValid(
                isLocked = false,
                pinEnabled = true,
                timeoutMillis = FIFTEEN_MINUTES_MILLIS,
                millisSinceUnlock = millisSinceLeft,
            ),
        )
    }

    @Test
    fun `leaving Saved Sessions and returning to a different gated flow after the timeout re-prompts`() {
        val millisSinceLeft = FIFTEEN_MINUTES_MILLIS + 60_000L
        assertTrue(hasSavedSessionGraceWindowExpired(FIFTEEN_MINUTES_MILLIS, millisSinceLeft))
    }
}
