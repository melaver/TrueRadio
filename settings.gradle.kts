pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.google.com") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Spotify App Remote SDK is distributed via JitPack
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "TrueRadio"
include(":app")
