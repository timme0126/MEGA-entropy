// :entropy-core is a pure Kotlin/JVM module. It MUST NOT gain an Android
// or networking dependency: see docs/NO-RNG-PROOF.md for the argument
// that this module boundary is what makes "wallet entropy = f(dice)"
// provable rather than merely asserted.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}

val forbiddenApiPatterns = listOf(
    "SecureRandom",
    "kotlin.random.Random",
    "java.util.Random",
    "java.util.UUID",
    "System.currentTimeMillis",
    "System.nanoTime",
    "java.time.",
    "java.util.Date",
    "android.",
    // Reflection/dynamic-loading patterns that could indirectly reach a
    // forbidden RNG/clock API without matching a literal substring above
    // (see docs/CODEX-AUDIT-ENTROPY-CORE.md, Finding: securityAudit bypass).
    "Class.forName",
    "getDeclaredMethod",
    "getMethod",
    "java.lang.reflect",
    "MethodHandles",
    "ServiceLoader",
    "shuffled",
    ".random(",
)

tasks.register("securityAudit") {
    group = "verification"
    description = "Fails if :entropy-core references any prohibited RNG/clock/Android API."
    val sourceDir = layout.projectDirectory.dir("src/main/kotlin")
    inputs.dir(sourceDir)

    doLast {
        val violations = mutableListOf<String>()
        sourceDir.asFile.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val withoutComment = line.substringBefore("//")
                    forbiddenApiPatterns.forEach { pattern ->
                        if (withoutComment.contains(pattern)) {
                            violations += "${file.relativeTo(projectDir)}:${index + 1}: forbidden reference to '$pattern'"
                        }
                    }
                }
            }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "entropy-core securityAudit FAILED — prohibited RNG/time/Android API detected:\n" +
                    violations.joinToString("\n") + "\n\n" +
                    "See docs/NO-RNG-PROOF.md section on the entropy-core module boundary."
            )
        }
        println("entropy-core securityAudit PASSED: no prohibited RNG/time/Android APIs found.")
    }
}

tasks.named("check") {
    dependsOn("securityAudit")
}
