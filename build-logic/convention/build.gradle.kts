plugins {
    `kotlin-dsl`
}

group = "ru.finassist.pf.buildlogic"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
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
        register("moduleRules") {
            id = "pf.module.rules"
            implementationClass = "ModuleRulesPlugin"
        }
    }
}
