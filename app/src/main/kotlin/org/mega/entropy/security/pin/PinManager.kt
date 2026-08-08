package org.mega.entropy.security.pin

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.System.currentTimeMillis

/**
 * Public API for the optional PIN feature.
 *
 * The PIN is an ADDITIONAL UI-level access barrier on top of Android sandboxing
 * and encrypted session storage. It does NOT gate creating new ephemeral dice
 * sessions, only access to already-saved sessions. A 5-8 digit numeric PIN
 * does not have meaningful cryptographic entropy by itself; its value is
 * strictly as a friction barrier against casual unauthorized access.
 *
 * All I/O is dispatched to Dispatchers.IO to comply with coroutine requirements.
 */
class PinManager(private val context: Context) {
    private val store = PinStore(context)

    suspend fun isPinEnabled(): Boolean = withContext(Dispatchers.IO) {
        store.readPinRecord() != null
    }

    suspend fun setPin(pin: String) = withContext(Dispatchers.IO) {
        // Validate invariants at function boundary; fail closed.
        require(pin.length in 5..8) { "PIN must be between 5 and 8 digits long, got ${pin.length}" }
        require(pin.all { it.isDigit() }) { "PIN must contain only numeric digits" }

        // Generate fresh salt and hash. A new PIN always clears prior lockout/failure history.
        val salt = generateSalt()
        val hash = hashPin(pin, salt, DEFAULT_PBKDF2_ITERATIONS)
        val record = PinRecord(salt, hash, DEFAULT_PBKDF2_ITERATIONS, currentTimeMillis())
        store.writePinRecord(record)
        store.resetAttemptState()
    }

    suspend fun disablePin() = withContext(Dispatchers.IO) {
        store.deletePinRecord()
        store.resetAttemptState()
    }

    suspend fun verifyPin(pin: String): PinVerifyResult = withContext(Dispatchers.IO) {
        val record = store.readPinRecord()
        if (record == null) return@withContext PinVerifyResult.NoPinConfigured

        val state = store.readAttemptState()
        val now = currentTimeMillis()

        // If currently locked out, return Locked immediately WITHOUT hashing the input
        // or incrementing the failure counter. This prevents active lockouts from
        // being extended by repeated calls.
        if (now < state.lockedUntilEpochMillis) {
            return@withContext PinVerifyResult.Locked(state.lockedUntilEpochMillis)
        }

        // Compute hash and verify using constant-time comparison to prevent timing side-channels.
        val inputHash = hashPin(pin, record.salt, record.iterations)
        if (constantTimeEquals(inputHash, record.hash)) {
            // Successful verification resets all failure tracking.
            store.resetAttemptState()
            return@withContext PinVerifyResult.Correct
        }

        // Failed attempt: increment counter and determine new lockout window.
        val newFailedCount = state.failedAttempts + 1
        val lockoutMillis = when {
            newFailedCount in 5..9 -> 30_000L
            newFailedCount in 10..14 -> 300_000L
            newFailedCount >= 15 -> 1_800_000L
            else -> 0L
        }

        val newLockedUntil = if (lockoutMillis > 0) now + lockoutMillis else 0L
        store.writeAttemptState(PinAttemptState(newFailedCount, newLockedUntil))

        // If the new count just crossed into a lockout tier, return Locked so the UI
        // can immediately show the lockout state instead of treating it as a normal failure.
        if (lockoutMillis > 0) {
            PinVerifyResult.Locked(newLockedUntil)
        } else {
            PinVerifyResult.Incorrect(newFailedCount)
        }
    }

    suspend fun lockoutStatus(): PinAttemptState = withContext(Dispatchers.IO) {
        store.readAttemptState()
    }
}
