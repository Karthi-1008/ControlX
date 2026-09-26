pluginManagement {
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
    }
}

rootProject.name = "gba-tv-suite"

include(":gba-tv-player:app")
include(":gba-tv-player:native-mgba")
include(":pocket-pad-controller:app")
