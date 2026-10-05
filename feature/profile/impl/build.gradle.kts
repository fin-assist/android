plugins { id("pf.feature.impl") }
dependencies {
    implementation(project(":feature:auth:api"))
    implementation(project(":feature:applock:api"))
    implementation(project(":feature:statements:api"))
    implementation(project(":feature:assistant:api"))
}
dependencies { implementation(libs.androidx.datastore.preferences) }
