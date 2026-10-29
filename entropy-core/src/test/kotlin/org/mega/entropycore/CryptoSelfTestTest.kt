package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The self-test's own test: proves every embedded vector still passes
 * on the CI JVM (any regression in a primitive turns this red exactly
 * the way it would brick the app on a phone), and proves the harness
 * actually FAILS when a vector is wrong or a check throws — a
 * self-test that could not fail would be security theatre.
 */
class CryptoSelfTestTest {

    @Test
    fun allKnownAnswerVectorsPass() {
        val outcome = CryptoSelfTest.run()
        val failed = (outcome as? CryptoSelfTestOutcome.Failed)?.failedCheckNames.orEmpty()
        assertTrue("boot self-test vectors must all pass, failed: $failed", outcome is CryptoSelfTestOutcome.Pass)
    }

    @Test
    fun everyCheckHasAUniqueStableName() {
        val names = CryptoSelfTest.checks.map { it.name }
        assertTrue("self-test needs a meaningful number of vectors", names.size >= 12)
        assertEquals("check names must be unique", names.distinct().size, names.size)
    }

    @Test
    fun harnessFailsOnAFalseCheck() {
        val outcome = CryptoSelfTest.runChecks(
            listOf(
                CryptoSelfTest.Check("good") { true },
                CryptoSelfTest.Check("deliberately-false") { false },
            ),
        )
        assertTrue("a false check must produce Failed", outcome is CryptoSelfTestOutcome.Failed)
        assertEquals(
            listOf("deliberately-false"),
            (outcome as CryptoSelfTestOutcome.Failed).failedCheckNames,
        )
    }

    @Test
    fun harnessTreatsAThrowingCheckAsFailed() {
        val outcome = CryptoSelfTest.runChecks(
            listOf(CryptoSelfTest.Check("throws") { error("simulated device fault") }),
        )
        assertTrue(
            "a throwing check must count as failed, never as a silent pass",
            outcome is CryptoSelfTestOutcome.Failed &&
                (outcome as CryptoSelfTestOutcome.Failed).failedCheckNames == listOf("throws"),
        )
    }
}
