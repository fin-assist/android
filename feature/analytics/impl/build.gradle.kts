plugins {
    id("pf.feature.impl")
    id("pf.screenshots")
}

dependencies {
    implementation(projects.feature.operations.api)
    implementation(projects.feature.assistant.api)
    implementation(projects.feature.statements.api)
}
