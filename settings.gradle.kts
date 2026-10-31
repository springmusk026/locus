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
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Locus"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":core:model")
include(":core:llm")
include(":core:agent")
include(":core:browser")
include(":core:linux")
include(":core:ondevice")
include(":core:database")
include(":core:datastore")
include(":core:data")
include(":core:designsystem")
include(":core:markdown")
include(":core:testing")
include(":feature:chat")
include(":feature:settings")
include(":feature:workspace")
include(":feature:activity")
include(":feature:browser")
include(":feature:library")
include(":feature:tasks")
include(":feature:onboarding")
include(":feature:analytics")
