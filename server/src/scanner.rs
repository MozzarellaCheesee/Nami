use std::path::{Path, PathBuf};

use lofty::prelude::*;
use rusqlite::Connection;
use serde::Serialize;
use walkdir::WalkDir;

/// Расширения, которые вообще имеет смысл открывать. Дешёвый фильтр до чтения тегов:
/// на 50 000 файлов лишний open() каждой обложки и .cue заметен.
const AUDIO_EXT: &[&str] = &[
    "mp3", "flac", "ogg", "opus", "m4a", "mp4", "aac", "wav", "wv", "ape", "aiff", "aif", "mpc",
];

#[derive(Debug, Default, Clone, Serialize)]
pub struct ScanReport {
    pub scanned: usize,
    pub added: usize,
    pub updated: usize,
    pub removed: usize,
    pub failed: usize,
}

/// Разобранные теги одного файла.
#[derive(Debug, Clone, PartialEq)]
pub struct TrackMeta {
    pub title: String,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub album_artist: Option<String>,
    pub track_no: Option<u32>,
    pub year: Option<u32>,
    pub duration_ms: u64,
    pub format: String,
}

/// Читает теги файла через lofty. Файл без тегов - не ошибка: заголовок берётся из имени,
/// иначе такой трек просто исчез бы из библиотеки.
pub fn read_meta(path: &Path) -> crate::Res<TrackMeta> {
    let tagged = lofty::read_from_path(path)?;
    let props = tagged.properties();
    let tag = tagged.primary_tag().or_else(|| tagged.first_tag());

    let s = |v: Option<std::borrow::Cow<'_, str>>| {
        v.map(|x| x.trim().to_string()).filter(|x| !x.is_empty())
    };

    let title = tag
        .and_then(|t| s(t.title()))
        .unwrap_or_else(|| file_stem(path));

    Ok(TrackMeta {
        title,
        artist: tag.and_then(|t| s(t.artist())),
        album: tag.and_then(|t| s(t.album())),
        album_artist: tag.and_then(|t| s(t.get_string(ItemKey::AlbumArtist).map(Into::into))),
        track_no: tag.and_then(|t| t.track()),
        // В lofty 0.25 у Accessor нет year(): год лежит строкой и в разных форматах может быть
        // полной датой - берём первые 4 цифры.
        year: tag
            .and_then(|t| t.get_string(ItemKey::Year).or_else(|| t.get_string(ItemKey::RecordingDate)))
            .and_then(|v| v.get(..4).and_then(|y| y.parse().ok())),
        duration_ms: props.duration().as_millis() as u64,
        format: format!("{:?}", tagged.file_type()),
    })
}

fn file_stem(path: &Path) -> String {
    path.file_stem()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| path.to_string_lossy().to_string())
}

fn is_audio(path: &Path) -> bool {
    path.extension()
        .and_then(|e| e.to_str())
        .map(|e| AUDIO_EXT.contains(&e.to_ascii_lowercase().as_str()))
        .unwrap_or(false)
}

