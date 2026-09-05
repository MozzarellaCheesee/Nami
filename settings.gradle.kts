pluginManagement {
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

rootProject.name = "nami"

include(
    ":app",
    ":core:model",
    ":core:database",
    ":core:designsystem",
    ":domain",
    ":data",
    ":player",
    ":feature:library",
    ":feature:player",
)
