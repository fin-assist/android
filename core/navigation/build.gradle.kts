plugins {
    alias(libs.plugins.pf.android.library.compose)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(projects.core.common)
    api(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
}
