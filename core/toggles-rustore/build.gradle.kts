plugins {
    id("pf.android.library")
    id("pf.hilt")
}
dependencies {
    implementation(project(":core:toggles"))
    // RuStore Remote Config SDK is added in stage 8 (artifactory-external.vkpartner.ru).
}
