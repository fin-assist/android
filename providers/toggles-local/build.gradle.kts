// Flags from a JSON asset / debug overrides. Used by the `mock` flavor and for local testing of toggles.
plugins {
    id("pf.android.library")
    alias(libs.plugins.kotlin.serialization)
    id("pf.hilt")
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.toggles)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
}
