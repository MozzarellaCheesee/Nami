package dev.nami.domain

/** П.md §20 "умные плейлисты" - fields a rule can filter on. Deliberately limited to what's
 * actually queryable from Track today: no BPM/key rule (would need every track scanned first,
 * see BpmKeyAnalyzer's lazy-scan-on-play caching), no rating (no Track.rating field exists in
 * this codebase). HAS_LYRICS checks for a sidecar .lrc file at evaluation time (not a DB column --
 * lyrics live as files, not rows), so it's the one field that costs real I/O per track. */
enum class SmartField { GENRE, FORMAT, PLAY_COUNT, ADDED_DAYS_AGO, LAST_PLAYED_DAYS_AGO, DURATION_SEC, HAS_LYRICS }

enum class SmartOperator { EQUALS, NOT_EQUALS, GREATER_THAN, LESS_THAN }

enum class SmartSortField { DATE_ADDED, TITLE, PLAY_COUNT, DURATION }

data class SmartRule(val field: SmartField, val operator: SmartOperator, val value: String)

data class SmartQuery(
    val rules: List<SmartRule>,
    val sortBy: SmartSortField = SmartSortField.DATE_ADDED,
    val sortDescending: Boolean = true,
    val limit: Int? = null,
)
