// Renderers of the block catalog (api.md «Блоки», FIN-7): the Analytics screen and the assistant's answers draw
// server-composed blocks through one registry.
plugins {
    id("pf.android.library.compose")
}

dependencies {
    api(projects.core.api)
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
}
