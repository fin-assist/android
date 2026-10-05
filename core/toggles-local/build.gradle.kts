plugins {
    id("pf.android.library")
    id("pf.hilt")
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    implementation(project(":core:toggles"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
}
