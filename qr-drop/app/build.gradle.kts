import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.kennethcheong.qrdrop"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.kennethcheong.qrdrop"
        minSdk = 30
        targetSdk = 35
        versionCode = 8
        versionName = "0.5.1"
    }

    // Release signing comes from the environment (CI secrets) when it's there;
    // otherwise the release APK is built unsigned and signed afterwards.
    val releaseKeystore = System.getenv("QRDROP_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storeType = "pkcs12"
                storePassword = System.getenv("QRDROP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("QRDROP_KEY_ALIAS") ?: "qrdrop"
                keyPassword = System.getenv("QRDROP_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Drop unused code and resources: the release APK is a few MB instead of ~24 MB.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
