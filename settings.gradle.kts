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
        // io.requery:sqlite-android (bundled SQLite with FTS5, needed because some devices'
        // system SQLite lacks it) is published only via JitPack, not Maven Central.
        maven("https://jitpack.io")
    }
}

rootProject.name = "nami"

include(
    ":app",
    ":core:model",
    ":core:database",
    ":core:designsystem",
    ":core:native",
    ":domain",
    ":data",
    ":player",
    ":feature:library",
    ":feature:player",
    ":feature:search",
    ":feature:playlists",
    ":feature:trash",
)
