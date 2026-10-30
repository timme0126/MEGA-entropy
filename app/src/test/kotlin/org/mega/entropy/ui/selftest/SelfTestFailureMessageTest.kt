package org.mega.entropy.ui.selftest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mega.entropycore.CryptoSelfTest

/**
 * The failure message is what stands between a wrong-computing device
 * and a user sending funds to a bad address — its wording is pinned.
 */
class SelfTestFailureMessageTest {

    @Test
    fun messageNamesEveryFailedCheck() {
        val message = buildSelfTestFailureMessage(listOf("sha256", "bip32-chain"))
        assertTrue(message.contains("sha256"))
        assertTrue(message.contains("bip32-chain"))
        assertTrue(message.contains("2 checks did not match"))
    }

    @Test
    fun singularGrammarForSingleFailure() {
        val message = buildSelfTestFailureMessage(listOf("wif"))
        assertTrue(message.contains("1 check did not match"))
    }

    @Test
    fun messageGivesTheSafetyDirectiveAndTheBothCausesExplanation() {
        val message = buildSelfTestFailureMessage(listOf("rfc6979-ecdsa"))
        // Both candidate causes stated, never a guess of one.
        assertTrue(message.contains("damaged or were replaced"))
        assertTrue(message.contains("producing wrong calculations"))
        // The action that must not be taken.
        assertTrue(message.contains("Do not create wallets"))
        // Nothing was mutated.
        assertTrue(message.contains("Nothing has been changed"))
    }

    @Test
    fun messageCarriesNoKeyMaterialOrSecrets() {
        val message = buildSelfTestFailureMessage(listOf("bip39-seed-12"))
        // The report is designed to be safe to copy/share offline: it
        // must contain no hex strings of any length.
        assertFalse(
            "failure report must never contain hex blobs",
            Regex("(?i)[0-9a-f]{16,}").containsMatchIn(message),
        )
    }

    @Test
    fun resourceFaultsDoNotAccuseTheHardware() {
        // memory-exhausted / self-test-startup mean "resource or
        // bootstrap problem" — the wording must NOT tell the user their
        // device computes wrong or to reinstall, or a transient OOM
        // triggers a panic reinstall ritual on a signer.
        val memory = buildSelfTestFailureMessage(listOf(CryptoSelfTest.MEMORY_EXHAUSTED))
        assertTrue(memory.contains("ran out of resources"))
        assertTrue(memory.contains("NOT been proven to be a wrong calculation"))
        assertFalse(memory.contains("Reinstall MEGA"))

        val startup = buildSelfTestFailureMessage(listOf(CryptoSelfTest.STARTUP_FAILURE))
        assertTrue(startup.contains("failed to start correctly"))
    }

    @Test
    fun aMixedReportStillUsesTheSafetyWording() {
        // One resource name among real check failures: the check
        // failures dominate — full safety directive, hardware suspicion
        // intact.
        val message = buildSelfTestFailureMessage(
            listOf(CryptoSelfTest.MEMORY_EXHAUSTED, "sha256"),
        )
        assertTrue(message.contains("2 checks did not match"))
        assertTrue(message.contains("Do not create wallets"))
    }
}
