// Logcat-only tracker and crash reporter. Used by the `mock` flavor.
plugins {
    id("pf.android.library")
    id("pf.hilt")
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.tracking)
}
