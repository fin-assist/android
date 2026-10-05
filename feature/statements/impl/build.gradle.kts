plugins { id("pf.feature.impl") }
dependencies {
    implementation(project(":feature:operations:api"))
    implementation(project(":feature:analytics:api"))
    implementation(project(":feature:auth:api"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.activity.compose)
}
