package ru.finassist.pf.buildlogic

import java.io.File
import java.util.Properties

/**
 * The Android SDK for tests that apply AGP to ProjectBuilder projects, in AGP's order: `sdk.dir` of the nearest
 * `local.properties` at or above [start] (Android Studio and scripts/agent-setup.sh write it), then `ANDROID_HOME`,
 * then `ANDROID_SDK_ROOT` (the CI runner). Null when there is none.
 */
internal fun findAndroidSdk(env: (String) -> String?, start: File): String? =
    generateSequence(start.absoluteFile) { it.parentFile }
        .map { File(it, "local.properties") }
        .firstOrNull { it.isFile }
        ?.let { file -> Properties().apply { file.inputStream().use(::load) }.getProperty("sdk.dir") }
        ?: env("ANDROID_HOME")
        ?: env("ANDROID_SDK_ROOT")
