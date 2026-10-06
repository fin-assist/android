plugins {
    id("pf.feature.api")
}

dependencies {
    api(projects.core.api)
    api(libs.kotlinx.coroutines.core)
}
