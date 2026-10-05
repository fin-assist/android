plugins { id("pf.feature.impl") }
dependencies {
    implementation(project(":feature:operations:api"))
    implementation(project(":feature:analytics:api"))
    implementation(libs.androidx.activity.compose)
}
