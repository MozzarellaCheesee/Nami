package dev.nami.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nami.core.database.NamiDatabase
import dev.nami.core.database.entity.TrackEntity
import dev.nami.domain.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SyncRepositoryImplTest {
    private lateinit var db: NamiDatabase
    private lateinit var context: Context
    private lateinit var repo: SyncRepositoryImpl

    private fun fakeTrack(id: String) = TrackEntity(
        id = id, title = "Title $id", artistId = null, albumId = null, trackNo = 1, discNo = 1,
        durationMs = 180000, path = "/music/$id.mp3", format = "mp3", sizeBytes = 1000,
        dateAdded = 1000, lastPlayed = null, playCount = 0, genre = null,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NamiDatabase::class.java)
            .allowMainThreadQueries().build()

        val settings = AppSettingsRepository(context)

        repo = SyncRepositoryImpl(
            context = context,
            settingsRepository = settings,
            playlistDao = db.playlistDao(),
            playlistTrackDao = db.playlistTrackDao(),
            trackDao = db.trackDao(),
            momentDao = db.momentDao(),
            loopDao = db.loopDao(),
            tagDao = db.tagDao(),
            playHistoryDao = db.playHistoryDao(),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `pull playlist creates, renames and soft deletes`() = runTest {
        // Create
        repo.applyChange(
            entity = "playlist",
            id = "p1",
            field = "name",
            change = JSONObject().put("value", "My Playlist"),
            updatedAt = 100L,
        )
        var p = db.playlistDao().findById("p1")
        assertNotNull(p)
        assertEquals("My Playlist", p.name)

        // Rename
        repo.applyChange(
            entity = "playlist",
            id = "p1",
            field = "name",
            change = JSONObject().put("value", "Renamed Playlist"),
            updatedAt = 200L,
        )
        p = db.playlistDao().findById("p1")
        assertNotNull(p)
        assertEquals("Renamed Playlist", p.name)

        // Soft delete
        repo.applyChange(
            entity = "playlist",
            id = "p1",
            field = "__deleted",
            change = JSONObject().put("value", true),
            updatedAt = 300L,
        )
        p = db.playlistDao().findById("p1")
        assertNotNull(p)
        assertEquals(300_000L, p.deletedAt)
    }

    @Test
    fun `pull playlist_track inserts and removes`() = runTest {
        db.trackDao().insertAll(listOf(fakeTrack("t1")))

        // Insert playlist_track (auto-creates playlist if missing)
        repo.applyChange(
            entity = "playlist_track",
            id = "p1:t1",
            field = "position",
            change = JSONObject().put("value", 0),
            updatedAt = 100L,
        )

        val tracks = db.playlistTrackDao().tracksInPlaylist("p1")
        assertEquals(1, tracks.size)
        assertEquals("t1", tracks[0].id)

        // Remove
        repo.applyChange(
            entity = "playlist_track",
            id = "p1:t1",
            field = "__deleted",
            change = JSONObject().put("value", true),
            updatedAt = 200L,
        )
        val tracksAfterRemove = db.playlistTrackDao().tracksInPlaylist("p1")
        assertEquals(0, tracksAfterRemove.size)
    }

    @Test
    fun `pull rating updates track rating`() = runTest {
        db.trackDao().insertAll(listOf(fakeTrack("t1")))

        repo.applyChange(
            entity = "rating",
            id = "t1",
            field = "stars",
            change = JSONObject().put("value", 5),
            updatedAt = 100L,
        )
        assertEquals(5, db.trackDao().findById("t1")?.rating)

        // Clear rating
        repo.applyChange(
            entity = "rating",
            id = "t1",
            field = "stars",
            change = JSONObject().put("value", JSONObject.NULL),
            updatedAt = 200L,
        )
        assertNull(db.trackDao().findById("t1")?.rating)
    }

    @Test
    fun `pull track_note updates and clears note`() = runTest {
        db.trackDao().insertAll(listOf(fakeTrack("t1")))

        repo.applyChange(
            entity = "track_note",
            id = "t1",
            field = "note",
            change = JSONObject().put("value", "A great solo at 1:30"),
            updatedAt = 100L,
        )
        assertEquals("A great solo at 1:30", db.trackDao().findById("t1")?.note)

        repo.applyChange(
            entity = "track_note",
            id = "t1",
            field = "__deleted",
            change = JSONObject().put("value", true),
            updatedAt = 200L,
        )
        assertNull(db.trackDao().findById("t1")?.note)
    }

    @Test
    fun `pull moment inserts and deletes`() = runTest {
        val momentData = JSONObject().apply {
            put("track_id", "t1")
            put("position_ms", 45000L)
            put("label", "Drop")
            put("color", 0xFF0000)
            put("created_at", 1000L)
            put("is_chapter", false)
        }

        repo.applyChange(
            entity = "moment",
            id = "42",
            field = "data",
            change = JSONObject().put("value", momentData),
            updatedAt = 100L,
        )

        val moments = db.momentDao().allSnapshot()
        assertEquals(1, moments.size)
        assertEquals(42L, moments[0].id)
        assertEquals("Drop", moments[0].label)

        // Delete
        repo.applyChange(
            entity = "moment",
            id = "42",
            field = "__deleted",
            change = JSONObject().put("value", true),
            updatedAt = 200L,
        )
        assertEquals(0, db.momentDao().allSnapshot().size)
    }

    @Test
    fun `pull loop inserts and deletes`() = runTest {
        val loopData = JSONObject().apply {
            put("track_id", "t1")
            put("start_ms", 10000L)
            put("end_ms", 20000L)
            put("name", "Chorus Loop")
            put("created_at", 1000L)
        }

        repo.applyChange(
            entity = "loop",
            id = "101",
            field = "data",
            change = JSONObject().put("value", loopData),
            updatedAt = 100L,
        )

        val loops = db.loopDao().allSnapshot()
        assertEquals(1, loops.size)
        assertEquals(101L, loops[0].id)
        assertEquals("Chorus Loop", loops[0].name)

        // Delete
        repo.applyChange(
            entity = "loop",
            id = "101",
            field = "__deleted",
            change = JSONObject().put("value", true),
            updatedAt = 200L,
        )
        assertEquals(0, db.loopDao().allSnapshot().size)
    }

    @Test
    fun `pull tag and tag_assignment assign and unassign`() = runTest {
        db.trackDao().insertAll(listOf(fakeTrack("t1")))

        val tagData = JSONObject().apply {
            put("name", "Instrumental")
            put("color_argb", 0x123456)
        }
        repo.applyChange(
            entity = "tag",
            id = "tag1",
            field = "data",
            change = JSONObject().put("value", tagData),
            updatedAt = 100L,
        )
        assertEquals(1, db.tagDao().allRaw().size)
        assertEquals("Instrumental", db.tagDao().allRaw()[0].name)

        // Assign tag
        repo.applyChange(
            entity = "tag_assignment",
            id = "t1:tag1",
            field = "assigned",
            change = JSONObject().put("value", true),
            updatedAt = 150L,
        )
        assertEquals(1, db.tagDao().allAssignmentsRaw().size)

        // Unassign tag
        repo.applyChange(
            entity = "tag_assignment",
            id = "t1:tag1",
            field = "__deleted",
            change = JSONObject().put("value", true),
            updatedAt = 200L,
        )
        assertEquals(0, db.tagDao().allAssignmentsRaw().size)
    }

    @Test
    fun `pull listening_history records listen event`() = runTest {
        val historyData = JSONObject().apply {
            put("track_id", "t1")
            put("played_at", 123456789L)
            put("duration_ms", 180000L)
        }
        repo.applyChange(
            entity = "listening_history",
            id = "t1:123456789",
            field = "history",
            change = JSONObject().put("value", historyData),
            updatedAt = 100L,
        )

        val history = db.playHistoryDao().since(0L)
        assertEquals(1, history.size)
        assertEquals("t1", history[0].trackId)
        assertEquals(123456789L, history[0].playedAt)
    }
}
