plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.paging.common)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
