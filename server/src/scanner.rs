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

/// Чистит один сегмент пути: ни разделителей, ни `..`, ни того, что ломает Windows-ФС.
fn sanitize_segment(seg: &str) -> String {
    let cleaned: String = seg
        .chars()
        .map(|c| if c.is_control() || "<>:\"|?*/\\".contains(c) { '_' } else { c })
        .collect();
    let cleaned = cleaned.trim_matches(['.', ' ']).to_string();
    if cleaned.is_empty() || cleaned == ".." { "_".into() } else { cleaned }
}

/// Строит относительный путь загруженного файла по шаблону автосортировки.
/// Плейсхолдеры: `%artist% %albumartist% %album% %title% %track% %year%`.
/// Пустой шаблон - файл в корень папки загрузок (как было). `ext` - с точкой.
pub fn sort_path(pattern: &str, meta: &TrackMeta, ext: &str) -> PathBuf {
    let pattern = pattern.trim();
    if pattern.is_empty() {
        return PathBuf::from(format!("{}{ext}", sanitize_segment(&meta.title)));
    }
    let track = meta.track_no.map(|n| format!("{n:02}")).unwrap_or_default();
    let year = meta.year.map(|y| y.to_string()).unwrap_or_default();
    let album_artist =
        meta.album_artist.as_deref().or(meta.artist.as_deref()).unwrap_or("Unknown Artist");
    // Подстановка - посегментно: подпапки задаёт только шаблон, а `/` внутри тега
    // (например в названии) вычищается, а не режет путь на части.
    let render = |seg: &str| {
        seg.replace("%albumartist%", album_artist)
            .replace("%artist%", meta.artist.as_deref().unwrap_or("Unknown Artist"))
            .replace("%album%", meta.album.as_deref().unwrap_or("Unknown Album"))
            .replace("%title%", &meta.title)
            .replace("%track%", track.trim())
            .replace("%year%", year.trim())
    };

    let mut p = PathBuf::new();
    for seg in pattern.split(['/', '\\']) {
        let seg = sanitize_segment(render(seg).trim());
        if !seg.is_empty() {
            p.push(seg);
        }
    }
    if p.as_os_str().is_empty() {
        p.push(sanitize_segment(&meta.title));
    }
    let stem = p.file_name().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
    p.set_file_name(format!("{stem}{ext}"));
    p
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
///
/// `library_id` - в какую библиотеку кладутся найденные треки (0 - библиотека по
/// умолчанию, music_dirs из config.toml). Пропавшие файлы удаляются только внутри этой
/// же библиотеки: скан одной библиотеки не должен обнулять чужую.
pub fn scan(conn: &mut Connection, dirs: &[PathBuf], library_id: i64) -> crate::Res<ScanReport> {
    let started = crate::db::now();
    let mut rep = ScanReport::default();

    let tx = conn.transaction()?;
    // Список битых файлов пересобирается каждым проходом: чинить их будут снаружи,
    // а старая запись про уже вылеченный файл - это ложная тревога в отчёте здоровья.
    tx.execute("DELETE FROM scan_failures WHERE library_id=?1", [library_id])?;
    {
        let mut fail = tx.prepare(
            "INSERT OR REPLACE INTO scan_failures (path, error, library_id, failed_at)
             VALUES (?1,?2,?3,?4)",
        )?;
        let mut upsert = tx.prepare(
            "INSERT INTO tracks (path, title, artist, album, album_artist, track_no, year,
                                 duration_ms, size_bytes, mtime, format, seen_at, library_id)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13)
             ON CONFLICT(path) DO UPDATE SET
                title=?2, artist=?3, album=?4, album_artist=?5, track_no=?6, year=?7,
                duration_ms=?8, size_bytes=?9, mtime=?10, format=?11, seen_at=?12,
                library_id=?13",
        )?;
        // Нетронутый файл (совпали размер и mtime) не перечитывается: повторное сканирование
        // 50 000 треков не должно снова разбирать теги каждого.
        let mut touch = tx.prepare(
            "UPDATE tracks SET seen_at=?2 WHERE path=?1 AND size_bytes=?3 AND mtime=?4
             AND library_id=?5",
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
                    Err(e) => {
                        fail.execute(rusqlite::params![path, e.to_string(), library_id, started])?;
                        rep.failed += 1;
                        continue;
                    }
                };

                if touch.execute(rusqlite::params![path, started, size, mtime, library_id])? == 1 {
                    continue;
                }

                let meta = match read_meta(entry.path()) {
                    Ok(m) => m,
                    Err(e) => {
                        tracing::warn!("не разобран {path}: {e}");
                        fail.execute(rusqlite::params![path, e.to_string(), library_id, started])?;
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
                    library_id,
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
    rep.removed = tx.execute(
        "DELETE FROM tracks WHERE seen_at < ?1 AND library_id = ?2",
        rusqlite::params![started, library_id],
    )?;
    tx.commit()?;
    Ok(rep)
}

/// Допуск длительности при поиске дубля по тегам. Тот же принцип, что в
/// Android-фингерпринте: перекодированная копия отличается на доли секунды.
pub const DURATION_TOLERANCE_MS: i64 = 2000;

/// sha256 файла потоком - целиком в память файл не читается.
pub fn file_hash(path: &Path) -> std::io::Result<String> {
    use sha2::Digest;
    use std::io::Read;
    let mut f = std::fs::File::open(path)?;
    let mut hasher = sha2::Sha256::new();
    let mut buf = [0u8; 64 * 1024];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hex::encode(hasher.finalize()))
}

/// Почему трек считается уже имеющимся.
#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum DuplicateOf {
    /// Побайтово тот же файл.
    Hash,
    /// Другой файл, но тот же трек: совпали исполнитель, название и длительность.
    Metadata,
}

