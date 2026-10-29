plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

/**
 * The budget's maths and Penny's words - what's left to spend, bills, cards, forecasts, renewals,
 * what she says - with no Android in it: plain Kotlin, tested in seconds without a phone. The rows
 * it works on (the database's entities) live here too, as plain data with Room's annotations.
 *
 * An Android library only so that lint checks it against the phone's oldest Android (minSdk):
 * plain Kotlin can call a Java API that Android 8 hasn't got, and as a plain Kotlin module lint
 * says nothing. Nothing in it may use Android itself - PureDomainTest fails if anything does.
 */
android {
    namespace = "com.spendroid.domain"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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

    lint {
        abortOnError = true
    }
}

dependencies {
    // Annotations only (@Entity, @PrimaryKey): the database itself stays in the app.
    api(libs.room.common)
    testImplementation(libs.junit)
}
