import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// STANDS4 lyrics fallback keys -- checked in this order: local.properties (stands4.uid/
// stands4.token, gitignored, lives at the repo root) first, then the STANDS4_UID/STANDS4_TOKEN
// env vars as a fallback for CI-style setups with no local.properties. Never committed either way.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun stands4Key(propertyName: String, envName: String): String =
    localProperties.getProperty(propertyName) ?: System.getenv(envName) ?: ""

android {
    namespace = "dev.nami.data"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        // Empty string (not found) just means Stands4Client's own isConfigured() stays false and
        // that fallback silently no-ops -- LRCLIB keeps working regardless.
        buildConfigField("String", "STANDS4_UID", "\"${stands4Key("stands4.uid", "STANDS4_UID")}\"")
        buildConfigField("String", "STANDS4_TOKEN", "\"${stands4Key("stands4.token", "STANDS4_TOKEN")}\"")
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:native"))
    implementation(project(":domain"))
    // Only for DsfToDopWav (Этап 10's DSD-over-PCM import conversion) -- :player depends on
    // :domain only, so this direction adds no cycle.
    implementation(project(":player"))
    implementation(libs.paging.runtime)
    implementation(libs.room.ktx)
    implementation(libs.hilt.android)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.core.ktx)
    implementation(libs.mlkit.translate)
    implementation(libs.kuromoji.ipadic)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}
