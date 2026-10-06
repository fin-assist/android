plugins {
    id("pf.feature.impl")
}

dependencies {
    implementation(projects.feature.auth.api)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.tink.android)
}
