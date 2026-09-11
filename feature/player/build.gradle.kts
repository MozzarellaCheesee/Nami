plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.nami.feature.player"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":domain"))
    implementation(project(":player"))
    implementation(project(":feature:playlists"))
    // RemoteCastController (:player) - Hilt-синглтон, общий у экрана выбора устройства и
    // PlaybackService; KSP здесь обязан разрешить все типы его сигнатуры, включая media3-плееры.
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette.ktx)
    // Пункт "Трансляция" открывает штатный диалог выбора Cast-устройства - см. NowPlayingScreen.
    // appcompat явно: диалоги mediarouter наследуют AppCompatDialog, без него не компилируются.
    implementation(libs.play.services.cast.framework)
    implementation(libs.androidx.appcompat)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.zxing.core)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
