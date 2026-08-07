// Root build file. Plugins are declared here with apply false and
// applied per-module so version numbers stay centralized in
// gradle/libs.versions.toml (the single source of truth for this
// project's dependency versions, per docs/REPRODUCIBLE-BUILD.md).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

tasks.register("securityAudit") {
    group = "verification"
    description = "Runs all MEGA security static-analysis checks (see docs/NO-RNG-PROOF.md)."
    dependsOn(":entropy-core:securityAudit", ":app:securityAudit")
}
