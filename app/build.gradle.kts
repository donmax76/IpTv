plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tvviewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tvviewer"
        minSdk = 21
        targetSdk = 34
        // versionCode mirrors the CI build number in the release tag
        // (`v5.4-build<run_number>`) so UpdateChecker, which extracts the
        // build number from the tag, can compare it directly against
        // BuildConfig.VERSION_CODE. Local dev builds keep a stable code.
        val ciRun = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = ciRun ?: 34
        versionName = "5.4"

        // GitHub token for auto-submitting crash/error reports as issues.
        // Provided via repository secret IPTV_ISSUE_TOKEN; empty by default
        // so the app falls back to opening the browser.
        val issueToken = System.getenv("IPTV_ISSUE_TOKEN") ?: ""
        buildConfigField("String", "ISSUE_TOKEN", "\"$issueToken\"")
        buildConfigField("String", "ISSUE_REPO", "\"donmax76/iptv\"")
    }

    // Stable debug keystore committed to the repo so every CI build is
    // signed with the same key. Without this, each CI run generates a
    // random debug keystore and Android refuses to install the new APK
    // over the old one ("App not installed" / signature mismatch).
    signingConfigs {
        create("releaseLikeDebug") {
            storeFile = rootProject.file("debug-stable.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("releaseLikeDebug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("releaseLikeDebug")
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
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.viewpager2:viewpager2:1.0.0")

    // ExoPlayer for streaming (HLS, etc.)
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp for fetching playlists
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coil for channel logos
    implementation("io.coil-kt:coil:2.5.0")

    // Gson for JSON
    implementation("com.google.code.gson:gson:2.10.1")
}
