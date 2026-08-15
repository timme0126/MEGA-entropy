import java.io.ByteArrayOutputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Local beta-release signing (see docs/RELEASE-SIGNING.md). keystore.properties
// is git-ignored and machine-local — its absence must never silently fall back
// to shipping an unsigned or debug-signed APK as "release"; it just means
// `assembleRelease` produces an unsigned artifact, and verifyReleaseArtifact
// (below) refuses to pass until the keystore exists.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasKeystoreProperties = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasKeystoreProperties) load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "org.mega.entropy"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.mega.entropy"
        minSdk = 29
        targetSdk = 36
        versionCode = 11
        versionName = "0.1.10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("betaRelease") {
            if (hasKeystoreProperties) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "DEMO_MODE_AVAILABLE", "false")
            if (hasKeystoreProperties) {
                signingConfig = signingConfigs.getByName("betaRelease")
            }
            // isDebuggable defaults to false for the "release" build type and is
            // never set true anywhere in this file — verifyReleaseArtifact
            // (below) double-checks the built APK itself rather than trusting
            // that default, since that's the actual thing that matters.
        }
        debug {
            // DEMO/TEST MODE (docs/TEST-VECTORS.md) is only ever compiled into debug builds.
            buildConfigField("boolean", "DEMO_MODE_AVAILABLE", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":entropy-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    // QR rendering and local QR scanning. CameraX provides the camera preview/image
    // analysis surface; ZXing decodes QR frames locally. No network, no cloud
    // scanner, no Play services dependency, and no cryptographic use.
    implementation(libs.zxing.core)
    implementation("com.sparrowwallet:hummingbird:1.7.4")
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
}

val forbiddenManifestPermissions = listOf(
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
)

tasks.register("securityAudit") {
    group = "verification"
    description = "Fails if the manifest requests network/storage permissions or backup is left enabled."
    val manifestFile = layout.projectDirectory.file("src/main/AndroidManifest.xml")
    inputs.file(manifestFile)

    doLast {
        // Strip XML comments first so explanatory prose (e.g. "MEGA requests
        // NO android.permission.INTERNET") can't trip a naive substring match.
        val text = manifestFile.asFile.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val violations = mutableListOf<String>()

        forbiddenManifestPermissions.forEach { perm ->
            val requestedPermission = Regex("<uses-permission[^>]*\"$perm\"[^>]*>")
                .findAll(text)
                .any { !it.value.contains("tools:node=\"remove\"") }
            if (requestedPermission) {
                violations += "AndroidManifest.xml declares forbidden permission '$perm'"
            }
        }
        if (!text.contains("android:allowBackup=\"false\"")) {
            violations += "AndroidManifest.xml must set android:allowBackup=\"false\""
        }
        if (!text.contains("android:dataExtractionRules")) {
            violations += "AndroidManifest.xml must reference android:dataExtractionRules"
        }
        if (!text.contains("android:fullBackupContent")) {
            violations += "AndroidManifest.xml must reference android:fullBackupContent (legacy backup exclusion)"
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "app securityAudit FAILED:\n" + violations.joinToString("\n")
            )
        }
        println("app securityAudit PASSED: no forbidden network/storage permissions; backup exclusion configured.")
    }
}


// Verify the manifests produced by the Android manifest merger, not only the source manifest.
tasks.register("verifyMergedManifestPermissions") {
    group = "verification"
    description = "Fails if a merged debug or release manifest introduces INTERNET or other prohibited permissions."
    dependsOn("processDebugManifest", "processReleaseManifest")
    doLast {
        val merged = layout.buildDirectory.get().asFile.walkTopDown().filter { it.isFile && it.path.contains("intermediates/merged_manifests") && it.name == "AndroidManifest.xml" }.toList()
        if (merged.isEmpty()) throw GradleException("No merged manifests found")
        val violations = merged.flatMap { file -> forbiddenManifestPermissions.filter { permission -> Regex("<uses-permission[^>]*android:name[^>]*" + Regex.escape(permission)).containsMatchIn(file.readText()) }.map { permission -> file.path + ": " + permission } }
        if (violations.isNotEmpty()) throw GradleException("Merged manifest security check FAILED:\n" + violations.joinToString("\n"))
        println("Merged manifest security check PASSED:  manifests checked.")
    }
}

tasks.named("securityAudit") { dependsOn("verifyMergedManifestPermissions") }

tasks.register("dependencyAudit") {
    group = "verification"
    description = "Fails if runtime dependencies match prohibited networking, telemetry, advertising, or cloud SDK patterns."
    doLast {
        val forbidden = listOf("okhttp", "retrofit", "ktor-client", "firebase", "crashlytics", "sentry", "analytics", "advertising", "ads", "play-services", "webview")
        val artifacts = configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id.toString() }
        val bad = artifacts.filter { artifact -> forbidden.any { pattern -> artifact.lowercase().contains(pattern) } }
        if (bad.isNotEmpty()) throw GradleException("Dependency security check FAILED:\n" + bad.joinToString("\n"))
        println("Dependency security check PASSED:  runtime artifacts checked.")
    }
}

tasks.named("securityAudit") { dependsOn("dependencyAudit") }

tasks.named("check") {
    dependsOn("securityAudit")
}

