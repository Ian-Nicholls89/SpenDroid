import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    // The saved database layouts, for the migration tests to read.
    sourceSets {
        // Robolectric reads the debug build's assets; the release build carries none of this.
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The whole app, started in a test (AppRunTest), needs more than the default 512 MB.
        unitTests.all { it.maxHeapSize = "2g" }
    }
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
        versionCode = 216
        versionName = "0.8.6.1"
        // Native code - the database's encryption - for 64-bit phones only (0.8.6.1): the user's
        // is arm64-v8a, and the 32-bit copy was 1 MB for no one. When the app goes to Play, add
        // "armeabi-v7a" back: a bundle sends each phone only its own, so it costs the rest nothing.
        ndk { abiFilters += listOf("arm64-v8a") }

        // Where the update checker looks for releases.
        buildConfigField("String", "GITHUB_OWNER", "\"Ian-Nicholls89\"")
        buildConfigField("String", "GITHUB_REPO", "\"SpenDroid\"")
        // The watch app published with this build, so an install can be checked for it.
        buildConfigField("int", "WEAR_VERSION_CODE", providers.gradleProperty("wearVersionCode").get())
        buildConfigField("String", "WEAR_VERSION_NAME", "\"${providers.gradleProperty("wearVersionName").get()}\"")
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

    packaging {
        resources {
            // Bouncy Castle's data for algorithms the app never uses: the watch link only makes a
            // certificate. Picnic's tables alone were 1.2 MB, and R8 already drops their code.
            excludes += listOf(
                "org/bouncycastle/pqc/crypto/picnic/*.properties",
                "org/bouncycastle/**/CertPathReviewerMessages*.properties",
            )
        }
    }
}

// The PDF reader's Chinese, Japanese and Korean encodings (1.2 MB): a UK statement is set in
// Identity-encoded fonts, which stay. Removed from the merged assets, as a pattern can't name them alone.
tasks.matching { it.name.matches(Regex("merge(Debug|Release)Assets")) }.configureEach {
    doLast {
        outputs.files.forEach { out ->
            out.resolve("com/tom_roush/fontbox/resources/cmap").listFiles()
                ?.filter { !it.name.startsWith("Identity-") }
                ?.forEach { it.delete() }
        }
    }
}

dependencies {
    // Penny - her face, moods and moments - shared with the watch.
    implementation(project(":penny"))
    implementation(project(":domain"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    // Reading PDF statements on the phone (Apache 2.0).
    implementation(libs.pdfbox.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
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
    testImplementation(libs.room.testing)
    // The database encrypted on the phone, when the user turns it on (BSD-style licence).
    implementation(libs.sqlcipher.android)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.work.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.play.services.wearable)
    // Play services pulls in Fragment 1.1, which predates the activity-result API the app uses.
    implementation(libs.androidx.fragment)
    // The watch installer: ADB over Wi-Fi to the watch, the certificate it pairs with, and
    // access to the platform's TLS key export that pairing needs.
    implementation(libs.libadb.android)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.hiddenapibypass)
    implementation(libs.kotlinx.coroutines.play.services)
    // "Open on my computer": a small web server, running only while the user has it on.
    implementation(libs.nanohttpd)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    // Android ships org.json; a JVM unit test needs a real implementation.
    testImplementation(libs.org.json)
    // Screenshots of the screens, rendered on the JVM, to check a design without a phone.
    // Run with -Pscreenshots; skipped otherwise, so CI and the usual run are unaffected.
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    if (!project.hasProperty("screenshots")) {
        exclude("**/screenshots/**")
    } else {
        systemProperty("roborazzi.test.record", "true")
        systemProperty("roborazzi.output.dir", rootProject.file("build/screenshots").absolutePath)
    }
}

// Room writes each database version's tables here, for migrations to be tested against.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