/// Ищет уже имеющийся в библиотеке трек: сначала по хешу файла, потом по тегам.
///
/// Поиск по тегам - в пределах одной библиотеки: в режиме раздельных библиотек
/// одинаковые треки у разных людей это разные записи, а не дубли.
pub fn find_duplicate(
    conn: &Connection,
    hash: &str,
    meta: &TrackMeta,
    library_id: i64,
) -> Option<(i64, DuplicateOf)> {
    if let Ok(id) = conn.query_row(
        "SELECT id FROM tracks WHERE file_hash=?1 AND library_id=?2",
        rusqlite::params![hash, library_id],
        |r| r.get::<_, i64>(0),
    ) {
        return Some((id, DuplicateOf::Hash));
    }
    conn.query_row(
        "SELECT id FROM tracks
         WHERE library_id=?4 AND lower(title)=lower(?1)
           AND lower(COALESCE(artist,''))=lower(COALESCE(?2,''))
           AND abs(duration_ms - ?3) <= ?5",
        rusqlite::params![
            meta.title,
            meta.artist,
            meta.duration_ms as i64,
            library_id,
            DURATION_TOLERANCE_MS
        ],
        |r| r.get::<_, i64>(0),
    )
    .ok()
    .map(|id| (id, DuplicateOf::Metadata))
}

/// Заносит уже лежащий на диске файл в библиотеку - через тот же разбор тегов,
/// что и сканер. Возвращает id и признак дубля.
pub fn add_file(
    conn: &Connection,
    path: &Path,
    library_id: i64,
) -> crate::Res<(i64, Option<DuplicateOf>)> {
    let meta = read_meta(path)?;
    let hash = file_hash(path)?;
    if let Some((id, why)) = find_duplicate(conn, &hash, &meta, library_id) {
        return Ok((id, Some(why)));
    }
    let m = std::fs::metadata(path)?;
    let mtime = m
        .modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    conn.execute(
        "INSERT INTO tracks (path, title, artist, album, album_artist, track_no, year,
                             duration_ms, size_bytes, mtime, format, seen_at, library_id, file_hash)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14)
         ON CONFLICT(path) DO UPDATE SET file_hash=?14, seen_at=?12",
        rusqlite::params![
            path.to_string_lossy(),
            meta.title,
            meta.artist,
            meta.album,
            meta.album_artist,
            meta.track_no,
            meta.year,
            meta.duration_ms as i64,
            m.len() as i64,
            mtime,
            meta.format,
            crate::db::now(),
            library_id,
            hash,
        ],
    )?;
    let id = conn.query_row(
        "SELECT id FROM tracks WHERE path=?1",
        [path.to_string_lossy()],
        |r| r.get(0),
    )?;
    Ok((id, None))
}

