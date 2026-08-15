package org.mega.entropy.security.verification

import org.junit.Assert.assertEquals
import org.junit.Test

class SecurityStateTest {
    private fun check(status: SecurityCheckStatus) = SecurityCheck("id", "title", status, "detail", true)

    @Test fun allPassIsVerified() {
        val state = SecurityState(listOf(check(SecurityCheckStatus.PASS)), emptyList())
        assertEquals(SecurityOverallStatus.VERIFIED, state.overallStatus)
    }

    @Test fun warningOrUnavailableIsNotClaimedSafe() {
        assertEquals(SecurityOverallStatus.SOME_UNVERIFIED, SecurityState(listOf(check(SecurityCheckStatus.WARNING)), emptyList()).overallStatus)
        assertEquals(SecurityOverallStatus.SOME_UNVERIFIED, SecurityState(emptyList(), listOf(check(SecurityCheckStatus.UNAVAILABLE))).overallStatus)
    }

    @Test fun failureRequiresAction() {
        val state = SecurityState(listOf(check(SecurityCheckStatus.FAIL)), emptyList())
        assertEquals(SecurityOverallStatus.ACTION_REQUIRED, state.overallStatus)
    }

    @Test fun allChecksCombinesAppAndDeviceChecks() {
        val state = SecurityState(listOf(check(SecurityCheckStatus.PASS)), listOf(check(SecurityCheckStatus.PASS)) )
        assertEquals(2, state.allChecks.size)
    }
}
