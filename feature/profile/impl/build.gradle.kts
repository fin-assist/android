plugins {
    id("pf.feature.impl")
    id("pf.screenshots")
}

dependencies {
    implementation(projects.feature.auth.api)
    implementation(projects.feature.applock.api)
    implementation(projects.feature.statements.api)
    implementation(projects.feature.assistant.api)

    // A real AppPreferences over a temp-file DataStore in ProfileViewModelTest.
    testImplementation(libs.androidx.datastore.preferences)
}
