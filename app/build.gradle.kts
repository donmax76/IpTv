plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tvviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tvviewer"
        // minSdk оставлен 26 (Android 8.0): код, написанный во время
        // экспериментов с libmpv, использует API 26+, а реальные
        // пользователи (по логам) — Android 9+ (X4 X4 Android 11,
        // OPPO Android 9, Huawei Android 9). Возврат к 21 ничего не
        // даст и потенциально сломает сборку.
        minSdk = 26
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
        // so the app falls back to ntfy.sh.
        val issueToken = System.getenv("IPTV_ISSUE_TOKEN") ?: ""
        buildConfigField("String", "ISSUE_TOKEN", "\"$issueToken\"")
        buildConfigField("String", "ISSUE_REPO", "\"donmax76/iptv\"")
        // Token-less crash channel via ntfy.sh — anyone with the topic can
        // read it (security through obscurity), but no GitHub token or
        // user-side configuration is required. The topic is fixed to this
        // long random string and the developer reads messages with:
        //   curl https://ntfy.sh/$NTFY_TOPIC/json?poll=1
        buildConfigField("String", "NTFY_TOPIC", "\"tvviewer-donmax76-50090885b4d9a5e0\"")
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

    // ExoPlayer for streaming (HLS / DASH / RTSP).
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.0")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.5.0")
    implementation("androidx.media3:media3-ui:1.5.0")

    // FFmpeg-расширение для Media3 (nextlib) — софтверные декодеры
    // для MP2 / AC3 / EAC3 / DTS / FLAC / Vorbis. Без него на каналах
    // с этим аудио (например DVB-стримы Round 183 user-report) плеер
    // пишет "звук не поддерживается". Раньше Round 171 убрал nextlib
    // из-за конфликта с libmpv по FFmpeg-символам — но libmpv тоже
    // удалён (Round 175), конфликта больше нет.
    implementation("io.github.anilbeesetti.nextlib:nextlib-media3ext:0.8.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp for fetching playlists
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coil for channel logos
    implementation("io.coil-kt:coil:2.5.0")

    // Gson for JSON
    implementation("com.google.code.gson:gson:2.10.1")
}
