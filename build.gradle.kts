// Top-level build file
plugins {
    // AGP 8.7+ нужен для Kotlin 2.x в JVM target. 8.2.0 + Kotlin 2.x
    // в принципе должны работать, но на грани совместимости.
    id("com.android.application") version "8.2.0" apply false
    // Kotlin 2.1.0 — синхронизирован с зависимостью nextlib-media3ext
    // 0.8.3, которая собрана на Kotlin 2.1.0 (более старый компилятор
    // не может прочитать её metadata).
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}
