plugins {
    id("pf.android.library")
    id("pf.hilt")
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
}
