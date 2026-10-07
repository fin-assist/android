plugins {
    id("pf.android.library.compose")
}

android {
    namespace = "ru.finassist.pf.core.screenshot"
}

// Test-only helpers for design-check snapshots: consumed as `testImplementation` by `pf.screenshots`.
dependencies {
    api(projects.core.designsystem)
    api(libs.robolectric)
    api(libs.roborazzi)
    api(libs.roborazzi.compose)
    api(libs.junit4)
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui.test.junit4)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
