// Remote configuration for the stage and production flavors.
plugins {
    id("pf.android.library")
    id("pf.hilt")
}

dependencies {
    implementation(projects.core.toggles)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.varioqub.config)
    implementation(libs.varioqub.appmetrica.adapter)

    testImplementation(libs.robolectric)
}
