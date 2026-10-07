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
        // RuStore SDK (Remote Config)
        maven("https://nexus-external.rustore.ru/repository/maven-rustore-exposed") {
            content { includeGroupByRegex("ru\\.rustore.*") }
        }
        maven("https://artifactory-external.vkpartner.ru/artifactory/maven") {
            content { includeGroupByRegex("ru\\.rustore.*") }
        }
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
include(":core:storage")
include(":core:screenshot-testing")

// Providers: implementations of core:toggles / core:tracking interfaces. Only :app depends on them.
include(":providers:toggles-local")
include(":providers:tracking-log")
include(":providers:toggles-rustore")
include(":providers:tracking-mytracker")
include(":providers:crash-appmetrica")

// In-process fake backend for the `mock` flavor (implements :core:api).
include(":mock:backend")

// Features: each one is :api (interfaces, routes, models) + :impl (data, domain, ui).
listOf("auth", "applock", "operations", "statements", "analytics", "assistant", "profile").forEach { feature ->
    include(":feature:$feature:api")
    include(":feature:$feature:impl")
}
