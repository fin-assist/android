plugins {
    id("pf.android.library.compose")
}

dependencies {
    implementation(projects.core.common)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
}
