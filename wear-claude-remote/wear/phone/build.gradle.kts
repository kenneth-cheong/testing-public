import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.kennethcheong.clauderemote.phone"
    compileSdk = 36

    defaultConfig {
        // Must match the watch app (and be signed with the same key) for the Data Layer to connect them.
        applicationId = "io.github.kennethcheong.clauderemote"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }

    // The bridge client, settings store and relay protocol are the watch app's own files.
    sourceSets {
        getByName("main") {
            java.srcDir("../app/src/main/java/io/github/kennethcheong/clauderemote/data")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.okhttp)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
