package dev.nami.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.model.DictionaryEntry
import dev.nami.domain.DictionaryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Full JMdict (218k+ entries, every one - not a common-words-only cut) bundled as a
 * ~46MB read-only SQLite asset (built from scriptin/jmdict-simplified's jmdict-eng release).
 * SQLiteDatabase can't open a file straight out of the APK's assets, so the first lookup copies
 * it once into app-private storage; every lookup after that just opens the copy. */
@Singleton
class DictionaryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : DictionaryRepository {

    private val mutex = Mutex()
    private var db: SQLiteDatabase? = null

    private suspend fun database(): SQLiteDatabase = mutex.withLock {
        db?.let { return it }
        val dest = File(context.getDatabasePath("jmdict.db").path)
        if (!dest.exists()) {
            dest.parentFile?.mkdirs()
            context.assets.open("jmdict.db").use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        }
        SQLiteDatabase.openDatabase(dest.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
    }

    override suspend fun lookup(word: String): List<DictionaryEntry> = withContext(Dispatchers.IO) {
        if (word.isBlank()) return@withContext emptyList()
        val cursor = database().rawQuery(
            """
            SELECT e.kanji, e.kana, e.pos, e.glosses
            FROM headwords h JOIN entries e ON h.entry_id = e.id
            WHERE h.headword = ?
            ORDER BY h.is_common DESC
            LIMIT 10
            """.trimIndent(),
            arrayOf(word),
        )
        cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        DictionaryEntry(
                            kanji = it.getString(0),
                            kana = it.getString(1),
                            partsOfSpeech = it.getString(2)?.split("|") ?: emptyList(),
                            glosses = it.getString(3)?.split(" / ") ?: emptyList(),
                        ),
                    )
                }
            }
        }
    }
}
