plugins {
    id("pf.android.library")
    id("pf.android.compose")
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    api(project(":core:common"))
    api(libs.androidx.navigation.compose)
    api(libs.kotlinx.serialization.json)
}
