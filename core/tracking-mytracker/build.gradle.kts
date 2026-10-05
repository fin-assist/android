plugins {
    id("pf.android.library")
    id("pf.hilt")
}
dependencies {
    implementation(project(":core:tracking"))
    // MyTracker SDK is added in stage 8 (mavenCentral: com.my.tracker:mytracker-sdk).
}
