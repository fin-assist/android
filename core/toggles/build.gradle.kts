// Feature-toggle interface + registry with code defaults. Providers live in :providers:toggles-*.
plugins {
    id("pf.jvm.library")
}

dependencies {
    implementation(projects.core.common)
}
