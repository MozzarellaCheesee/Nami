package dev.nami.domain

/** Хвост группы C "CUE-поддержка" - один физический файл (образ альбома), .cue описывает где
 * начинается каждый трек. [startMs] от INDEX 01 (пропускает pregap INDEX 00, если есть) -
 * [endMs] не читается из .cue (там его и нет), вызывающий код сам ставит его как startMs
 * следующего трека, для последнего трека - null (до конца файла). */
data class CueTrackInfo(
    val trackNo: Int,
    val title: String,
    val performer: String?,
    val startMs: Long,
)

/** Разбирает только то, что реально нужно для нарезки на треки - TRACK/TITLE/PERFORMER/INDEX.
 * REM-комментарии, CATALOG, множественные FILE-блоки (мульти-CD в одном .cue) не поддержаны -
 * второй FILE просто обрывает разбор на первом, честно возвращая уже собранные треки первого. */
object CueSheet {
    private val indexRegex = Regex("""INDEX\s+(\d{2})\s+(\d{1,3}):(\d{2}):(\d{2})""", RegexOption.IGNORE_CASE)
    private val trackRegex = Regex("""TRACK\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val quotedRegex = Regex(""""([^"]*)"""")

    fun parse(text: String): List<CueTrackInfo> {
        var albumPerformer: String? = null
        var sawFile = false
        val result = mutableListOf<CueTrackInfo>()
        var currentTrackNo: Int? = null
        var currentTitle: String? = null
        var currentPerformer: String? = null
        var currentStartMs: Long? = null

        fun flush() {
            val trackNo = currentTrackNo ?: return
            val startMs = currentStartMs ?: return
            result.add(CueTrackInfo(trackNo, currentTitle ?: "Track $trackNo", currentPerformer ?: albumPerformer, startMs))
        }

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith("FILE", ignoreCase = true) -> {
                    if (sawFile) break // second FILE block - see class doc, stop here
                    sawFile = true
                }
                line.startsWith("TRACK", ignoreCase = true) -> {
                    flush()
                    currentTrackNo = trackRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
                    currentTitle = null
                    currentPerformer = null
                    currentStartMs = null
                }
                line.startsWith("TITLE", ignoreCase = true) -> {
                    // Album title (before any TRACK line) is unused - only per-track TITLE matters.
                    if (currentTrackNo != null) currentTitle = quotedRegex.find(line)?.groupValues?.get(1)
                }
                line.startsWith("PERFORMER", ignoreCase = true) -> {
                    val performer = quotedRegex.find(line)?.groupValues?.get(1)
                    if (currentTrackNo == null) albumPerformer = performer else currentPerformer = performer
                }
                line.startsWith("INDEX", ignoreCase = true) -> {
                    val match = indexRegex.find(line) ?: continue
                    val (number, mm, ss, ff) = match.destructured
                    // INDEX 00 is the pregap - only INDEX 01 (track's real start) matters here,
                    // and only the first INDEX 01 seen per track (some sheets repeat it).
                    if (number == "01" && currentStartMs == null) {
                        // CDDA: 75 frames/sec.
                        currentStartMs = (mm.toLong() * 60 + ss.toLong()) * 1000 + (ff.toLong() * 1000 / 75)
                    }
                }
            }
        }
        flush()
        return result.sortedBy { it.trackNo }
    }
}
