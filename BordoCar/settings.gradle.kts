pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // the Mapbox map (the map of the next turn in Estrada)
        maven { url = uri("https://api.mapbox.com/downloads/v2/releases/maven") }
    }
}
rootProject.name = "BordoCar"
include(":app")
