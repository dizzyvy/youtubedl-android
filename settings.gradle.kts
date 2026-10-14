pluginManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        gradlePluginPortal()
    }
}

include(":common", ":app", ":library", ":ffmpeg", ":aria2c")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
        jcenter()
        // e.g this is how you would add jitpack
        maven("https://jitpack.io")
        // Add any repositories you would be adding to all projects here
    }
}