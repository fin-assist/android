pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // RuStore SDK (Remote Config). Only needed by :core:toggles-rustore.
        maven("https://artifactory-external.vkpartner.ru/artifactory/maven")
    }
}

rootProject.name = "ponyatnye-finansy"

include(":app")

// Core: shared infrastructure. No api/impl split — these are not features.
include(":core:common")
include(":core:designsystem")
include(":core:network")
include(":core:navigation")
include(":core:toggles")
include(":core:toggles-local")
include(":core:toggles-rustore")
include(":core:tracking")
include(":core:tracking-log")
include(":core:tracking-mytracker")
include(":core:mock-backend")

// Features: every feature is <name>:api (interfaces, routes, models) + <name>:impl (data/domain/ui).
listOf("auth", "applock", "operations", "statements", "analytics", "assistant", "profile").forEach { f ->
    include(":feature:$f:api")
    include(":feature:$f:impl")
}
