plugins { id("pf.feature.impl") }
dependencies { implementation(project(":feature:statements:api")) }
dependencies { implementation(libs.androidx.datastore.preferences) }
