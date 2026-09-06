plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.nami.core.whisperalign"
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

val nativeCrateDir = rootProject.file("native/whisper-align")
val generatedBindingsDir = layout.buildDirectory.dir("generated/uniffi")

// whisper.cpp is heavy to cross-compile (its own CMake/C++ build via the NDK toolchain, on
// top of the usual cargo-ndk clang setup) -- the exact env this needs was worked out by hand
// against this machine's NDK/CMake install; see android-arm64.toolchain.cmake next to the crate.
// arm64-v8a only: real-time-ish Whisper inference on a 32-bit device would be unusably slow
// anyway, and this cuts the native build matrix in half.
val ndkHome = System.getenv("ANDROID_NDK_HOME") ?: System.getenv("ANDROID_HOME")?.let { sdk ->
    file("$sdk/ndk").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }?.absolutePath
} ?: error("ANDROID_NDK_HOME not set and no NDK found under ANDROID_HOME/ndk")
val ndkClangBin = "$ndkHome/toolchains/llvm/prebuilt/windows-x86_64/bin"

val cargoNdkBuild by tasks.registering(Exec::class) {
    workingDir = nativeCrateDir
    inputs.dir(nativeCrateDir.resolve("src"))
    outputs.dir(file("src/main/jniLibs"))
    environment("LIBCLANG_PATH", ndkClangBin)
    environment("ANDROID_NDK_HOME", ndkHome)
    environment("ANDROID_NDK_ROOT", ndkHome)
    environment("CMAKE_TOOLCHAIN_FILE_aarch64_linux_android", nativeCrateDir.resolve("android-arm64.toolchain.cmake").absolutePath)
    environment("CMAKE_GENERATOR", "MinGW Makefiles")
    environment("CMAKE_MAKE_PROGRAM", "C:/gcc/bin/make.exe")
    // A machine-wide CXX/CC/CMAKE(_C(XX)_COMPILER) env var (set outside this project, for an
    // unrelated MinGW toolchain) otherwise leaks in and hijacks this cross-compile -- setting
    // them to "" still leaves them "present but empty" to the build script (Ok("") is not
    // Err), so they must be removed from the map outright. Windows env vars are effectively
    // case-insensitive but Gradle's environment map isn't guaranteed to normalize case for
    // you, so both "CMake" and "CMAKE" need removing.
    listOf("CXX", "CC", "CMAKE_CXX_COMPILER", "CMAKE_C_COMPILER", "CMake", "CMAKE").forEach { environment.remove(it) }
    commandLine(
        "cargo", "ndk",
        "-o", file("src/main/jniLibs").absolutePath,
        "-t", "arm64-v8a",
        "build", "--release",
    )
}

val cargoHostBuild by tasks.registering(Exec::class) {
    workingDir = nativeCrateDir
    inputs.dir(nativeCrateDir.resolve("src"))
    outputs.dir(nativeCrateDir.resolve("../target/release"))
    listOf("CXX", "CC", "CMAKE_CXX_COMPILER", "CMAKE_C_COMPILER", "CMake", "CMAKE").forEach { environment.remove(it) }
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
        "generate", "--library", nativeCrateDir.resolve("../target/release/whisper_align.dll").absolutePath,
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
    implementation("net.java.dev.jna:jna:5.14.0@aar")
}
