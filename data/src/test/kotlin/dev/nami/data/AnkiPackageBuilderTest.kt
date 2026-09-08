package dev.nami.data

import android.database.sqlite.SQLiteDatabase
import dev.nami.domain.VocabularyWord
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Битый .apkg выглядит как обычный файл и ломается только в момент импорта в Anki, поэтому
 * тест разбирает архив обратно: те ли записи внутри, читается ли база, ссылаются ли карточки на
 * существующие заметки и на объявленную колоду. */
@RunWith(RobolectricTestRunner::class)
class AnkiPackageBuilderTest {

    private fun words() = listOf(
        VocabularyWord(1, "猫", "ねこ", "кошка", "猫が好き", "Track A", 0),
        VocabularyWord(2, "犬", "いぬ", "собака", "犬も好き", "Track B", 0),
    )

    private fun buildInTemp(list: List<VocabularyWord>): Pair<File, File> {
        val dir = File(System.getProperty("java.io.tmpdir"), "apkg_${System.nanoTime()}").apply { mkdirs() }
        val out = File(dir, "deck.apkg")
        AnkiPackageBuilder.build(list, dir, out)
        return dir to out
    }

    @Test
    fun `архив содержит collection anki2 и манифест media`() {
        val (dir, out) = buildInTemp(words())
        try {
            ZipFile(out).use { zip ->
                assertNotNull(zip.getEntry("collection.anki2"), "нет collection.anki2")
                val media = zip.getEntry("media")
                assertNotNull(media, "нет манифеста media")
                assertEquals("{}", zip.getInputStream(media).readBytes().decodeToString())
            }
            // Временная база не должна пережить сборку.
            assertTrue(!File(dir, "collection.anki2").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `база читается, карточки ссылаются на заметки и объявленную колоду`() {
        val (dir, out) = buildInTemp(words())
        try {
            val extracted = File(dir, "extracted.anki2")
            ZipFile(out).use { zip ->
                zip.getInputStream(zip.getEntry("collection.anki2")).use { input ->
                    extracted.outputStream().use { input.copyTo(it) }
                }
            }
            SQLiteDatabase.openDatabase(extracted.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                fun scalar(sql: String): String? =
                    db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getString(0) else null }

                assertEquals("2", scalar("SELECT COUNT(*) FROM notes"))
                assertEquals("2", scalar("SELECT COUNT(*) FROM cards"))
                assertEquals("11", scalar("SELECT ver FROM col"))
                // Ни одной карточки-сироты.
                assertEquals("0", scalar("SELECT COUNT(*) FROM cards WHERE nid NOT IN (SELECT id FROM notes)"))

                // did карточек обязан быть среди колод в col.decks, иначе Anki импортирует
                // заметки без карточек и колода выглядит пустой.
                val decks = JSONObject(scalar("SELECT decks FROM col")!!)
                val cardDeck = scalar("SELECT DISTINCT did FROM cards")!!
                assertTrue(decks.has(cardDeck), "колода $cardDeck не объявлена в col.decks")

                // Тип заметки должен объявлять ровно те пять полей, что записаны в flds.
                val models = JSONObject(scalar("SELECT models FROM col")!!)
                val model = models.getJSONObject(models.keys().next())
                assertEquals(5, model.getJSONArray("flds").length())
                val flds = scalar("SELECT flds FROM notes ORDER BY id LIMIT 1")!!
                assertEquals(5, flds.split("").size)
                assertEquals("猫", flds.split("")[0])
                assertEquals("猫", scalar("SELECT sfld FROM notes ORDER BY id LIMIT 1"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `пустой словарик даёт валидный архив без заметок`() {
        val (dir, out) = buildInTemp(emptyList())
        try {
            ZipFile(out).use { zip -> assertNotNull(zip.getEntry("collection.anki2")) }
        } finally {
            dir.deleteRecursively()
        }
    }
}
