plugins { `kotlin-dsl` }

group = "ru.finassist.pf.buildlogic"

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.kotlin.compose.gradle.plugin)
    compileOnly(libs.ksp.gradle.plugin)
    compileOnly(libs.hilt.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") { id = "pf.android.application"; implementationClass = "pf.AndroidApplicationPlugin" }
        register("androidLibrary") { id = "pf.android.library"; implementationClass = "pf.AndroidLibraryPlugin" }
        register("androidCompose") { id = "pf.android.compose"; implementationClass = "pf.AndroidComposePlugin" }
        register("hilt") { id = "pf.hilt"; implementationClass = "pf.HiltPlugin" }
        register("featureApi") { id = "pf.feature.api"; implementationClass = "pf.FeatureApiPlugin" }
        register("featureImpl") { id = "pf.feature.impl"; implementationClass = "pf.FeatureImplPlugin" }
        register("moduleRules") { id = "pf.module-rules"; implementationClass = "pf.ModuleRulesPlugin" }
    }
}
