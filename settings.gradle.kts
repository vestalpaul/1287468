pluginManagement {
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
        google()
        mavenCentral()
    }
}

rootProject.name = "BuckCastAndroid"

include(":app")
include(":core:domain")
include(":core:data")
include(":core:sensors")
include(":feature:forecast")
include(":feature:map")
include(":feature:stands")
include(":feature:fieldlog")
include(":feature:tracker")
include(":feature:gemini")
include(":feature:settings")
