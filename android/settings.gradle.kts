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

rootProject.name = "video-mover"

include(":core")

// The application module needs the Android SDK. Unit tests do not.
// `gradle :core:test -PskipApp=true` runs without it.
val skipApp = startParameter.projectProperties["skipApp"] == "true"
if (!skipApp) {
    include(":app")
}
