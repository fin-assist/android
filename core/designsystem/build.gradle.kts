plugins {
    id("pf.android.library")
    id("pf.android.compose")
}
dependencies {
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
}
