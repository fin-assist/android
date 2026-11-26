plugins {
    id("pf.feature.impl")
    id("pf.screenshots")
}

dependencies {
    implementation(libs.androidx.browser)
    implementation(libs.androidx.datastore.preferences)
}
