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
        // Robolectric's 150 MB framework jar (app/build.gradle.kts,
        // robolectricSdk) from Google's mirror of Maven Central rather than
        // Maven Central itself, which answers the shared CI runners with
        // HTTP 429 (Too Many Requests). Only this one module comes from here.
        exclusiveContent {
            forRepository {
                maven("https://maven-central.storage-download.googleapis.com/maven2/") {
                    name = "MavenCentralGoogleMirror"
                }
            }
            filter { includeModule("org.robolectric", "android-all-instrumented") }
        }
    }
}

rootProject.name = "connectonion-android"
include(":app")
include(":baselineprofile")
