package dev.nami.data

import android.content.Context

internal data class SyncTombstone(val entity: String, val id: String, val updatedAt: Long, val raw: String)

/** Маленький durable outbox только для удалений, которых уже нет в Room-снимке. */
internal object SyncTombstones {
    private const val PREFS = "nami_sync_tombstones"
    private const val KEY = "pending"
    private const val SEP = '\u001f'

    @Synchronized
    fun add(context: Context, entity: String, id: String, updatedAt: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = prefs.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        entries += listOf(entity, id, updatedAt.toString()).joinToString(SEP.toString())
        prefs.edit().putStringSet(KEY, entries).commit()
    }

    fun read(context: Context): List<SyncTombstone> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()).orEmpty().mapNotNull { raw ->
                val parts = raw.split(SEP, limit = 3)
                val at = parts.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
                SyncTombstone(parts[0], parts[1], at, raw)
            }

    @Synchronized
    fun cancel(context: Context, entity: String, id: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = prefs.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        entries.removeAll { raw ->
            val parts = raw.split(SEP, limit = 3)
            parts.getOrNull(0) == entity && parts.getOrNull(1) == id
        }
        prefs.edit().putStringSet(KEY, entries).commit()
    }

    @Synchronized
    fun remove(context: Context, sent: Collection<SyncTombstone>) {
        if (sent.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = prefs.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        entries.removeAll(sent.map { it.raw }.toSet())
        prefs.edit().putStringSet(KEY, entries).commit()
    }
}
