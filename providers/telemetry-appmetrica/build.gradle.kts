plugins {
    id("pf.android.library")
    id("pf.hilt")
}

android {
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
}

dependencies {
    implementation(projects.core.tracking)
    testImplementation(libs.robolectric)
    implementation(libs.appmetrica.analytics) {
        exclude(group = "io.appmetrica.analytics", module = "analytics-ad-revenue")
        // AppMetrica uses this module's interfaces even for its no-Google-Play fallback.
        exclude(group = "com.google.android.gms", module = "play-services-appset")
        exclude(group = "io.appmetrica.analytics", module = "analytics-billing")
        exclude(group = "io.appmetrica.analytics", module = "analytics-identifiers")
        exclude(group = "io.appmetrica.analytics", module = "analytics-location")
        exclude(group = "io.appmetrica.analytics", module = "analytics-remote-permissions")
        exclude(group = "io.appmetrica.analytics", module = "analytics-screenshot")
    }
}
