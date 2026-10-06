// Client API contract: DTOs and service interfaces generated/derived from openapi.yaml.
// Implemented by :core:network (real HTTP) and :mock:backend (in-process fake).
plugins {
    id("pf.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.common)
    api(libs.kotlinx.serialization.json)
}
