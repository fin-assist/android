plugins { id("pf.feature.impl") }
dependencies {
    implementation(project(":feature:operations:api"))
    implementation(project(":feature:assistant:api"))
    implementation(project(":feature:statements:api"))
    implementation(libs.androidx.datastore.preferences)
}
