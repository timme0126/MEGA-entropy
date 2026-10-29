package org.mega.entropy.ui.selftest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
