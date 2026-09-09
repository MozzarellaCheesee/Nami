package dev.nami.player

import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Слушать вместе" втроём и больше держится на том, что хост обслуживает нескольких гостей
 * одновременно и не хранит состояния "текущего гостя". Проверить это тремя телефонами получается
 * не всегда, а сломать одним неосторожным полем в сервере - легко, поэтому три одновременных
 * клиента гоняются здесь. */
@RunWith(RobolectricTestRunner::class)
class LocalHttpServerFanOutTest {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `three clients polling at the same time all get an answer`() {
        val port = freePort()
        val server = LocalHttpServer(
            port = port,
            trackByIdBlocking = { null },
            deviceName = "HOST",
            nowPlayingJsonBlocking = { JSONObject().put("trackId", "t1") },
        )
        server.start()
        try {
            val start = CountDownLatch(1)
            val done = CountDownLatch(3)
            val answers = java.util.Collections.synchronizedList(mutableListOf<String>())
            repeat(3) {
                Thread {
                    start.await()
                    answers += URL("http://127.0.0.1:$port/nowplaying").readText()
                    done.countDown()
                }.start()
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS), "не все клиенты дождались ответа")
            assertEquals(3, answers.size)
            answers.forEach { assertEquals("t1", JSONObject(it).getString("trackId")) }
            // Все три пришли с одного адреса - это один слушатель, не три.
            assertEquals(1, server.guestCount())
        } finally {
            server.stop()
        }
    }
}
