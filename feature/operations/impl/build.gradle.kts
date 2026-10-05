plugins {
    alias(libs.plugins.pf.feature.impl)
}

dependencies {
    implementation(projects.feature.statements.api)
}
