package dev.nami.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateManagerTest {
    @Test
    fun `версия Android берётся из имени APK а не серверного тега`() {
        assertEquals("0.1.2-beta.19", androidVersionFromApkName("Nami-Android-0.1.2-beta.19.apk"))
    }

    @Test
    fun `посторонний APK без версии не выдаёт придуманную версию`() {
        assertNull(androidVersionFromApkName("Nami-Android.apk"))
    }

    @Test
    fun `та же beta версия не считается обновлением`() {
        assertEquals(false, isVersionNewer("0.1.2-beta.19", "0.1.2-beta.19"))
    }

    @Test
    fun `следующая beta версия считается обновлением`() {
        assertEquals(true, isVersionNewer("0.1.2-beta.20", "0.1.2-beta.19"))
    }
}
