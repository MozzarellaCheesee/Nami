package dev.nami.data.vk

/**
 * Ограничитель частоты запросов к VK API. VK ограничивает частоту до 3 запросов в секунду
 * для пользовательских токенов. Держим задержку не менее 350 мс между сетевыми вызовами.
 */
object VkRateLimiter {
    private const val MIN_INTERVAL_MS = 350L
    private var lastRequestTime = 0L
    private val lock = Any()

    fun throttle() {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRequestTime
            if (elapsed < MIN_INTERVAL_MS) {
                try {
                    Thread.sleep(MIN_INTERVAL_MS - elapsed)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            lastRequestTime = System.currentTimeMillis()
        }
    }
}
