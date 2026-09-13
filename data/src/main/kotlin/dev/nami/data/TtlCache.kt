package dev.nami.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Кеш ответов сервера в памяти: срок жизни, потолок по числу записей и склейка одновременных
 * запросов за одним и тем же ключом.
 *
 * Раньше вместо него стояли обычные `ConcurrentHashMap`. У них три беды сразу:
 * - записи не вытеснялись, и карта росла на весь размер библиотеки за сессию;
 * - записи не протухали, и правка трека на сервере не доезжала до телефона до перезапуска;
 * - два одновременных запроса про один и тот же трек шли в сеть оба, а результат второго
 *   затирал результат первого.
 *
 * Все три решает эта обёртка. Вытеснение - по давности обращения (LRU): популярный трек
 * переживает пролистывание чужого альбома, а не вылетает первым просто потому, что положен
 * раньше. Склейка запросов держит один `CompletableDeferred` на ключ: пришедшие вторыми ждут
 * тот же результат, а не делают свой запрос.
 *
 * Промахи (`null`) не кешируются: вызывающий сам решает, надо ли запоминать отрицательный ответ,
 * и обычно кладёт для этого собственное значение-заглушку. Молча кешировать `null` опаснее -
 * временная ошибка сети превратилась бы в «трека нет» на весь срок жизни записи.
 */
class TtlCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val ttlMs: Long,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private class Entry<V>(val value: V, val storedAt: Long)

    private val lock = Mutex()

    // accessOrder = true: порядок обхода - от давно не спрашиваемых к свежим, что и нужно LRU.
    private val entries = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>): Boolean =
            size > maxEntries
    }

    private val inFlight = HashMap<K, CompletableDeferred<V?>>()

    /** Значение из кеша либо результат [load]. Одновременные вызовы с одним ключом сделают
     * ровно один [load] и разделят его результат. */
    suspend fun get(key: K, load: suspend () -> V?): V? {
        var waiter: CompletableDeferred<V?>? = null
        var owner = false
        lock.withLock {
            val cached = entries[key]
            if (cached != null) {
                if (now() - cached.storedAt < ttlMs) return cached.value
                entries.remove(key)
            }
            val pending = inFlight[key]
            if (pending != null) {
                waiter = pending
            } else {
                val fresh = CompletableDeferred<V?>()
                inFlight[key] = fresh
                waiter = fresh
                owner = true
            }
        }
        val deferred = waiter!!
        if (!owner) return deferred.await()

        return try {
            val value = load()
            lock.withLock {
                if (value != null) entries[key] = Entry(value, now())
                inFlight.remove(key)
            }
            deferred.complete(value)
            value
        } catch (t: Throwable) {
            // Отмена сюда тоже попадает, и это важно: без снятия записи об идущем запросе
            // остальные ждали бы результата, которого уже никто не посчитает.
            lock.withLock { inFlight.remove(key) }
            deferred.completeExceptionally(t)
            throw t
        }
    }

    /** Кладёт готовое значение - когда оно получено попутно, без отдельного запроса. */
    suspend fun put(key: K, value: V) {
        lock.withLock { entries[key] = Entry(value, now()) }
    }

    /** Полная очистка. Нужна при смене сервера или учётной записи: чужие данные показывать
     * нельзя, а ключи у разных серверов совпадают. */
    suspend fun clear() {
        lock.withLock {
            entries.clear()
            inFlight.clear()
        }
    }

    /** Только для тестов и отладки. */
    internal suspend fun size(): Int = lock.withLock { entries.size }
}
