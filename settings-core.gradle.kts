// Dev-only settings file for building/testing the pure-Kotlin :core module
// without the Android SDK or Google's Maven repository:
//
//     gradle -c settings-core.gradle.kts :core:test
//
// The real build uses settings.gradle.kts and includes :app as well.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "Prime-Remote"
include(":core")
