package dev.nami.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TtlCacheTest {

    @Test
    fun `value is served from the cache until it expires`() = runTest {
        var now = 0L
        val loads = AtomicInteger()
        val cache = TtlCache<String, String>(maxEntries = 10, ttlMs = 100, now = { now })

        assertEquals("v1", cache.get("k") { loads.incrementAndGet(); "v1" })
        now = 50
        assertEquals("v1", cache.get("k") { loads.incrementAndGet(); "v2" })
        assertEquals(1, loads.get())

        now = 150
        assertEquals("v2", cache.get("k") { loads.incrementAndGet(); "v2" })
        assertEquals(2, loads.get())
    }

    @Test
    fun `the cache does not grow past its limit`() = runTest {
        // Именно от этого страдали прежние ConcurrentHashMap: за сессию туда попадала вся
        // библиотека и ничего не вытеснялось.
        val cache = TtlCache<Int, Int>(maxEntries = 3, ttlMs = 10_000)
        repeat(10) { i -> cache.get(i) { i } }
        assertEquals(3, cache.size())
    }

    @Test
    fun `least recently used entry is evicted, not the oldest`() = runTest {
        val cache = TtlCache<String, String>(maxEntries = 2, ttlMs = 10_000)
        cache.get("a") { "a" }
        cache.get("b") { "b" }
        // Трогаем "a" - теперь самый давний по обращению это "b".
        cache.get("a") { error("должно прийти из кеша") }
        cache.get("c") { "c" }

        assertEquals("a", cache.get("a") { error("должно прийти из кеша") })
        assertEquals("c", cache.get("c") { error("должно прийти из кеша") })
    }

    @Test
    fun `concurrent requests for one key share a single load`() = runTest {
        val loads = AtomicInteger()
        val cache = TtlCache<String, String>(maxEntries = 10, ttlMs = 10_000)

        val results = coroutineScope {
            List(5) {
                async {
                    cache.get("k") {
                        loads.incrementAndGet()
                        delay(50)
                        "v"
                    }
                }
            }.map { it.await() }
        }

        assertEquals(List(5) { "v" }, results)
        assertEquals(1, loads.get())
    }

    @Test
    fun `a miss is not cached`() = runTest {
        // Временная ошибка сети не должна превращаться в "этого нет" на весь срок жизни записи.
        val loads = AtomicInteger()
        val cache = TtlCache<String, String>(maxEntries = 10, ttlMs = 10_000)

        assertNull(cache.get("k") { loads.incrementAndGet(); null })
        assertEquals("v", cache.get("k") { loads.incrementAndGet(); "v" })
        assertEquals(2, loads.get())
    }
}
