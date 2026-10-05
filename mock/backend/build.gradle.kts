// In-process fake backend for the `mock` flavor. Implements the :core:api service interfaces on top of
// a real T-Bank OFX statement: parsing, dedup, own-transfer pairs, refunds, aggregates, thresholds,
// streaming via Flow, daily assistant limit, idempotency keys.
plugins {
    alias(libs.plugins.pf.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.pf.hilt)
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.api)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
