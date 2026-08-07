plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.mega.entropy"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.mega.entropy"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "DEMO_MODE_AVAILABLE", "false")
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
    description = "Fails if the manifest requests INTERNET/storage permissions or backup is left enabled."
    val manifestFile = layout.projectDirectory.file("src/main/AndroidManifest.xml")
    inputs.file(manifestFile)

    doLast {
        // Strip XML comments first so explanatory prose (e.g. "MEGA requests
        // NO android.permission.INTERNET") can't trip a naive substring match.
        val text = manifestFile.asFile.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val violations = mutableListOf<String>()

        forbiddenManifestPermissions.forEach { perm ->
            if (Regex("<uses-permission[^>]*\"$perm\"").containsMatchIn(text)) {
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
        println("app securityAudit PASSED: no forbidden permissions; backup exclusion configured.")
    }
}

tasks.named("check") {
    dependsOn("securityAudit")
}
