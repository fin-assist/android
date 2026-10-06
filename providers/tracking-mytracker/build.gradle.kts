// Product analytics events in MyTracker. Used by the `prod` flavor.
plugins {
    id("pf.android.library")
    id("pf.hilt")
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.tracking)
    implementation(libs.mytracker.sdk)
}
