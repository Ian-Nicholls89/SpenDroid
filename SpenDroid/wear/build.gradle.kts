import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * The watch half of SpenDroid: a complication and nothing else. It shares the phone app's
 * application id and signing key - the Wear OS data layer only connects the two halves of
 * one app, and it knows them by exactly those.
 */
android {
    namespace = "com.spendroid.wear"
    compileSdk = 36

    val releaseKeystore = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    val keystorePath = System.getenv("SPENDROID_KEYSTORE")
        ?: releaseKeystore.getProperty("storeFile")
    val keystoreFile = keystorePath?.let { rootProject.file(it) }?.takeIf { it.exists() }
    val debugKeystore = rootProject.file("app/debug.keystore")

    signingConfigs {
        getByName("debug") {
            storeFile = debugKeystore
            storePassword = "android"
            keyAlias = "debug"
            keyPassword = "android"
        }
        create("release") {
            if (keystoreFile != null) {
                storeFile = keystoreFile
                storePassword = System.getenv("SPENDROID_KEYSTORE_PASSWORD")
                    ?: releaseKeystore.getProperty("storePassword")
                keyAlias = System.getenv("SPENDROID_KEY_ALIAS")
                    ?: releaseKeystore.getProperty("keyAlias")
                keyPassword = System.getenv("SPENDROID_KEY_PASSWORD")
                    ?: releaseKeystore.getProperty("keyPassword")
            } else {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "debug"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        applicationId = "com.spendroid"
        // Wear OS 3, the oldest the Pixel Watch line has run.
        minSdk = 30
        targetSdk = 35
        versionCode = providers.gradleProperty("wearVersionCode").get().toInt()
        versionName = providers.gradleProperty("wearVersionName").get()
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.wear.complications.data.source.ktx)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    // The screens a tap on the complication opens.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material3)
    // The full lists open on screens of their own, swiped away to go back.
    implementation(libs.wear.compose.navigation)
    // Tiles: the budget and this cycle's categories, a swipe from the watch face.
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material3)
    implementation(libs.concurrent.futures.ktx)
    implementation(libs.wear.remote.interactions)
    // The roundup's icon on the watch face, kept there until it is read.
    implementation(libs.wear.ongoing)
    testImplementation(libs.junit)
}
