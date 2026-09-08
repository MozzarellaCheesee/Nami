plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// versionCode was hardcoded at 1 for every dev build this whole project -- Android's package
// installer can silently refuse to reinstall an APK whose versionCode isn't strictly higher than
// what's already on the device (no error shown on many OEMs, it just doesn't update), which means
// a locally sideloaded test build could be running stale code indefinitely. Derive it from the
// git commit count instead so every build from a new commit is guaranteed installable over the
// last one.
val gitCommitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim().toIntOrNull() ?: 1

android {
    namespace = "dev.nami.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.nami.app"
        minSdk = 26
        targetSdk = 35
        versionCode = gitCommitCount
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    // kuromoji-ipadic (furigana) and jakarta.inject both ship the same META-INF text files --
    // harmless license/notice duplicates, not a real conflict.
    packaging {
        resources {
            excludes += "META-INF/*.md"
        }
    }
}
kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:native"))
    implementation(project(":core:whisper"))
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":player"))
    implementation(project(":feature:library"))
    implementation(project(":feature:player"))
    implementation(project(":feature:search"))
    implementation(project(":feature:playlists"))
    implementation(project(":feature:trash"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