/// Папки каждой заведённой библиотеки (режим раздельных библиотек).
pub fn library_dirs(conn: &Connection) -> rusqlite::Result<Vec<(i64, Vec<PathBuf>)>> {
    let mut stmt = conn.prepare("SELECT id, dirs FROM libraries ORDER BY id")?;
    let rows = stmt
        .query_map([], |r| {
            let dirs: String = r.get(1)?;
            let dirs = serde_json::from_str::<Vec<String>>(&dirs)
                .unwrap_or_default()
                .into_iter()
                .map(PathBuf::from)
                .collect();
            Ok((r.get::<_, i64>(0)?, dirs))
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
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
        conn.execute_batch(crate::db::SCHEMA).unwrap();

        let dirs = vec![fixtures()];
        let first = scan(&mut conn, &dirs, 0).unwrap();
        assert_eq!(first.added, 2, "должны найтись обе фикстуры: {first:?}");
        assert_eq!(first.failed, 0);

        let second = scan(&mut conn, &dirs, 0).unwrap();
        assert_eq!(second.added, 0);
        assert_eq!(second.removed, 0, "второй проход не должен ничего удалять");

        let n: i64 = conn.query_row("SELECT COUNT(*) FROM tracks", [], |r| r.get(0)).unwrap();
        assert_eq!(n, 2);
    }

    #[test]
    fn исчезнувший_файл_удаляется_из_бд() {
        let mut conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(crate::db::SCHEMA).unwrap();
        conn.execute(
            "INSERT INTO tracks (path, title, seen_at) VALUES ('/нет/такого.mp3', 'призрак', 0)",
            [],
        )
        .unwrap();

        let rep = scan(&mut conn, &[fixtures()], 0).unwrap();
        assert_eq!(rep.removed, 1, "запись без файла должна была уйти: {rep:?}");
    }

    #[test]
    fn загруженный_файл_добавляется_один_раз() {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(crate::db::SCHEMA).unwrap();
        let f = fixtures().join("sample.flac");

        let (id, dup) = add_file(&conn, &f, 0).unwrap();
        assert_eq!(dup, None, "первая загрузка - новый трек");

        // Тот же файл ещё раз: побайтово совпал.
        let (again, dup) = add_file(&conn, &f, 0).unwrap();
        assert_eq!(again, id);
        assert_eq!(dup, Some(DuplicateOf::Hash));

        // Другой файл, но тот же трек по тегам и длительности.
        let meta = read_meta(&f).unwrap();
        let found = find_duplicate(&conn, "другой-хеш", &meta, 0);
        assert_eq!(found.map(|(i, w)| (i, w)), Some((id, DuplicateOf::Metadata)));

        // В другой библиотеке это отдельный трек, а не дубль.
        assert!(find_duplicate(&conn, "другой-хеш", &meta, 7).is_none());

        let n: i64 = conn.query_row("SELECT COUNT(*) FROM tracks", [], |r| r.get(0)).unwrap();
        assert_eq!(n, 1, "вторая запись создаваться не должна");
    }

    #[test]
    fn длительность_сверяется_с_допуском() {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(crate::db::SCHEMA).unwrap();
        let f = fixtures().join("sample.wav");
        add_file(&conn, &f, 0).unwrap();

        let mut meta = read_meta(&f).unwrap();
        meta.duration_ms += DURATION_TOLERANCE_MS as u64 - 100;
        assert!(find_duplicate(&conn, "х", &meta, 0).is_some(), "в допуске - дубль");
        meta.duration_ms += 1000;
        assert!(find_duplicate(&conn, "х", &meta, 0).is_none(), "вне допуска - другой трек");
    }

    #[test]
    fn не_аудио_игнорируется() {
        assert!(is_audio(Path::new("a/b.FLAC")));
        assert!(!is_audio(Path::new("a/cover.jpg")));
        assert!(!is_audio(Path::new("a/album.cue")));
    }

    #[test]
    fn автосортировка_раскладывает_по_шаблону() {
        let meta = TrackMeta {
            title: "Song / Two".into(),
            artist: Some("The Band".into()),
            album: Some("Album: Live".into()),
            album_artist: None,
            track_no: Some(3),
            year: Some(2020),
            duration_ms: 1000,
            format: "Flac".into(),
        };
        // album_artist пуст - берётся artist; слэш в названии не создаёт подпапку;
        // двоеточие в альбоме вычищено.
        let p = sort_path("%albumartist%/%album%/%track% %title%", &meta, ".flac");
        assert_eq!(
            p,
            PathBuf::from("The Band").join("Album_ Live").join("03 Song _ Two.flac")
        );

        // Пустой шаблон - плоско, только имя из названия.
        assert_eq!(sort_path("", &meta, ".mp3"), PathBuf::from("Song _ Two.mp3"));

        // Нет тегов - подставляются заглушки, а не пустые сегменты.
        let bare = TrackMeta { artist: None, album: None, track_no: None, year: None, ..meta };
        let p = sort_path("%artist%/%album%/%title%", &bare, ".ogg");
        assert_eq!(p, PathBuf::from("Unknown Artist").join("Unknown Album").join("Song _ Two.ogg"));
    }
}
