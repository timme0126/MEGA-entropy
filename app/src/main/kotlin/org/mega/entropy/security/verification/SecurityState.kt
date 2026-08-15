package org.mega.entropy.security.verification

enum class SecurityCheckStatus { PASS, WARNING, UNAVAILABLE, FAIL }

data class SecurityCheck(val id: String, val title: String, val status: SecurityCheckStatus, val detail: String, val authoritative: Boolean, val settingsAction: String? = null)
enum class SecurityOverallStatus { VERIFIED, ACTION_REQUIRED, SOME_UNVERIFIED }
enum class SecurityEnvironmentProfile(val label: String, val guidance: String) {
    DEDICATED_GRAPHENEOS("Dedicated offline GrapheneOS device", "Preferred configuration, but this selection is user-supplied and not automatically verified."),
    SAMSUNG_SECURE_FOLDER("Samsung Secure Folder / Knox", "Useful practical separation, but not equivalent to a dedicated offline signer."),
    PRIVATE_SPACE("Android Private Space / isolated profile", "Useful additional separation, but not an air gap and not automatically verified."),
    ORDINARY_ANDROID("Ordinary Android installation", "Android sandboxing and MEGA's missing INTERNET permission still help, but separation is weaker."),
}
data class SecurityState(val appChecks: List<SecurityCheck>, val deviceChecks: List<SecurityCheck>, val environment: SecurityEnvironmentProfile = SecurityEnvironmentProfile.ORDINARY_ANDROID) {
    val allChecks get() = appChecks + deviceChecks
    val overallStatus get() = when {
        allChecks.any { it.status == SecurityCheckStatus.FAIL } -> SecurityOverallStatus.ACTION_REQUIRED
        allChecks.any { it.status == SecurityCheckStatus.WARNING || it.status == SecurityCheckStatus.UNAVAILABLE } -> SecurityOverallStatus.SOME_UNVERIFIED
        else -> SecurityOverallStatus.VERIFIED
    }
}
