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
        }
    }
}
kotlin { jvmToolchain(21) }

val nativeCrateDir = rootProject.file("native/tag-reader")
val generatedBindingsDir = layout.buildDirectory.dir("generated/uniffi")

val hasPrebuiltLibs = file("src/main/jniLibs/arm64-v8a/libtag_reader.so").exists() &&
    file("src/main/jniLibs/armeabi-v7a/libtag_reader.so").exists()
val hasPrebuiltBindings = file("src/main/kotlin/uniffi/tag_reader/tag_reader.kt").exists()

val cargoNdkBuild by tasks.registering(Exec::class) {
    onlyIf { !hasPrebuiltLibs }
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
    onlyIf { !hasPrebuiltBindings }
    workingDir = nativeCrateDir
    inputs.dir(nativeCrateDir.resolve("src"))
    outputs.dir(nativeCrateDir.resolve("../target/release"))
    commandLine("cargo", "build", "--release")
}

val generateUniffiBindings by tasks.registering(Exec::class) {
    onlyIf { !hasPrebuiltBindings }
    dependsOn(cargoNdkBuild, cargoHostBuild)
    workingDir = nativeCrateDir
    outputs.dir(generatedBindingsDir)
    doFirst { generatedBindingsDir.get().asFile.mkdirs() }
    val libExt = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "dll" else if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) "dylib" else "so"
    val libPrefix = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "" else "lib"
    commandLine(
        "cargo", "run", "--bin", "uniffi-bindgen", "--",
        "generate", "--library", nativeCrateDir.resolve("../target/release/${libPrefix}tag_reader.${libExt}").absolutePath,
        "--language", "kotlin",
        "--out-dir", generatedBindingsDir.get().asFile.absolutePath,
    )
}

if (!hasPrebuiltBindings) {
    android.sourceSets.getByName("main").kotlin.srcDir("$buildDir/generated/uniffi")
    tasks.named("preBuild") { dependsOn(generateUniffiBindings) }
}

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
