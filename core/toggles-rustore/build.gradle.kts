plugins {
    id("pf.android.library")
    id("pf.hilt")
}
dependencies {
    implementation(project(":core:toggles"))
    implementation(project(":core:tracking"))
    implementation(platform(libs.rustore.bom))
    implementation(libs.rustore.remoteconfig)
}
