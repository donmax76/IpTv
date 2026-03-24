plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Auto-version from git: versionCode = commit count, versionName = "2.0-<hash>-<date>"
fun gitVersionCode(): Int {
    return try {
        val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(projectDir).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 8
    } catch (_: Exception) { 8 }
}

fun gitVersionName(): String {
    return try {
        val hash = ProcessBuilder("git", "rev-parse", "--short=7", "HEAD")
            .directory(projectDir).redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText().trim()
        val date = ProcessBuilder("git", "log", "-1", "--format=%cd", "--date=format:%Y%m%d-%H%M")
            .directory(projectDir).redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText().trim()
        "2.0-$hash-$date"
    } catch (_: Exception) { "2.0-unknown" }
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

    // Native USB library (optional — requires NDK)
    // To enable: install NDK via sdkmanager "ndk;25.2.9519653"
    // then uncomment the block below.
    // Without NDK, app uses Java USB API fallback (NativeUsb.kt handles this).
    //
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/CMakeLists.txt")
    //         version = "3.18.1"
    //     }
    // }

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
