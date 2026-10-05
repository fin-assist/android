// Analytics events and crash reporting interfaces. Providers live in :providers:tracking-*.
plugins {
    alias(libs.plugins.pf.jvm.library)
}

dependencies {
    implementation(projects.core.common)
}
