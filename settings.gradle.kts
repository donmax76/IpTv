pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // nextlib-media3ext опубликован ТОЛЬКО на JitPack (готовая
        // FFmpeg-сборка для Media3 — даёт MP2/AC3/EAC3/DTS-декодеры).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "TVViewer"
include(":app")
