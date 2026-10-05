plugins {
    alias(libs.plugins.pf.feature.impl)
}

dependencies {
    implementation(projects.feature.operations.api)
    implementation(projects.feature.assistant.api)
    implementation(projects.feature.statements.api)
}
