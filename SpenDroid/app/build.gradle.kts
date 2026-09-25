import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.spendroid"
    compileSdk = 36

    // Release signing comes from keystore.properties locally, or from the environment in
    // CI. Neither the key nor its password is in the repository. If no release key is
    // configured the build falls back to the debug key so `assembleRelease` still works for
    // local testing - but an APK signed that way cannot update one signed with the real key.
    val releaseKeystore = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    val keystorePath = System.getenv("SPENDROID_KEYSTORE")
        ?: releaseKeystore.getProperty("storeFile")
    val keystoreFile = keystorePath?.let { rootProject.file(it) }?.takeIf { it.exists() }
    val hasReleaseKey = keystoreFile != null

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "debug"
            keyPassword = "android"
        }
        create("release") {
            if (hasReleaseKey) {
                storeFile = keystoreFile
                storePassword = System.getenv("SPENDROID_KEYSTORE_PASSWORD")
                    ?: releaseKeystore.getProperty("storePassword")
                keyAlias = System.getenv("SPENDROID_KEY_ALIAS")
                    ?: releaseKeystore.getProperty("keyAlias")
                keyPassword = System.getenv("SPENDROID_KEY_PASSWORD")
                    ?: releaseKeystore.getProperty("keyPassword")
            } else {
                storeFile = file("debug.keystore")
                storePassword = "android"
                keyAlias = "debug"
                keyPassword = "android"
            }
        }
    }

    if (!hasReleaseKey) {
        logger.warn(
            "No release keystore configured - release builds will be signed with the debug " +
                "key and cannot update installs signed with the real one.",
        )
    }

    defaultConfig {
        applicationId = "com.spendroid"
        minSdk = 26
        targetSdk = 35
        versionCode = 75
        versionName = "2.29.1"

        // Where the update checker looks for releases.
        buildConfigField("String", "GITHUB_OWNER", "\"Ian-Nicholls89\"")
        buildConfigField("String", "GITHUB_REPO", "\"SpenDroid\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9 carries Kotlin itself; the compiler is configured through its own block.
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.work.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    // Android ships org.json; a JVM unit test needs a real implementation.
    testImplementation(libs.org.json)
}