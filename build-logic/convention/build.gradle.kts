plugins {
    `kotlin-dsl`
}

group = "ru.finassist.pf.buildlogic"

// No toolchain: the plugins run inside the Gradle daemon, so they are compiled for whatever JDK runs it
// (pinned to 21 by gradle/gradle-daemon-jvm.properties). A toolchain here would demand a separate JDK install.

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
    compileOnly(libs.kover.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "pf.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "pf.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "pf.android.library.compose"
            implementationClass = "AndroidLibraryComposeConventionPlugin"
        }
        register("jvmLibrary") {
            id = "pf.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("hilt") {
            id = "pf.hilt"
            implementationClass = "HiltConventionPlugin"
        }
        register("featureApi") {
            id = "pf.feature.api"
            implementationClass = "FeatureApiConventionPlugin"
        }
        register("featureImpl") {
            id = "pf.feature.impl"
            implementationClass = "FeatureImplConventionPlugin"
        }
        register("screenshots") {
            id = "pf.screenshots"
            implementationClass = "ScreenshotsConventionPlugin"
        }
        register("moduleRules") {
            id = "pf.module.rules"
            implementationClass = "ModuleRulesPlugin"
        }
    }
}
