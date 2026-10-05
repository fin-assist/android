// Logcat-only tracker and crash reporter. Used by the `mock` flavor.
plugins {
    alias(libs.plugins.pf.android.library)
    alias(libs.plugins.pf.hilt)
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.tracking)
}
