import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.baselineprofile)
}

// versionCode was hardcoded at 1 for every dev build this whole project - Android's package
// installer can silently refuse to reinstall an APK whose versionCode isn't strictly higher than
// what's already on the device (no error shown on many OEMs, it just doesn't update), which means
// a locally sideloaded test build could be running stale code indefinitely. Derive it from the
// git commit count instead so every build from a new commit is guaranteed installable over the
// last one.
val gitCommitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim().toIntOrNull() ?: 1

val localProps = Properties()
run {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { localProps.load(it) }
}

android {
    namespace = "dev.nami.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.nami.app"
        minSdk = 26
        targetSdk = 35
        versionCode = gitCommitCount
        versionName = "0.1.2-beta.10"
    }
    val keystoreFile = rootProject.file("secrets/nami.jks")
    val storePass = System.getenv("NAMI_KEYSTORE_PASSWORD")
        ?: localProps.getProperty("nami.keystore.storePassword")
    val keyAlias = System.getenv("NAMI_KEYSTORE_ALIAS")
        ?: localProps.getProperty("nami.keystore.keyAlias")
    val keyPass = System.getenv("NAMI_KEY_PASSWORD")
        ?: localProps.getProperty("nami.keystore.keyPassword")

    val hasReleaseSigning = keystoreFile.exists() && !storePass.isNullOrBlank() && !keyAlias.isNullOrBlank() && !keyPass.isNullOrBlank()

    // Пароли берутся из local.properties или env-переменных (см. secrets/nami.jks).
    // Если ключа нет (CI без настроенных секретов или dev-сборка), автоматически
    // переключаемся на debug-подпись, чтобы packageRelease не падал с ошибкой.
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                this.storeFile = keystoreFile
                this.storePassword = storePass
                this.keyAlias = keyAlias
                this.keyPassword = keyPass
            }
        }
    }
    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    // kuromoji-ipadic (furigana) and jakarta.inject both ship the same META-INF text files --
    // harmless license/notice duplicates, not a real conflict.
    packaging {
        resources {
            excludes += "META-INF/*.md"
        }
        jniLibs {
            pickFirsts += listOf("**/libc++_shared.so")
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
    // Только ради Theme.AppCompat как родителя Theme.Nami - см. res/values/themes.xml.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.windowsize)
    implementation(libs.androidx.window)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.coil.compose)
    // Coil 3 без этого не грузит ни один http(s):// URL вообще (обложки треков "В сети" -
    // Audius/Archive/Piped) - молча остаются пустыми, ни ошибки, ни краша. Регистрируется сам
    // через механизм автообнаружения компонентов Coil, достаточно один раз на classpath
    // финального APK - подключать в каждый feature-модуль отдельно не нужно.
    implementation(libs.coil.network.okhttp)
    // Ставит baseline-профиль в ART при первом запуске - без него сам файл профиля в APK
    // ни на что не влияет.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    ksp(libs.hilt.compiler)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    // Разбору .json темы нужны настоящие org.json и android.graphics.Color, а не заглушки
    // из android.jar для юнит-тестов - см. ThemeIoTest.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
