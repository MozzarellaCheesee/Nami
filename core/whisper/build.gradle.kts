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
        }
    }
}
kotlin { jvmToolchain(21) }

val nativeCrateDir = rootProject.file("native/whisper-align")
val generatedBindingsDir = layout.buildDirectory.dir("generated/uniffi")

val hasPrebuiltLibs = file("src/main/jniLibs/arm64-v8a/libwhisper_align.so").exists()
val hasPrebuiltBindings = file("src/main/kotlin/uniffi/whisper_align/whisper_align.kt").exists()

if (!hasPrebuiltBindings || !hasPrebuiltLibs) {
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
        listOf("CXX", "CC", "CMAKE_CXX_COMPILER", "CMAKE_C_COMPILER", "CMake", "CMAKE").forEach { environment.remove(it) }
        commandLine(
            "cargo", "ndk",
            "-o", file("src/main/jniLibs").absolutePath,
            "-t", "arm64-v8a",
            "build", "--release",
        )
        doLast {
            copy {
                from("$ndkHome/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so")
                into(file("src/main/jniLibs/arm64-v8a"))
            }
        }
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
        val libExt = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "dll" else if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) "dylib" else "so"
        val libPrefix = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "" else "lib"
        commandLine(
            "cargo", "run", "--bin", "uniffi-bindgen", "--",
            "generate", "--library", nativeCrateDir.resolve("../target/release/${libPrefix}whisper_align.${libExt}").absolutePath,
            "--language", "kotlin",
            "--out-dir", generatedBindingsDir.get().asFile.absolutePath,
        )
    }

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
    implementation("net.java.dev.jna:jna:5.14.0@aar")
}
