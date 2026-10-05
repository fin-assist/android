plugins { id("pf.feature.impl") }
dependencies { implementation(project(":feature:auth:api")) }
dependencies {
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.datastore.preferences)
}
