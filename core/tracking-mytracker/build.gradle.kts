plugins {
    id("pf.android.library")
    id("pf.hilt")
}
dependencies {
    implementation(project(":core:tracking"))
    implementation(libs.mytracker.sdk)
    implementation(libs.appmetrica.analytics)
}
