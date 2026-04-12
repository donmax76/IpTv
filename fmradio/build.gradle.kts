plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Auto-version from git: versionCode = commit count, versionName = "3.0.<commit_count>"
// Only digits — no hashes or dates in the version string.
fun gitVersionCode(): Int {
    return try {
        val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(projectDir).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 8
    } catch (_: Exception) { 8 }
}

fun gitVersionName(): String {
    return "3.0.${gitVersionCode()}"
}

android {
    namespace = "com.fmradio"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fmradio.rtlsdr"
        minSdk = 21
        targetSdk = 34
        versionCode = gitVersionCode()
        versionName = gitVersionName()
    }

    // Native DSP library for real-time FM demodulation (C++ via JNI)
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "26.1.10909125"

    signingConfigs {
        getByName("debug") {
            // Use default debug keystore — consistent key for update installs
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.media:media:1.7.0")
}
