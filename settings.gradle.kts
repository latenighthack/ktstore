pluginManagement {
    includeBuild("migration-gradle-plugin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ktstore"
include(":library")

// Explicit isolated library development; release builds use published dependencies.
apply(from = "gradle/fh-workspace.settings.gradle")

include(":browser-tests")

include(":migration-tooling")
include(":migration-example-spec", ":migration-example")
