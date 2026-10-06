plugins {
    id("pf.feature.impl")
}

dependencies {
    implementation(projects.feature.operations.api)
    implementation(projects.feature.analytics.api)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.datastore.preferences)
}
