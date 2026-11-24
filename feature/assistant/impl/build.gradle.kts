plugins {
    id("pf.feature.impl")
    id("pf.screenshots")
}

dependencies {
    implementation(projects.feature.auth.api)
    implementation(projects.feature.operations.api)
    implementation(projects.feature.analytics.api)
    implementation(libs.androidx.browser)
    implementation(projects.core.blocks)
    testImplementation(libs.androidx.datastore.preferences)
}
