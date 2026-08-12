package org.mega.entropy.security.pin

import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM unit tests for PinManager — enabled by PinStore/PinManager
 * taking a base directory directly instead of a Context, so a temp
 * directory stands in for context.filesDir with no Robolectric or
 * Android device needed.
 */
class PinManagerTest {

    private fun createManager(): PinManager = PinManager(createTempDirectory().toFile())

    @Test
    fun `normal failures cause lockout`() = runBlocking {
        val pm = createManager()
        pm.setPin("12345")
        repeat(4) { pm.verifyPin("00000") }
        val result = pm.verifyPin("00000")
        assertTrue("5th failure should trigger lockout", result is PinVerifyResult.Locked)
    }

    @Test
    fun `wrong duress guesses during lockout are not free`() = runBlocking {
        val pm = createManager()
        pm.setPin("12345")
        repeat(5) { pm.verifyPin("00000") }
        val stateBefore = pm.lockoutStatus()
        assertTrue("should be locked", stateBefore.lockedUntilEpochMillis > 0)

        // One more wrong guess while still inside the lockout window.
        val result = pm.verifyPin("00000")
        assertTrue("should still be locked", result is PinVerifyResult.Locked)

        val stateAfter = pm.lockoutStatus()
        assertTrue("failed attempts must increase during lockout", stateAfter.failedAttempts > stateBefore.failedAttempts)
        assertTrue("lockout must never shorten", stateAfter.lockedUntilEpochMillis >= stateBefore.lockedUntilEpochMillis)
    }

    @Test
    fun `correct duress during lockout still wipes`() = runBlocking {
        val pm = createManager()
        pm.setPin("12345")
        pm.setDuressPin("54321")
        repeat(5) { pm.verifyPin("00000") }
        assertTrue("should be locked", pm.lockoutStatus().lockedUntilEpochMillis > 0)

        val result = pm.verifyPin("54321")
        assertEquals("duress must still trigger during lockout", PinVerifyResult.Duress, result)
    }

    @Test
    fun `correct PIN resets state when not locked`() = runBlocking {
        val pm = createManager()
        pm.setPin("12345")
        pm.verifyPin("00000")
        pm.verifyPin("00000")

        val result = pm.verifyPin("12345")
        assertEquals(PinVerifyResult.Correct, result)

        val state = pm.lockoutStatus()
        assertEquals(0, state.failedAttempts)
        assertEquals(0L, state.lockedUntilEpochMillis)
    }
}
