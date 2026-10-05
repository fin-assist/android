plugins {
    alias(libs.plugins.pf.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
