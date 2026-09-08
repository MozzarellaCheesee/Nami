package dev.nami.data

import android.database.sqlite.SQLiteDatabase
import dev.nami.domain.VocabularyWord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** П.md - экспорт словарика в .apkg (колода Anki) вдобавок к CSV.
 *
 * .apkg - это zip с SQLite-базой `collection.anki2` внутри плюс манифест `media`. Готовой
 * JVM-библиотеки для генерации нет (genanki - питон), поэтому база собирается вручную: схема
 * фиксированная и небольшая, а тянуть ради неё зависимость или писать свой SQLite-движок
 * несоразмерно. Room тут не подходит принципиально - схему диктует Anki, а не мы, и Room
 * навязал бы свою таблицу версий в чужой файл.
 *
 * Схема - "11", legacy-формат: его до сих пор читают все версии Anki и AnkiDroid, тогда как
 * новые (18/collection.anki21b) требуют zstd и protobuf. Для экспорта нужен максимально
 * совместимый файл, а не самый свежий.
 *
 * Карточки создаются "новыми" (type/queue = 0): экспорт отдаёт материал для изучения, а не
 * историю повторений, которой у нас и нет. */
object AnkiPackageBuilder {

    private const val DECK_NAME = "Nami словарик"
    private val FIELD_NAMES = listOf("Слово", "Чтение", "Значение", "Строка", "Трек")

