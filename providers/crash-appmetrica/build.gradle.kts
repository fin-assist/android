// Crashes, ANR and non-fatal errors in AppMetrica. Used by the `prod` flavor.
plugins {
    id("pf.android.library")
    id("pf.hilt")
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.tracking)
    implementation(libs.appmetrica.analytics)
}
