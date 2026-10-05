plugins {
    alias(libs.plugins.pf.feature.impl)
}

dependencies {
    implementation(projects.feature.auth.api)
}
