plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "dev.nami.baselineprofile"
    compileSdk = 35
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    defaultConfig {
        // Macrobenchmark умеет писать профиль только с API 28 - на более старых просто нечем
        // собирать трассу запуска. Само приложение по-прежнему minSdk 26, профиль на нём
        // ставится через profileinstaller как обычный ресурс.
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":app"
}
kotlin { jvmToolchain(21) }

baselineProfile {
    // Профиль генерируется на подключённом устройстве/эмуляторе, gradle managed device тут не
    // заводим - лишний слой, который сам тянет скачивание образа системы.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
