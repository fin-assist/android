pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // RuStore SDK (Remote Config), MyTracker — stage 8
        maven("https://artifactory-external.vkpartner.ru/artifactory/maven")
    }
}

rootProject.name = "pf-android"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

// Core: shared code. No feature knows about another feature except through its :api module.
include(":core:common")
include(":core:api")
include(":core:network")
include(":core:designsystem")
include(":core:navigation")
include(":core:toggles")
include(":core:tracking")

// Providers: implementations of core:toggles / core:tracking interfaces. Only :app depends on them.
include(":providers:toggles-local")
include(":providers:tracking-log")

// In-process fake backend for the `mock` flavor (implements :core:api).
include(":mock:backend")

// Features: each one is :api (interfaces, routes, models) + :impl (data, domain, ui).
listOf("auth", "applock", "operations", "statements", "analytics", "assistant", "profile").forEach { feature ->
    include(":feature:$feature:api")
    include(":feature:$feature:impl")
}
