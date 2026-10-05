plugins { id("pf.feature.impl") }
dependencies {
    implementation(libs.tink.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
}
