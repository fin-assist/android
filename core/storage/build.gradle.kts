// On-device storage shared by both flavors: DataStore preferences and the refresh-token store
// encrypted with Tink under an Android Keystore master key.
plugins {
    alias(libs.plugins.pf.android.library)
    alias(libs.plugins.pf.hilt)
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.api)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.tink.android)
}
