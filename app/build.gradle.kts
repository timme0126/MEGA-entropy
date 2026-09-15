import java.io.ByteArrayOutputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Local beta-release signing (see docs/RELEASE-SIGNING.md). Signing secrets
// must live OUTSIDE the repo working copy: `.gitignore` alone is not a
// protection — `git add -f`, a misconfigured `git clean -fdx` losing nothing
// but a backup including them, or any copy of the working tree (USB, shared
// checkout, compromised machine) travels with the keystore + passwords still
// next to the code. So the build reads them ONLY from:
//   1. environment variables MEGA_KEYSTORE_FILE / MEGA_STORE_PASSWORD /
//      MEGA_KEY_ALIAS / MEGA_KEY_PASSWORD, or
//   2. an out-of-tree properties file named by MEGA_KEYSTORE_PROPERTIES
//      (keys: storeFile, storePassword, keyAlias, keyPassword).
// An in-tree `keystore.properties` or `keystore/` directory is a hard BUILD
// ERROR, and BOTH the properties file and the resolved keystore are checked
// for path containment inside the repo root — naming a file `keystore.*`
// was never the real boundary; "outside the working copy" is. So
// "secrets in the checkout" stops being a policy and becomes mechanically
// impossible. A configured MEGA_KEYSTORE_PROPERTIES path that doesn't exist
// is also a config-time error (a mistyped path must not silently downgrade
// to unsigned). Absence of any signing config is not an error:
// `assembleRelease` produces an unsigned artifact and verifyReleaseArtifact
// (below) refuses to pass — never a silent fallback to debug signing.
val repoRootCanonical = rootProject.projectDir.canonicalFile
fun assertOutsideRepo(label: String, file: File?) {
    if (file == null) return
    val canonical = file.canonicalFile
    if (canonical == repoRootCanonical ||
        canonical.path.startsWith(repoRootCanonical.path + File.separator)
    ) {
        throw GradleException(
            "Release-signing $label must live OUTSIDE the repository working copy " +
                "(resolved inside it: $canonical). See docs/RELEASE-SIGNING.md.",
        )
    }
}
val inTreeKeystoreProperties = rootProject.file("keystore.properties")
val inTreeKeystoreDir = rootProject.file("keystore")
if (inTreeKeystoreProperties.exists() || inTreeKeystoreDir.exists()) {
    throw GradleException(
        "Release-signing secrets must not live in the repository working copy " +
            "(found ${if (inTreeKeystoreProperties.exists()) inTreeKeystoreProperties else inTreeKeystoreDir}). " +
            "Move them out of tree and point MEGA_KEYSTORE_PROPERTIES at the properties file, or set " +
            "MEGA_KEYSTORE_FILE / MEGA_STORE_PASSWORD / MEGA_KEY_ALIAS / MEGA_KEY_PASSWORD. " +
            "See docs/RELEASE-SIGNING.md.",
    )
}
val outOfTreeKeystorePropertiesFile = System.getenv("MEGA_KEYSTORE_PROPERTIES")?.let {
    val f = File(it).canonicalFile
    if (!f.exists()) {
        throw GradleException(
            "MEGA_KEYSTORE_PROPERTIES is set to '$it' but no such file exists — " +
                "refusing to silently build unsigned. Fix the path or unset the variable. " +
                "See docs/RELEASE-SIGNING.md.",
        )
    }
    assertOutsideRepo("signing properties file", f)
    f
}
val keystoreProperties = Properties().apply {
    if (outOfTreeKeystorePropertiesFile?.exists() == true) load(outOfTreeKeystorePropertiesFile.inputStream())
}
// The four fields come from ONE source, never mixed per-field: if ANY
// MEGA_KEYSTORE_* env var is set, we treat that as the intended route and
// refuse to silently backfill the remaining fields from the properties file
// (a stray env var from an old shell session otherwise produces a
// store/password/alias combination nobody assembled together).
val envRoute = listOf(
    "MEGA_KEYSTORE_FILE", "MEGA_STORE_PASSWORD", "MEGA_KEY_ALIAS", "MEGA_KEY_PASSWORD",
).any { !System.getenv(it).isNullOrBlank() }
if (envRoute && outOfTreeKeystorePropertiesFile != null) {
    throw GradleException(
        "Signing config is ambiguous: MEGA_KEYSTORE_PROPERTIES and at least one " +
            "MEGA_KEYSTORE_*/MEGA_*_PASSWORD env var are both set. Use exactly one route " +
            "(see docs/RELEASE-SIGNING.md).",
    )
}
fun keystoreField(envVar: String, propertiesKey: String): String? =
    if (envRoute) System.getenv(envVar) else keystoreProperties.getProperty(propertiesKey)
