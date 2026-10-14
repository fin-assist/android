plugins {
    id("pf.feature.impl")
    id("pf.screenshots")
}

dependencies {
    implementation(projects.feature.auth.api)
    implementation(projects.feature.applock.api)
    implementation(projects.feature.statements.api)
    implementation(projects.feature.assistant.api)
    // TimeZoneSync: the pending zone record and the return-from-background trigger (FIN-91).
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.process)
}
