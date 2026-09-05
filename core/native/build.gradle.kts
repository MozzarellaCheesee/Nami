plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.nami.core.nativebridge"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDir("src/main/jniLibs")
            kotlin.srcDir("$buildDir/generated/uniffi")
        }
    }
}
kotlin { jvmToolchain(21) }

val nativeCrateDir = rootProject.file("native/tag-reader")
val generatedBindingsDir = layout.buildDirectory.dir("generated/uniffi")

val cargoNdkBuild by tasks.registering(Exec::class) {
    workingDir = nativeCrateDir
    inputs.dir(nativeCrateDir.resolve("src"))
    outputs.dir(file("src/main/jniLibs"))
    commandLine(
        "cargo", "ndk",
        "-o", file("src/main/jniLibs").absolutePath,
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "build", "--release",
    )
}

// uniffi-bindgen introspects the library by loading it on the HOST, so it needs a
// host-architecture build (Windows .dll here), separate from the Android .so's above.
val cargoHostBuild by tasks.registering(Exec::class) {
    workingDir = nativeCrateDir
    inputs.dir(nativeCrateDir.resolve("src"))
    outputs.dir(nativeCrateDir.resolve("../target/release"))
    commandLine("cargo", "build", "--release")
}

val generateUniffiBindings by tasks.registering(Exec::class) {
    dependsOn(cargoNdkBuild, cargoHostBuild)
    workingDir = nativeCrateDir
    outputs.dir(generatedBindingsDir)
    doFirst { generatedBindingsDir.get().asFile.mkdirs() }
    commandLine(
        "cargo", "run", "--bin", "uniffi-bindgen", "--",
        // ponytail: hardcoded to Windows host artifact (.dll); switch on OS if this
        // project ever builds on Linux/macOS dev machines too.
        "generate", "--library", nativeCrateDir.resolve("../target/release/tag_reader.dll").absolutePath,
        "--language", "kotlin",
        "--out-dir", generatedBindingsDir.get().asFile.absolutePath,
    )
}

tasks.named("preBuild") { dependsOn(generateUniffiBindings) }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // UniFFI's generated Kotlin bindings call into JNI via JNA; the @aar classifier
    // pulls the Android-compatible build (bundles native libs for host JVM too).
    implementation("net.java.dev.jna:jna:5.14.0@aar")
}
