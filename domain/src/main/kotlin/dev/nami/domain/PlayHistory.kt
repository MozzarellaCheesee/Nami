package dev.nami.domain

/** B1 "Статистика" (План.md §23.22) -- one bucket day for the contribution grid: how many
 * minutes of actually-listened-to music landed on that calendar day (device-local timezone). */
data class DayActivity(val epochDay: Long, val minutesPlayed: Int)