    /** Собирает .apkg из [words] в [outputFile]. [workDir] нужен под временный collection.anki2:
     * SQLiteDatabase умеет открывать только настоящий файл, не поток. */
    fun build(words: List<VocabularyWord>, workDir: File, outputFile: File) {
        val collection = File(workDir, "collection.anki2")
        collection.delete()
        try {
            SQLiteDatabase.openOrCreateDatabase(collection, null).use { db ->
                createSchema(db)
                fillCollection(db, words)
            }
            ZipOutputStream(outputFile.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("collection.anki2"))
                collection.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                // Манифест медиа обязателен даже когда медиа нет - без него импорт падает.
                zip.putNextEntry(ZipEntry("media"))
                zip.write("{}".toByteArray())
                zip.closeEntry()
            }
        } finally {
            collection.delete()
        }
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE col (
                id integer primary key, crt integer not null, mod integer not null,
                scm integer not null, ver integer not null, dty integer not null,
                usn integer not null, ls integer not null, conf text not null,
                models text not null, decks text not null, dconf text not null, tags text not null
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE notes (
                id integer primary key, guid text not null, mid integer not null,
                mod integer not null, usn integer not null, tags text not null,
                flds text not null, sfld integer not null, csum integer not null,
                flags integer not null, data text not null
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE cards (
                id integer primary key, nid integer not null, did integer not null,
                ord integer not null, mod integer not null, usn integer not null,
                type integer not null, queue integer not null, due integer not null,
                ivl integer not null, factor integer not null, reps integer not null,
                lapses integer not null, left integer not null, odue integer not null,
                odid integer not null, flags integer not null, data text not null
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE TABLE graves (usn integer not null, oid integer not null, type integer not null)")
        db.execSQL(
            """
            CREATE TABLE revlog (
                id integer primary key, cid integer not null, usn integer not null,
                ease integer not null, ivl integer not null, lastIvl integer not null,
                factor integer not null, time integer not null, type integer not null
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX ix_notes_usn ON notes (usn)")
        db.execSQL("CREATE INDEX ix_cards_usn ON cards (usn)")
        db.execSQL("CREATE INDEX ix_cards_nid ON cards (nid)")
        db.execSQL("CREATE INDEX ix_cards_sched ON cards (did, queue, due)")
        db.execSQL("CREATE INDEX ix_notes_csum ON notes (csum)")
        db.execSQL("CREATE INDEX ix_revlog_cid ON revlog (cid)")
        db.execSQL("CREATE INDEX ix_revlog_usn ON revlog (usn)")
    }

    private fun fillCollection(db: SQLiteDatabase, words: List<VocabularyWord>) {
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        // Anki опознаёт колоду и тип заметки по числовому id - берём текущее время, чтобы
        // повторный экспорт не сливался со старым и не перезаписывал чужую колоду.
        val modelId = nowMs
        val deckId = nowMs + 1

        db.execSQL(
            "INSERT INTO col VALUES (1, ?, ?, ?, 11, 0, 0, 0, ?, ?, ?, ?, '{}')",
            arrayOf<Any>(
                nowSec, nowMs, nowMs,
                confJson(modelId), modelsJson(modelId, deckId, nowSec),
                decksJson(deckId, nowSec), dconfJson(),
            ),
        )

        words.forEachIndexed { index, word ->
            // id заметки/карточки в Anki - это метка времени в мс и одновременно первичный ключ.
            // Слов может быть больше, чем миллисекунд, поэтому просто идём с шагом в единицу.
            val noteId = nowMs + index * 2
            val cardId = noteId + 1
            val fields = listOf(word.word, word.reading, word.meaning, word.contextLine, word.trackTitle)
            // \u001f - разделитель полей внутри одной заметки, так задано форматом.
            val flds = fields.joinToString("\u001f")
            db.execSQL(
                "INSERT INTO notes VALUES (?, ?, ?, ?, -1, '', ?, ?, ?, 0, '')",
                arrayOf<Any>(noteId, guidFor(noteId), modelId, nowSec, flds, fields[0], checksum(fields[0])),
            )
            db.execSQL(
                "INSERT INTO cards VALUES (?, ?, ?, 0, ?, -1, 0, 0, ?, 0, 0, 0, 0, 0, 0, 0, 0, '')",
                arrayOf<Any>(cardId, noteId, deckId, nowSec, index + 1),
            )
        }
    }

    /** Anki хранит контрольную сумму первого поля как первые 8 hex-цифр его SHA1 - по ней
     * ищутся дубликаты при импорте. Считается именно так, иначе Anki посчитает все заметки
     * разными и накопит дубли при повторном экспорте. */
    private fun checksum(sortField: String): Long {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(sortField.toByteArray())
        return sha1.take(4).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
    }

    private fun guidFor(noteId: Long): String =
        java.util.UUID.nameUUIDFromBytes(noteId.toString().toByteArray()).toString().take(10)

    private fun confJson(modelId: Long): String = JSONObject().apply {
        put("nextPos", 1)
        put("estTimes", true)
        put("activeDecks", JSONArray(listOf(1)))
        put("sortType", "noteFld")
        put("timeLim", 0)
        put("sortBackwards", false)
        put("addToCur", true)
        put("curDeck", 1)
        put("newBury", true)
        put("newSpread", 0)
        put("dueCounts", true)
        put("curModel", modelId.toString())
        put("collapseTime", 1200)
    }.toString()

    private fun modelsJson(modelId: Long, deckId: Long, nowSec: Long): String {
        val fields = JSONArray()
        FIELD_NAMES.forEachIndexed { ord, name ->
            fields.put(
                JSONObject().apply {
                    put("name", name)
                    put("ord", ord)
                    put("sticky", false)
                    put("rtl", false)
                    put("font", "Arial")
                    put("size", 20)
                    put("description", "")
                },
            )
        }
        val template = JSONObject().apply {
            put("name", "Слово - значение")
            put("ord", 0)
            put("qfmt", "{{Слово}}")
            put(
                "afmt",
                "{{FrontSide}}\n\n<hr id=answer>\n\n{{Чтение}}<br>{{Значение}}" +
                    "<br><br><i>{{Строка}}</i><br><small>{{Трек}}</small>",
            )
            put("bqfmt", "")
            put("bafmt", "")
            put("did", JSONObject.NULL)
            put("bfont", "")
            put("bsize", 0)
        }
        val model = JSONObject().apply {
            put("id", modelId)
            put("name", DECK_NAME)
            put("type", 0)
            put("mod", nowSec)
            put("usn", -1)
            put("sortf", 0)
            put("did", deckId)
            put("tmpls", JSONArray().put(template))
            put("flds", fields)
            put("css", ".card { font-family: sans-serif; font-size: 20px; text-align: center; }")
            put("latexPre", "\\documentclass[12pt]{article}\n\\begin{document}\n")
            put("latexPost", "\\end{document}")
            put("latexsvg", false)
            // "первое поле непустое" - единственное условие генерации карточки.
            put("req", JSONArray().put(JSONArray().put(0).put("any").put(JSONArray().put(0))))
            put("vers", JSONArray())
        }
        return JSONObject().put(modelId.toString(), model).toString()
    }

    private fun decksJson(deckId: Long, nowSec: Long): String {
        fun deck(id: Long, name: String) = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("mod", nowSec)
            put("usn", -1)
            put("lrnToday", JSONArray().put(0).put(0))
            put("revToday", JSONArray().put(0).put(0))
            put("newToday", JSONArray().put(0).put(0))
            put("timeToday", JSONArray().put(0).put(0))
            put("collapsed", false)
            put("browserCollapsed", false)
            put("desc", "")
            put("dyn", 0)
            put("conf", 1)
            put("extendNew", 0)
            put("extendRev", 0)
        }
        // Колода 1 ("Default") обязана присутствовать - на неё ссылается conf.activeDecks.
        return JSONObject().apply {
            put("1", deck(1, "Default"))
            put(deckId.toString(), deck(deckId, DECK_NAME))
        }.toString()
    }

    private fun dconfJson(): String {
        val conf = JSONObject().apply {
            put("id", 1)
            put("mod", 0)
            put("name", "Default")
            put("usn", 0)
            put("maxTaken", 60)
            put("autoplay", true)
            put("timer", 0)
            put("replayq", true)
            put(
                "new",
                JSONObject().apply {
                    put("bury", false)
                    put("delays", JSONArray().put(1.0).put(10.0))
                    put("initialFactor", 2500)
                    put("ints", JSONArray().put(1).put(4).put(0))
                    put("order", 1)
                    put("perDay", 20)
                },
            )
            put(
                "rev",
                JSONObject().apply {
                    put("bury", false)
                    put("ease4", 1.3)
                    put("ivlFct", 1.0)
                    put("maxIvl", 36500)
                    put("perDay", 200)
                    put("hardFactor", 1.2)
                },
            )
            put(
                "lapse",
                JSONObject().apply {
                    put("delays", JSONArray().put(10.0))
                    put("leechAction", 1)
                    put("leechFails", 8)
                    put("minInt", 1)
                    put("mult", 0.0)
                },
            )
            put("dyn", false)
        }
        return JSONObject().put("1", conf).toString()
    }
}
