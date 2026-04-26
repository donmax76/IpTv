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
        // nextlib-media3ext: prebuilt FFmpeg software decoders для Media3.
        // Нужен для MP2 (mpeg-L2) на боксах без аппаратного декодера, и
        // заодно AC3/EAC3, DTS и пр. — те же кодеки, что воспроизводит VLC.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "TVViewer"
include(":app")
