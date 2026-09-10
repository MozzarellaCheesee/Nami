//! Анализ аудио: ReplayGain/R128, BPM, ключ, waveform, chromaprint.
//!
//! ponytail: ffmpeg для ReplayGain/R128 (ebur128 filter), chromaprint для fingerprint.
//! BPM и key detection через librosa-подобную логику отложены — добавить когда нужно.

use std::path::Path;
use std::process::Command;

use rusqlite::Connection;
use serde::Serialize;

use crate::Res;

/// Результат анализа трека
#[derive(Debug, Clone)]
pub struct Analysis {
    /// ReplayGain track gain (dB)
    pub replaygain_track_gain: Option<f32>,
    /// ReplayGain track peak (0.0..1.0+)
    pub replaygain_track_peak: Option<f32>,
    /// EBU R128 loudness (LUFS)
    pub r128_loudness: Option<f32>,
    /// Chromaprint fingerprint (base64)
    pub fingerprint: Option<String>,
}

/// Анализирует трек через ffmpeg (ReplayGain/R128) и fpcalc (chromaprint).
///
/// ponytail: один проход ffmpeg с ebur128 filter даёт оба значения (ReplayGain ~= R128).
/// fpcalc вызывается отдельно, если нужен fingerprint для дедупликации/поиска.
pub fn analyze(path: &Path, need_fingerprint: bool) -> Res<Analysis> {
    let mut result = Analysis {
        replaygain_track_gain: None,
        replaygain_track_peak: None,
        r128_loudness: None,
        fingerprint: None,
    };

    // ReplayGain/R128 через ebur128 filter
    let output = Command::new("ffmpeg")
        .args([
            "-i",
            path.to_str().unwrap(),
            "-af",
            "ebur128=framelog=verbose",
            "-f",
            "null",
            "-",
        ])
        .output()?;

    if output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);

        // Парсим I (integrated loudness) из лога ebur128
        if let Some(line) = stderr.lines().find(|l| l.contains("I:")) {
            if let Some(val) = line.split("I:").nth(1).and_then(|s| {
                s.trim().split_whitespace().next().and_then(|v| v.parse::<f32>().ok())
            }) {
                result.r128_loudness = Some(val);
                // ReplayGain track gain = -18 LUFS - измеренный LUFS
                result.replaygain_track_gain = Some(-18.0 - val);
            }
        }

        // Peak из того же вывода
        if let Some(line) = stderr.lines().find(|l| l.contains("Peak:")) {
            if let Some(val) = line.split("Peak:").nth(1).and_then(|s| {
                s.trim().split_whitespace().next().and_then(|v| v.parse::<f32>().ok())
            }) {
                result.replaygain_track_peak = Some(val);
            }
        }
    }

    // Chromaprint fingerprint через fpcalc
    if need_fingerprint {
        if let Ok(output) = Command::new("fpcalc")
            .args(["-raw", path.to_str().unwrap()])
            .output()
        {
            if output.status.success() {
                let stdout = String::from_utf8_lossy(&output.stdout);
                if let Some(line) = stdout.lines().find(|l| l.starts_with("FINGERPRINT=")) {
                    if let Some(fp) = line.strip_prefix("FINGERPRINT=") {
                        result.fingerprint = Some(fp.trim().to_string());
                    }
                }
            }
        }
    }

    Ok(result)
}

/// Проверяет доступность ffmpeg и fpcalc в PATH
pub fn check_tools() -> (bool, bool) {
    let ffmpeg = Command::new("ffmpeg").arg("-version").output().is_ok();
    let fpcalc = Command::new("fpcalc").arg("-version").output().is_ok();
    (ffmpeg, fpcalc)
}

/// Отчёт порционного анализа - та же форма, что у обогащения метаданных.
#[derive(Debug, Default, Serialize)]
pub struct AnalyzeReport {
    /// Сколько треков рассмотрено за вызов.
    pub checked: usize,
    /// У скольких появились реальные значения (loudness или fingerprint).
    pub analyzed: usize,
    /// Сколько треков ещё без анализа (для следующего вызова).
    pub remaining: i64,
}

/// Прогоняет анализатор по трекам без `analyzed_at`, порциями.
///
/// ponytail: ffmpeg декодирует файл целиком ради ebur128 - это секунды на трек, поэтому
/// синхронно в сканер такое ставить нельзя (50 000 треков - часы). Отдельный порционный
/// проход, как `library::enrich` для MusicBrainz: вызывающий обязан запускать в
/// spawn_blocking, `remaining` говорит, сколько осталось.
///
/// Трек помечается `analyzed_at` в любом случае - битый файл не должен вечно висеть в
/// очереди и стоить по запуску ffmpeg каждый раз.
pub fn analyze_batch(
    conn: &Connection,
    limit: usize,
    need_fingerprint: bool,
) -> rusqlite::Result<AnalyzeReport> {
    let mut rep = AnalyzeReport::default();
    let todo: Vec<(i64, String)> = {
        let mut stmt = conn.prepare(
            "SELECT id, path FROM tracks WHERE analyzed_at IS NULL ORDER BY id LIMIT ?1",
        )?;
        let rows = stmt
            .query_map([limit as i64], |r| Ok((r.get(0)?, r.get(1)?)))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        rows
    };

    for (id, path) in &todo {
        rep.checked += 1;
        conn.execute(
            "UPDATE tracks SET analyzed_at=?2 WHERE id=?1",
            rusqlite::params![id, crate::db::now()],
        )?;
        let Ok(a) = analyze(Path::new(path), need_fingerprint) else {
            continue;
        };
        conn.execute(
            "UPDATE tracks SET rg_track_gain=?2, rg_track_peak=?3, r128_loudness=?4,
                fingerprint=COALESCE(?5, fingerprint) WHERE id=?1",
            rusqlite::params![
                id,
                a.replaygain_track_gain,
                a.replaygain_track_peak,
                a.r128_loudness,
                a.fingerprint
            ],
        )?;
        if a.r128_loudness.is_some() || a.fingerprint.is_some() {
            rep.analyzed += 1;
        }
    }

    rep.remaining =
        conn.query_row("SELECT COUNT(*) FROM tracks WHERE analyzed_at IS NULL", [], |r| r.get(0))?;
    Ok(rep)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::Connection;

    fn db() -> Connection {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch(crate::db::SCHEMA).unwrap();
        c
    }

    #[test]
    fn порционный_анализ_помечает_и_считает_остаток() {
        let c = db();
        for i in 1..=3 {
            c.execute(
                "INSERT INTO tracks (path, title, seen_at) VALUES (?1, ?2, 0)",
                rusqlite::params![format!("/нет/такого-{i}.flac"), format!("трек {i}")],
            )
            .unwrap();
        }

        // Битые пути: анализ не даст значений, но пометит рассмотренными.
        let rep = analyze_batch(&c, 2, false).unwrap();
        assert_eq!(rep.checked, 2);
        assert_eq!(rep.analyzed, 0, "несуществующий файл значений не даёт");
        assert_eq!(rep.remaining, 1, "третий трек ещё в очереди");

        let done: i64 = c
            .query_row("SELECT COUNT(*) FROM tracks WHERE analyzed_at IS NOT NULL", [], |r| r.get(0))
            .unwrap();
        assert_eq!(done, 2);

        // Второй вызов добирает остаток и очередь пустеет.
        let rep = analyze_batch(&c, 10, false).unwrap();
        assert_eq!(rep.checked, 1);
        assert_eq!(rep.remaining, 0);
    }
}
