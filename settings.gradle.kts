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

rootProject.name = "D-Agent"

include(":core")

// Without an Android SDK only the pure-Kotlin :core module is built, so the agent
// engine can be compiled and tested on any machine with a JDK.
val androidSdkPresent: Boolean = run {
    val localProps = file("local.properties")
    val fromLocal = localProps.exists() && localProps.readLines().any { it.trim().startsWith("sdk.dir=") }
    fromLocal || System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null
}

if (androidSdkPresent) {
    include(":app")
} else {
    // The Android Gradle plugin is not even resolved, so this works offline from Google's Maven.
    rootProject.buildFileName = "core-only.gradle.kts"
    logger.lifecycle("D-Agent: no Android SDK found, building :core only.")
}
