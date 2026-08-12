package org.mega.entropy.security.pin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
 *
 * Takes the base directory directly (callers pass context.filesDir) rather
 * than a Context — see PinStore's doc comment for why.
 */
class PinManager(private val baseDir: File) {
    private val store = PinStore(baseDir)

    suspend fun isPinEnabled(): Boolean = withContext(Dispatchers.IO) {
        store.readPinRecord() != null
    }

    suspend fun isDuressPinEnabled(): Boolean = withContext(Dispatchers.IO) {
        store.readDuressPinRecord() != null
    }

    suspend fun setPin(pin: String) = withContext(Dispatchers.IO) {
        // Validate invariants at function boundary; fail closed.
        require(pin.length in 5..8) { "PIN must be between 5 and 8 digits long, got ${pin.length}" }
        require(pin.all { it.isDigit() }) { "PIN must contain only numeric digits" }

        // Generate fresh salt and hash. A new PIN always clears prior lockout/failure history.
        val salt = generateSalt()
        val hash = hashPin(pin, salt, DEFAULT_PBKDF2_ITERATIONS)
        val existingDuress = store.readDuressPinRecord()
        if (existingDuress != null && constantTimeEquals(hashPin(pin, existingDuress.salt, existingDuress.iterations), existingDuress.hash)) {
            throw IllegalArgumentException("Normal PIN must not match duress PIN")
        }

        val record = PinRecord(salt, hash, DEFAULT_PBKDF2_ITERATIONS, currentTimeMillis())
        store.writePinRecord(record)
        store.resetAttemptState()
    }

    suspend fun setDuressPin(pin: String) = withContext(Dispatchers.IO) {
        require(pin.length in 5..8) { "PIN must be between 5 and 8 digits long, got ${pin.length}" }
        require(pin.all { it.isDigit() }) { "PIN must contain only numeric digits" }

        val normalRecord = store.readPinRecord()
            ?: throw IllegalStateException("Normal PIN must be configured before duress PIN")
        if (constantTimeEquals(hashPin(pin, normalRecord.salt, normalRecord.iterations), normalRecord.hash)) {
            throw IllegalArgumentException("Duress PIN must not match normal PIN")
        }

        val salt = generateSalt()
        val hash = hashPin(pin, salt, DEFAULT_PBKDF2_ITERATIONS)
        store.writeDuressPinRecord(PinRecord(salt, hash, DEFAULT_PBKDF2_ITERATIONS, currentTimeMillis()))
    }

    suspend fun clearDuressPin() = withContext(Dispatchers.IO) {
        store.deleteDuressPinRecord()
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

        val duressRecord = store.readDuressPinRecord()
        if (duressRecord != null) {
            val duressHash = hashPin(pin, duressRecord.salt, duressRecord.iterations)
            if (constantTimeEquals(duressHash, duressRecord.hash)) {
                store.resetAttemptState()
                return@withContext PinVerifyResult.Duress
            }
        }

        // If currently locked out, a guess still must not be free: without this,
        // an attacker (or someone fishing for the duress PIN specifically —
        // there is no way to tell the two apart from here, every input that
        // reaches this branch already failed the duress check above) could
        // submit unlimited guesses during a lockout window at zero cost, since
        // none of them were ever tracked. Extend the SAME escalating lockout a
        // normal failure would apply, via maxOf so a fast successive guess can
        // only extend or hold the lockout, never shorten it — and still never
        // hash the input against the real PIN while locked, preserving the
        // original "no timing signal while locked" property. Duress PIN keeps
        // its priority above this check (see above) so it still wipes even
        // during an active lockout.
        if (now < state.lockedUntilEpochMillis) {
            val newFailedCount = state.failedAttempts + 1
            val lockoutMillis = lockoutMillisFor(newFailedCount)
            val newLockedUntil = if (lockoutMillis > 0) now + lockoutMillis else state.lockedUntilEpochMillis
            val effectiveLockedUntil = maxOf(newLockedUntil, state.lockedUntilEpochMillis)
            store.writeAttemptState(PinAttemptState(newFailedCount, effectiveLockedUntil))
            return@withContext PinVerifyResult.Locked(effectiveLockedUntil)
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
        val lockoutMillis = lockoutMillisFor(newFailedCount)

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

    /** Lockout duration for a given failed-attempt count (0 below the first
     * tier). The single source of truth for the escalation tiers, shared by
     * the normal post-hash-failure path above and the during-lockout path,
     * so the two can never drift into disagreeing about the schedule. */
    private fun lockoutMillisFor(failedAttemptCount: Int): Long = when {
        failedAttemptCount in 5..9 -> 30_000L
        failedAttemptCount in 10..14 -> 300_000L
        failedAttemptCount >= 15 -> 1_800_000L
        else -> 0L
    }
}
