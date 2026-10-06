// Feature flags and A/B segments from RuStore Remote Config. Used by the `prod` flavor.
plugins {
    id("pf.android.library")
    id("pf.hilt")
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.toggles)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.rustore.remoteconfig)
}
