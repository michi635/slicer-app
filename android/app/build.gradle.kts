plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version from CI: versionCode = build number, so every build installs over the previous one.
val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()

android {
    namespace = "com.michi635.slicer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.michi635.slicer"
        minSdk = 28
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // A fixed key kept in the repository: every version installs over the previous one and keeps its settings.
        create("release") {
            storeFile = rootProject.file("signing/release.jks")
            storePassword = "slicer-app"
            keyAlias = "slicer"
            keyPassword = "slicer-app"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // The slicer engine is an executable shipped as lib/arm64-v8a/libslicercore.so. It must be extracted to the
        // native library directory at install time, the only place an app may run its own programs from.
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libslicercore.so"
        }
    }
    androidResources {
        noCompress += "zip"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
