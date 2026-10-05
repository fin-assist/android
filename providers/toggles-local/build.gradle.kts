// Flags from a JSON asset / debug overrides. Used by the `mock` flavor and for local testing of toggles.
plugins {
    alias(libs.plugins.pf.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.pf.hilt)
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.toggles)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
}
