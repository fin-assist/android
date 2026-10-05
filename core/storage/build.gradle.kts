// On-device storage shared by both flavors: DataStore preferences and the refresh-token store
// encrypted with Tink under an Android Keystore master key.
plugins {
    id("pf.android.library")
    id("pf.hilt")
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.api)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.tink.android)
}
