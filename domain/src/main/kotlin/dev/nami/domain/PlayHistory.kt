package dev.nami.domain

import dev.nami.core.model.TrackId

/** B1 "Статистика" (План.md §23.22) -- one bucket day for the contribution grid: how many
 * minutes of actually-listened-to music landed on that calendar day (device-local timezone). */
data class DayActivity(val epochDay: Long, val minutesPlayed: Int)

/** Header numbers for the Статистика screen -- hours/tracks/artists actually listened to in the
 * window, not the library's total size (a huge unplayed library shouldn't inflate these). */
data class ListeningSummary(val totalMinutes: Int, val distinctTracks: Int, val distinctArtists: Int)

data class TopTrackStat(val trackId: TrackId, val title: String, val artistName: String?, val playCount: Int)
