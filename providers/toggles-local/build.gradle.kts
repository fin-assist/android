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

    // LocalFeatureFlags reads its asset through a Context; tests run the real DataStore on Robolectric.
    testImplementation(libs.robolectric)
}

android {
    // src/test/assets/flags.json stands in for the app's asset.
    testOptions.unitTests.isIncludeAndroidResources = true
}
