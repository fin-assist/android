plugins {
    id("pf.android.library")
    id("pf.hilt")
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    api(project(":core:common"))
    api(libs.okhttp)
    api(libs.okhttp.sse)
    api(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.logging)
    testImplementation(libs.okhttp.mockwebserver)
}