val signingStoreFile = keystoreField("MEGA_KEYSTORE_FILE", "storeFile")
val signingStorePassword = keystoreField("MEGA_STORE_PASSWORD", "storePassword")
val signingKeyAlias = keystoreField("MEGA_KEY_ALIAS", "keyAlias")
val signingKeyPassword = keystoreField("MEGA_KEY_PASSWORD", "keyPassword")
// A configured-enough signing setup: every value present. A partial config
// (e.g. file but no password) is treated as NOT configured so the build
// produces an unsigned APK that verifyReleaseArtifact then refuses, rather
// than half-working.
val hasKeystoreProperties =
    listOf(signingStoreFile, signingStorePassword, signingKeyAlias, signingKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "org.mega.entropy"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.mega.entropy"
        minSdk = 29
        targetSdk = 36
        versionCode = 15
        versionName = "0.1.14"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("betaRelease") {
            if (hasKeystoreProperties) {
                // Relative storeFile paths resolve against the directory of the
                // out-of-tree properties file; absolute paths pass through.
                // Either way the RESOLVED keystore must canonicalize outside
                // the repo — `..` tricks and the env-var route's
                // projectDir base are both rejected here.
                val storeFileBase = outOfTreeKeystorePropertiesFile?.parentFile ?: rootProject.projectDir
                val resolvedStoreFile = storeFileBase.resolve(signingStoreFile!!)
                assertOutsideRepo("keystore file", resolvedStoreFile)
                storeFile = resolvedStoreFile
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
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
    // Pure-Java (no JNI/native code, no ABI concerns) scrypt implementation
    // for the encrypted backup-file feature — see security/backup/BackupCrypto.kt.
    // Nothing else in the app uses BouncyCastle; PSBT/BIP32/ECDSA math is
    // hand-rolled in :entropy-core.
    implementation(libs.bouncycastle.bcprov)

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
 * key as a previous release, not a substituted one. NOTE: this proves signer
 * CONTINUITY only, not author authenticity — and only forward from the
 * 2026-09-15 key rotation: the pre-rotation key is treated as potentially
 * exposed, so the OLD fingerprint no longer proves any old APK was signed by
 * the maintainer. Update this constant (and the doc) if the keystore is ever
 * deliberately rotated. */
val expectedBetaReleaseSignerSha256 = "91d6b222cbf358ee583fd953f30956ec44fbf43a8cebf1db98f1ee48bac0a61f"

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
 * (kept OUTSIDE the repo, pointed at via MEGA_KEYSTORE_PROPERTIES or env
 * vars; see docs/RELEASE-SIGNING.md) that most contributors won't have, and
 * running a full `assembleRelease` on every `./gradlew check` would be
 * wasteful. This is the release process's own explicit gate, run via
 * `./gradlew assembleRelease verifyReleaseArtifact`.
 */
tasks.register("verifyReleaseArtifact") {
    group = "verification"
    description = "Fails unless the release APK is non-debuggable, signed by the expected beta-release key, and free of forbidden network/storage permissions. Run after assembleRelease."
    dependsOn("assembleRelease")

    doLast {
        if (!hasKeystoreProperties) {
            throw GradleException(
                "No out-of-tree signing config found (MEGA_KEYSTORE_PROPERTIES or the " +
                    "MEGA_KEYSTORE_FILE/MEGA_STORE_PASSWORD/MEGA_KEY_ALIAS/MEGA_KEY_PASSWORD env vars) " +
                    "— assembleRelease produced an UNSIGNED apk, not a distributable one. " +
                    "See docs/RELEASE-SIGNING.md to set up a local beta-release keystore OUTSIDE the repo.",
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