/** Expected SHA-256 fingerprint of the mega-beta-release signing certificate
 * (see docs/RELEASE-SIGNING.md) — not secret, deliberately public: it's how a
 * beta tester or reviewer confirms a given APK was signed by the SAME local
 * key as a previous release, not a substituted one. Update this constant
 * (and the doc) if the keystore is ever deliberately rotated. */
val expectedBetaReleaseSignerSha256 = "42c9da0722585a04c3388e9989b8ebcb4b62731629924aae3c96eec7d536489c"

fun latestAndroidBuildToolsDir(): File {
    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) load(file.inputStream())
    }
    val sdkDir = localProperties.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: throw GradleException("Cannot locate the Android SDK: no sdk.dir in local.properties and no ANDROID_HOME set")
    val buildToolsRoot = File(sdkDir, "build-tools")
    val versions = buildToolsRoot.listFiles { f -> f.isDirectory }
        ?: throw GradleException("No build-tools directories found under $buildToolsRoot")
    return versions.maxByOrNull { it.name }
        ?: throw GradleException("No build-tools versions installed under $buildToolsRoot")
}

/**
 * The release-signing counterpart to securityAudit above: where that task
 * checks the MANIFEST SOURCE, this task checks the actual BUILT APK that
 * would be handed to a beta tester — the artifact is the thing that
 * matters, not just the config that (should have) produced it. Fails
 * unless the release APK is (a) non-debuggable, (b) signed by exactly the
 * expected local beta-release key (catching a stale/wrong keystore, or a
 * silently-unsigned build), and (c) free of the same forbidden network/storage
 * permissions securityAudit already checks in the manifest.
 *
 * Deliberately NOT wired into `check` — it requires a real signing key
 * (keystore.properties, git-ignored, machine-local; see
 * docs/RELEASE-SIGNING.md) that most contributors won't have, and running
 * a full `assembleRelease` on every `./gradlew check` would be wasteful.
 * This is the release process's own explicit gate, run via
 * `./gradlew assembleRelease verifyReleaseArtifact`.
 */
tasks.register("verifyReleaseArtifact") {
    group = "verification"
    description = "Fails unless the release APK is non-debuggable, signed by the expected beta-release key, and free of forbidden network/storage permissions. Run after assembleRelease."
    dependsOn("assembleRelease")

    doLast {
        if (!hasKeystoreProperties) {
            throw GradleException(
                "No keystore.properties found — assembleRelease produced an UNSIGNED apk, " +
                    "not a distributable one. See docs/RELEASE-SIGNING.md to generate a local " +
                    "beta-release keystore.",
            )
        }

        val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val apk = apkDir.listFiles { f -> f.name.endsWith(".apk") }?.firstOrNull()
            ?: throw GradleException("No release .apk found under $apkDir — did assembleRelease succeed?")

        val buildTools = latestAndroidBuildToolsDir()
        val aapt2 = File(buildTools, "aapt2")
        val apksigner = File(buildTools, "apksigner")

        val badgingOutput = ByteArrayOutputStream()
        project.exec {
            commandLine(aapt2.absolutePath, "dump", "badging", apk.absolutePath)
            standardOutput = badgingOutput
        }
        val badging = badgingOutput.toString()

        val violations = mutableListOf<String>()

        if (badging.contains("application-debuggable")) {
            violations += "RELEASE APK IS DEBUGGABLE ($apk) — this must never be distributed."
        }

        val forbiddenPermissions = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
        )
        val declaredPermissions = Regex("uses-permission: name='([^']+)'")
            .findAll(badging)
            .map { it.groupValues[1] }
            .toSet()
        val foundForbidden = declaredPermissions.intersect(forbiddenPermissions)
        if (foundForbidden.isNotEmpty()) {
            violations += "Release APK declares forbidden permission(s): $foundForbidden"
        }

        val signerOutput = ByteArrayOutputStream()
        project.exec {
            commandLine(apksigner.absolutePath, "verify", "--print-certs", apk.absolutePath)
            standardOutput = signerOutput
            isIgnoreExitValue = false // apksigner verify exits non-zero if verification fails — let that throw
        }
        val signerInfo = signerOutput.toString()
        val actualSha256 = Regex("certificate SHA-256 digest:\\s*([0-9a-fA-F]+)")
            .find(signerInfo)
            ?.groupValues
            ?.get(1)
            ?.lowercase()
        if (actualSha256 == null) {
            violations += "Could not find a 'certificate SHA-256 digest' line in apksigner output:\n$signerInfo"
        } else if (actualSha256 != expectedBetaReleaseSignerSha256) {
            violations += "Release APK signer SHA-256 ($actualSha256) does not match the expected " +
                "mega-beta-release key fingerprint ($expectedBetaReleaseSignerSha256) — wrong or " +
                "rotated keystore? Update expectedBetaReleaseSignerSha256 in this file (and " +
                "docs/RELEASE-SIGNING.md) if this rotation was deliberate."
        }

        if (violations.isNotEmpty()) {
            throw GradleException("verifyReleaseArtifact FAILED:\n" + violations.joinToString("\n"))
        }
        println("verifyReleaseArtifact PASSED: $apk is non-debuggable, signer matches the expected fingerprint, no forbidden network/storage permissions declared.")
    }
}
