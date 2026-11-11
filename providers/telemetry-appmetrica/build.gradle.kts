plugins {
    id("pf.android.library")
    id("pf.hilt")
}

dependencies {
    implementation(projects.core.tracking)
    implementation(libs.appmetrica.analytics) {
        exclude(group = "io.appmetrica.analytics", module = "analytics-ad-revenue")
        exclude(group = "io.appmetrica.analytics", module = "analytics-appsetid")
        exclude(group = "io.appmetrica.analytics", module = "analytics-billing")
        exclude(group = "io.appmetrica.analytics", module = "analytics-identifiers")
        exclude(group = "io.appmetrica.analytics", module = "analytics-location")
        exclude(group = "io.appmetrica.analytics", module = "analytics-remote-permissions")
        exclude(group = "io.appmetrica.analytics", module = "analytics-screenshot")
    }
}