/// Полное сканирование указанных папок в БД.
///
/// Синхронная функция - вызывающий обязан запускать её в `spawn_blocking`.
/// Файлы обрабатываются потоком, по одному: пиковая память не зависит от размера
/// библиотеки (цель "50 000 треков на 512 МБ"), поэтому никакого `Vec<TrackMeta>` со
/// всей библиотекой здесь нет.
///
/// Вызывается и вручную (`POST /api/scan`), и автоматически из `watcher` по событиям ФС.
pub fn scan(conn: &mut Connection, dirs: &[PathBuf]) -> crate::Res<ScanReport> {
    let started = crate::db::now();
    let mut rep = ScanReport::default();

    let tx = conn.transaction()?;
    {
        let mut upsert = tx.prepare(
            "INSERT INTO tracks (path, title, artist, album, album_artist, track_no, year,
                                 duration_ms, size_bytes, mtime, format, seen_at)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12)
             ON CONFLICT(path) DO UPDATE SET
                title=?2, artist=?3, album=?4, album_artist=?5, track_no=?6, year=?7,
                duration_ms=?8, size_bytes=?9, mtime=?10, format=?11, seen_at=?12",
        )?;
        // Нетронутый файл (совпали размер и mtime) не перечитывается: повторное сканирование
        // 50 000 треков не должно снова разбирать теги каждого.
        let mut touch = tx.prepare(
            "UPDATE tracks SET seen_at=?2 WHERE path=?1 AND size_bytes=?3 AND mtime=?4",
        )?;

        for dir in dirs {
            for entry in WalkDir::new(dir).follow_links(false).into_iter().filter_map(|e| e.ok()) {
                if !entry.file_type().is_file() || !is_audio(entry.path()) {
                    continue;
                }
                rep.scanned += 1;
                let path = entry.path().to_string_lossy().to_string();
                let (size, mtime) = match entry.metadata() {
                    Ok(m) => (
                        m.len() as i64,
                        m.modified()
                            .ok()
                            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                            .map(|d| d.as_secs() as i64)
                            .unwrap_or(0),
                    ),
                    Err(_) => {
                        rep.failed += 1;
                        continue;
                    }
                };

                if touch.execute(rusqlite::params![path, started, size, mtime])? == 1 {
                    continue;
                }

                let meta = match read_meta(entry.path()) {
                    Ok(m) => m,
                    Err(e) => {
                        tracing::warn!("не разобран {path}: {e}");
                        rep.failed += 1;
                        continue;
                    }
                };
                let existed: bool = tx.query_row(
                    "SELECT 1 FROM tracks WHERE path=?1",
                    [&path],
                    |_| Ok(true),
                ).unwrap_or(false);
                upsert.execute(rusqlite::params![
                    path,
                    meta.title,
                    meta.artist,
                    meta.album,
                    meta.album_artist,
                    meta.track_no,
                    meta.year,
                    meta.duration_ms as i64,
                    size,
                    mtime,
                    meta.format,
                    started,
                ])?;
                if existed {
                    rep.updated += 1;
                } else {
                    rep.added += 1;
                }
            }
        }
    }
    // Всё, что не попалось в этом проходе, из библиотеки удалено.
    rep.removed = tx.execute("DELETE FROM tracks WHERE seen_at < ?1", [started])?;
    tx.commit()?;
    Ok(rep)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixtures() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures")
    }

    #[test]
    fn читаются_теги_wav() {
        let m = read_meta(&fixtures().join("sample.wav")).unwrap();
        assert_eq!(m.title, "Nami Test Wav");
        assert_eq!(m.artist.as_deref(), Some("Nami Tester"));
        assert_eq!(m.album.as_deref(), Some("Fixtures"));
        assert_eq!(m.track_no, Some(3));
        assert_eq!(m.year, Some(2024));
        // Фикстура - ровно секунда PCM 8000 Гц/16 бит/моно.
        assert_eq!(m.duration_ms, 1000, "длительность WAV посчитана неверно");
    }

    #[test]
    fn читаются_теги_flac() {
        let m = read_meta(&fixtures().join("sample.flac")).unwrap();
        assert_eq!(m.title, "Nami Test Flac");
        assert_eq!(m.artist.as_deref(), Some("Nami Tester"));
        assert_eq!(m.track_no, Some(7));
        assert_eq!(m.year, Some(2023));
    }

    #[test]
    fn сканирование_наполняет_бд_и_повторный_проход_идемпотентен() {
        let mut conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(
            "CREATE TABLE tracks (id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE,
             title TEXT NOT NULL, artist TEXT, album TEXT, album_artist TEXT, track_no INTEGER,
             year INTEGER, duration_ms INTEGER NOT NULL DEFAULT 0,
             size_bytes INTEGER NOT NULL DEFAULT 0, mtime INTEGER NOT NULL DEFAULT 0,
             format TEXT, seen_at INTEGER NOT NULL DEFAULT 0)",
        )
        .unwrap();

        let dirs = vec![fixtures()];
        let first = scan(&mut conn, &dirs).unwrap();
        assert_eq!(first.added, 2, "должны найтись обе фикстуры: {first:?}");
        assert_eq!(first.failed, 0);

        let second = scan(&mut conn, &dirs).unwrap();
        assert_eq!(second.added, 0);
        assert_eq!(second.removed, 0, "второй проход не должен ничего удалять");

        let n: i64 = conn.query_row("SELECT COUNT(*) FROM tracks", [], |r| r.get(0)).unwrap();
        assert_eq!(n, 2);
    }

    #[test]
    fn исчезнувший_файл_удаляется_из_бд() {
        let mut conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(
            "CREATE TABLE tracks (id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE,
             title TEXT NOT NULL, artist TEXT, album TEXT, album_artist TEXT, track_no INTEGER,
             year INTEGER, duration_ms INTEGER NOT NULL DEFAULT 0,
             size_bytes INTEGER NOT NULL DEFAULT 0, mtime INTEGER NOT NULL DEFAULT 0,
             format TEXT, seen_at INTEGER NOT NULL DEFAULT 0)",
        )
        .unwrap();
        conn.execute(
            "INSERT INTO tracks (path, title, seen_at) VALUES ('/нет/такого.mp3', 'призрак', 0)",
            [],
        )
        .unwrap();

        let rep = scan(&mut conn, &[fixtures()]).unwrap();
        assert_eq!(rep.removed, 1, "запись без файла должна была уйти: {rep:?}");
    }

    #[test]
    fn не_аудио_игнорируется() {
        assert!(is_audio(Path::new("a/b.FLAC")));
        assert!(!is_audio(Path::new("a/cover.jpg")));
        assert!(!is_audio(Path::new("a/album.cue")));
    }
}
