plugins {
    id("pf.android.library.compose")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.api)
    api(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
}
