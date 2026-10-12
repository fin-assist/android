// Crashes, ANR and non-fatal errors in Tracer (VK, the RuStore console). Used by both flavors; stays off in a
// build without `pf.tracerAppToken` (README, «Ключи SDK»).
plugins {
    id("pf.android.library")
    id("pf.hilt")
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.tracking)
    // `api`: the application class extends CrashReportingApplication, whose supertype comes from the SDK.
    api(platform(libs.tracer.platform))
    api(libs.tracer.crash.report)
}
