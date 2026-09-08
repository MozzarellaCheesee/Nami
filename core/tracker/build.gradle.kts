import java.net.URI

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Исходники libopenmpt и game-music-emu не лежат в git (это ~13 МБ чужого дерева) - качаем их
// в native/third_party так же, как core:native собирает свои .so локально через cargo-ndk.
// Делается на конфигурации, а не отдельной задачей: AGP опрашивает ndk-build уже при
// конфигурации варианта, к этому моменту Android.mk апстрима должны существовать.
val thirdParty = rootProject.file("native/third_party")

fun fetch(targetName: String, url: String, extractedName: String, mkSourceDir: String?) {
    val target = File(thirdParty, targetName)
    if (File(target, "Android.mk").exists()) return
    logger.lifecycle("Скачиваю исходники $targetName из $url")
    thirdParty.mkdirs()
    val archive = File(thirdParty, "$targetName.tar.gz")
    URI(url).toURL().openStream().use { input ->
        archive.outputStream().use { output -> input.copyTo(output) }
    }
    copy {
        from(tarTree(resources.gzip(archive)))
        into(thirdParty)
    }
    archive.delete()
    target.deleteRecursively()
    File(thirdParty, extractedName).renameTo(target)
    // libopenmpt держит свой Android.mk в build/android_ndk и требует скопировать его в корень
    // дерева (build/android_ndk/README.AndroidNDK.txt) - иначе относительные пути к исходникам
    // в нём не сходятся. У game-music-emu Android.mk уже лежит в корне.
    if (mkSourceDir != null) {
        File(target, mkSourceDir).listFiles()?.filter { it.name.endsWith(".mk") }
            ?.forEach { it.copyTo(File(target, it.name), overwrite = true) }
    }
}

fetch(
    targetName = "libopenmpt",
    url = "https://lib.openmpt.org/files/libopenmpt/src/libopenmpt-0.8.9%2Brelease.makefile.tar.gz",
    extractedName = "libopenmpt-0.8.9+release",
    mkSourceDir = "build/android_ndk",
)
fetch(
    targetName = "game-music-emu",
    url = "https://codeload.github.com/libgme/game-music-emu/tar.gz/refs/heads/master",
    extractedName = "game-music-emu-master",
    mkSourceDir = null,
)

android {
    namespace = "dev.nami.core.tracker"
    compileSdk = 35
    // В SDK установлен только этот NDK - без явной версии AGP просит свою дефолтную и падает.
    ndkVersion = "30.0.15729638"
    defaultConfig {
        minSdk = 26
        // Те же ABI, что собирает cargo-ndk для tag-reader (core/native) - смысла тащить x86
        // в проект, который никогда не собирался под эмулятор, нет.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    externalNativeBuild {
        ndkBuild { path = rootProject.file("native/jni/Android.mk") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
kotlin { jvmToolchain(21) }
