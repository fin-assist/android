plugins {
    alias(libs.plugins.pf.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.pf.hilt)
}

dependencies {
    api(projects.core.api)
    implementation(projects.core.common)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.tink.android)

    testImplementation(libs.okhttp.mockwebserver)
}
