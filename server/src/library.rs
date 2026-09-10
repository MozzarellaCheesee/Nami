//! Здоровье библиотеки и обогащение метаданных из MusicBrainz (План.md §34, §40).
//!
//! Идея проверок - та же, что в Android-клиенте (`domain/LibraryHealth.kt`): показать то,
//! что реально можно проверить, и не выдумывать проверок, для которых нет данных.
//! Клиентские пункты про обложки и лирику сюда не переехали: обложек сервер не хранит,
//! а «нет лирики» на сервере означало бы «ещё не искали», что не диагноз.
//!
//! Проверки идут одним SQL-проходом по уже собранной библиотеке - файлы повторно не
//! открываются: на 50 000 треков это были бы минуты дисковой работы на каждый запрос
//! ручки. Единственное, что нельзя узнать из SQL - «файл не открылся», поэтому сканер
//! записывает свои неудачи в таблицу `scan_failures` в момент прохода.

use rusqlite::types::Value;
use rusqlite::Connection;
use serde::Serialize;

use crate::users::{visibility, Ident};

/// Потолок на каждый список в отчёте: отчёт читает человек, а не машина, и вываливать
/// в него 20 000 треков без тегов незачем - счётчик рядом говорит, сколько их всего.
pub const SAMPLE_LIMIT: usize = 200;

/// Короткая ссылка на трек в отчёте.
#[derive(Debug, Serialize, PartialEq)]
pub struct TrackRef {
    pub id: i64,
    pub title: String,
    pub artist: Option<String>,
    pub path: String,
}

/// Файл, который сканер не смог открыть.
#[derive(Debug, Serialize)]
pub struct BrokenFile {
    pub path: String,
    pub error: String,
    pub failed_at: i64,
}

/// Список с полным счётчиком: сам список обрезан до [`SAMPLE_LIMIT`].
#[derive(Debug, Serialize)]
pub struct Sampled<T> {
    pub count: i64,
    pub items: Vec<T>,
}

#[derive(Debug, Serialize)]
pub struct Health {
    /// Всего треков, видимых запрашивающему.
    pub tracks: i64,
    /// Файлы, не открывшиеся при последнем сканировании.
    pub broken: Sampled<BrokenFile>,
    /// Файлы, которых больше нет на диске (запись есть, файла нет).
    pub missing: Sampled<TrackRef>,
    pub without_artist: Sampled<TrackRef>,
    pub without_album: Sampled<TrackRef>,
    pub without_year: Sampled<TrackRef>,
    /// Группы предполагаемых дублей: та же эвристика, что при загрузке
    /// (исполнитель + название + длительность с допуском).
    pub duplicate_groups: Vec<Vec<TrackRef>>,
}

fn row_to_ref(r: &rusqlite::Row<'_>) -> rusqlite::Result<TrackRef> {
    Ok(TrackRef { id: r.get(0)?, title: r.get(1)?, artist: r.get(2)?, path: r.get(3)? })
}

/// Треки, подходящие под условие, плюс их полное число.
fn sampled(
    conn: &Connection,
    clause: &str,
    params: &[Value],
    extra: &str,
) -> rusqlite::Result<Sampled<TrackRef>> {
    let count: i64 = conn.query_row(
        &format!("SELECT COUNT(*) FROM tracks WHERE 1=1{clause} AND {extra}"),
        rusqlite::params_from_iter(params.iter().cloned()),
        |r| r.get(0),
    )?;
    let mut stmt = conn.prepare(&format!(
        "SELECT id, title, artist, path FROM tracks WHERE 1=1{clause} AND {extra}
         ORDER BY path LIMIT {SAMPLE_LIMIT}"
    ))?;
    let items = stmt
        .query_map(rusqlite::params_from_iter(params.iter().cloned()), row_to_ref)?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(Sampled { count, items })
}

/// Отчёт о здоровье библиотеки в пределах видимости запрашивающего.
pub fn health(conn: &Connection, ident: &Ident) -> rusqlite::Result<Health> {
    let (clause, params) = visibility(conn, ident);

    let tracks: i64 = conn.query_row(
        &format!("SELECT COUNT(*) FROM tracks WHERE 1=1{clause}"),
        rusqlite::params_from_iter(params.iter().cloned()),
        |r| r.get(0),
    )?;

    let empty = "COALESCE(TRIM(%),'') = ''";
    let without_artist = sampled(conn, &clause, &params, &empty.replace('%', "artist"))?;
    let without_album = sampled(conn, &clause, &params, &empty.replace('%', "album"))?;
    let without_year = sampled(conn, &clause, &params, "year IS NULL OR year = 0")?;

    // Битые файлы к видимости не привязаны: у них нет записи в tracks, а значит и
    // папки, по которой их можно было бы отфильтровать. Поэтому - только владельцу
    // (проверяет вызывающий) и целиком.
    let broken_count: i64 =
        conn.query_row("SELECT COUNT(*) FROM scan_failures", [], |r| r.get(0))?;
    let mut stmt = conn.prepare(&format!(
        "SELECT path, error, failed_at FROM scan_failures ORDER BY failed_at DESC
         LIMIT {SAMPLE_LIMIT}"
    ))?;
    let broken = stmt
        .query_map([], |r| {
            Ok(BrokenFile { path: r.get(0)?, error: r.get(1)?, failed_at: r.get(2)? })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;

    Ok(Health {
        tracks,
        broken: Sampled { count: broken_count, items: broken },
        missing: missing_files(conn, &clause, &params)?,
        without_artist,
        without_album,
        without_year,
        duplicate_groups: duplicates(conn, &clause, &params)?,
    })
}

/// Записи, чьих файлов больше нет на диске.
///
/// ponytail: `stat` на каждый трек - это одна дешёвая операция, но их столько же,
/// сколько треков, поэтому проверка идёт только по первым [`SAMPLE_LIMIT`] найденным
/// пропажам и обрывается. Обычно их не бывает вовсе: сканер удаляет исчезнувшие записи
/// сам, сюда попадает только то, что пропало между сканированиями.
fn missing_files(
    conn: &Connection,
    clause: &str,
    params: &[Value],
) -> rusqlite::Result<Sampled<TrackRef>> {
    let mut stmt = conn.prepare(&format!(
        "SELECT id, title, artist, path FROM tracks WHERE 1=1{clause} ORDER BY path"
    ))?;
    let mut items = Vec::new();
    let mut rows = stmt.query(rusqlite::params_from_iter(params.iter().cloned()))?;
    while let Some(r) = rows.next()? {
        let t = row_to_ref(r)?;
        if !std::path::Path::new(&t.path).exists() {
            items.push(t);
            if items.len() >= SAMPLE_LIMIT {
                break;
            }
        }
    }
    Ok(Sampled { count: items.len() as i64, items })
}

/// Группы предполагаемых дублей.
///
/// ponytail: длительность округляется до корзины в [`crate::scanner::DURATION_TOLERANCE_MS`],
/// а не сравнивается попарно с допуском - иначе это перебор всех пар (O(n²) по библиотеке).
/// Расплата: два трека по разные стороны границы корзины в одну группу не попадут.
/// Точное сравнение имеет смысл добавлять, если такие промахи окажутся заметны на практике.
fn duplicates(
    conn: &Connection,
    clause: &str,
    params: &[Value],
) -> rusqlite::Result<Vec<Vec<TrackRef>>> {
    // Группировка в Rust, а не GROUP BY: `lower()` в SQLite складывает только латиницу,
    // и «Песня»/«ПЕСНЯ» разошлись бы по разным группам. Ключи и ссылки на треки в памяти -
    // это единицы мегабайт на 50 000 треков, файлы при этом не открываются.
    let mut stmt = conn.prepare(&format!(
        "SELECT id, title, artist, path, duration_ms FROM tracks WHERE 1=1{clause} ORDER BY path"
    ))?;
    let mut rows = stmt.query(rusqlite::params_from_iter(params.iter().cloned()))?;

    let mut buckets: std::collections::HashMap<String, Vec<TrackRef>> = Default::default();
    let mut order: Vec<String> = Vec::new();
    while let Some(r) = rows.next()? {
        let duration: i64 = r.get(4)?;
        let t = row_to_ref(r)?;
        let key = format!(
            "{}|{}|{}",
            t.title.trim().to_lowercase(),
            t.artist.as_deref().unwrap_or("").trim().to_lowercase(),
            duration / crate::scanner::DURATION_TOLERANCE_MS
        );
        let entry = buckets.entry(key.clone()).or_default();
        if entry.is_empty() {
            order.push(key);
        }
        entry.push(t);
    }
    let groups = order
        .into_iter()
        .filter_map(|k| buckets.remove(&k))
        .filter(|g| g.len() > 1)
        .take(SAMPLE_LIMIT)
        .collect();
    Ok(groups)
}

// ---------------------------------------------------------------- MusicBrainz

/// Политика MusicBrainz требует не больше одного запроса в секунду и осмысленного
/// User-Agent с контактом. Держим с запасом.
const MB_MIN_INTERVAL: std::time::Duration = std::time::Duration::from_millis(1100);
const MB_USER_AGENT: &str =
    concat!("Nami-server/", env!("CARGO_PKG_VERSION"), " ( https://github.com/MozzarellaCheesee/Nami )");

/// Что удалось найти по треку.
#[derive(Debug, Default, PartialEq)]
pub struct Candidate {
    pub mbid: Option<String>,
    pub album: Option<String>,
    pub album_artist: Option<String>,
    pub year: Option<i64>,
}

/// Разбирает ответ `/ws/2/recording` - вынесено отдельно, чтобы тест не ходил в сеть.
pub fn parse_musicbrainz(body: &str) -> Option<Candidate> {
    let v: serde_json::Value = serde_json::from_str(body).ok()?;
    let recs = v.get("recordings")?.as_array()?;
    // Первым в выдаче часто идёт живая запись с бутлегом вместо альбома - берём первую
    // запись, у которой есть официальный релиз, и только если такой нет - первую подряд.
    let official = |r: &serde_json::Value| {
        r.get("releases")
            .and_then(|x| x.as_array())
            .is_some_and(|rs| {
                rs.iter().any(|x| x.get("status").and_then(|s| s.as_str()) == Some("Official"))
            })
    };
    let rec = recs.iter().find(|r| official(r)).or_else(|| recs.first())?;
    let s = |x: Option<&serde_json::Value>| {
        x.and_then(|x| x.as_str()).map(str::trim).filter(|x| !x.is_empty()).map(str::to_string)
    };
    // Из релизов берётся официальный, а не первый попавшийся: первым в ответе
    // MusicBrainz нередко оказывается бутлег живого концерта, и альбомом трека он не был.
    let releases = rec.get("releases").and_then(|r| r.as_array());
    let release = releases.and_then(|rs| {
        rs.iter()
            .find(|r| r.get("status").and_then(|s| s.as_str()) == Some("Official"))
            .or_else(|| rs.first())
    });
    Some(Candidate {
        mbid: s(rec.get("id")),
        album: release.and_then(|r| s(r.get("title"))),
        album_artist: rec
            .get("artist-credit")
            .and_then(|a| a.as_array())
            .and_then(|a| a.first())
            .and_then(|a| s(a.get("name"))),
        // Год - самый ранний among релизов: у выбранного релиза даты может не быть вовсе,
        // а трек всё же вышел когда-то, и «когда-то» - это первое издание.
        year: releases
            .map(|rs| {
                rs.iter()
                    .filter_map(|r| s(r.get("date")))
                    .filter_map(|d| d.get(..4).and_then(|y| y.parse::<i64>().ok()))
                    .min()
            })
            .unwrap_or(None),
    })
}

fn mb_search(title: &str, artist: Option<&str>) -> Option<Candidate> {
    let mut query = format!("recording:\"{}\"", title.replace('"', ""));
    if let Some(a) = artist.filter(|a| !a.is_empty()) {
        query.push_str(&format!(" AND artist:\"{}\"", a.replace('"', "")));
    }
    let url = format!(
        "https://musicbrainz.org/ws/2/recording/?query={}&fmt=json&limit=5",
        crate::lyrics::urlencode(&query)
    );
    let body = ureq::get(&url)
        .config()
        .timeout_global(Some(std::time::Duration::from_secs(10)))
        .build()
        .header("User-Agent", MB_USER_AGENT)
        .call()
        .ok()?
        .body_mut()
        .read_to_string()
        .ok()?;
    parse_musicbrainz(&body)
}

#[derive(Debug, Default, Serialize)]
pub struct EnrichReport {
    /// Сколько треков рассмотрено.
    pub checked: usize,
    /// Сколько реально дополнено.
    pub filled: usize,
    /// Сколько осталось необработанных (для следующего запроса).
    pub remaining: i64,
}

/// Дополняет метаданные из MusicBrainz у треков, где не хватает года, альбома или
/// альбом-исполнителя.
///
/// Что важно: **файлы не переписываются**. Дополняется только строка в БД сервера, теги в
/// файле остаются как были - план требует не перезаписывать имеющееся без явной просьбы,
/// а самый честный способ этого добиться - вообще не трогать чужие файлы. Уже заполненное
/// поле не затирается и в БД: дописывается только то, чего нет.
///
/// Синхронная и медленная (не быстрее одного трека в секунду - это требование политики
/// MusicBrainz, а не наша осторожность), поэтому `limit` небольшой, а вызывающий обязан
/// запускать её в `spawn_blocking`.
pub fn enrich(conn: &Connection, limit: usize) -> rusqlite::Result<EnrichReport> {
    let need = "(COALESCE(TRIM(album),'')='' OR COALESCE(TRIM(album_artist),'')=''
                OR year IS NULL OR year=0) AND enriched_at IS NULL";
    let mut rep = EnrichReport::default();
    let todo: Vec<(i64, String, Option<String>)> = {
        let mut stmt = conn.prepare(&format!(
            "SELECT id, title, artist FROM tracks WHERE {need} ORDER BY id LIMIT ?1"
        ))?;
        let rows = stmt
            .query_map([limit as i64], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        rows
    };

    for (i, (id, title, artist)) in todo.iter().enumerate() {
        if i > 0 {
            std::thread::sleep(MB_MIN_INTERVAL);
        }
        rep.checked += 1;
        // Помечаем рассмотренным в любом случае: иначе трек, которого в MusicBrainz нет,
        // будет вечно занимать место в очереди и стоить по запросу наружу каждый раз.
        conn.execute(
            "UPDATE tracks SET enriched_at=?2 WHERE id=?1",
            rusqlite::params![id, crate::db::now()],
        )?;
        let Some(c) = mb_search(title, artist.as_deref()) else { continue };
        let n = conn.execute(
            "UPDATE tracks SET
                album = CASE WHEN COALESCE(TRIM(album),'')='' THEN COALESCE(?2, album) ELSE album END,
                album_artist = CASE WHEN COALESCE(TRIM(album_artist),'')=''
                    THEN COALESCE(?3, album_artist) ELSE album_artist END,
                year = CASE WHEN year IS NULL OR year=0 THEN COALESCE(?4, year) ELSE year END,
                mbid = COALESCE(mbid, ?5)
             WHERE id=?1",
            rusqlite::params![id, c.album, c.album_artist, c.year, c.mbid],
        )?;
        if n == 1 {
            rep.filled += 1;
        }
    }
    rep.remaining =
        conn.query_row(&format!("SELECT COUNT(*) FROM tracks WHERE {need}"), [], |r| r.get(0))?;
    Ok(rep)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn db() -> Connection {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch(crate::db::SCHEMA).unwrap();
        c
    }

    fn add(c: &Connection, title: &str, artist: Option<&str>, album: Option<&str>, year: Option<i64>, dur: i64, path: &str) -> i64 {
        c.execute(
            "INSERT INTO tracks (path, title, artist, album, year, duration_ms)
             VALUES (?1,?2,?3,?4,?5,?6)",
            rusqlite::params![path, title, artist, album, year, dur],
        )
        .unwrap();
        c.last_insert_rowid()
    }

    #[test]
    fn здоровье_находит_треки_без_тегов_и_битые_файлы() {
        let c = db();
        add(&c, "Полный", Some("Кто-то"), Some("Альбом"), Some(2020), 1000, "/м/1.mp3");
        add(&c, "Без исполнителя", None, Some("Альбом"), Some(2020), 1000, "/м/2.mp3");
        add(&c, "Без года", Some("Кто-то"), Some("Альбом"), None, 1000, "/м/3.mp3");
        c.execute(
            "INSERT INTO scan_failures (path, error, failed_at) VALUES ('/м/битый.mp3','не открылся',1)",
            [],
        )
        .unwrap();

        let h = health(&c, &Ident::default()).unwrap();
        assert_eq!(h.tracks, 3);
        assert_eq!(h.without_artist.count, 1);
        assert_eq!(h.without_artist.items[0].title, "Без исполнителя");
        assert_eq!(h.without_year.count, 1);
        assert_eq!(h.without_album.count, 0);
        assert_eq!(h.broken.count, 1);
        assert_eq!(h.broken.items[0].path, "/м/битый.mp3");
        // Файлов по этим путям нет - все три записи считаются пропавшими.
        assert_eq!(h.missing.count, 3);
    }

    #[test]
    fn здоровье_группирует_дубли_той_же_эвристикой_что_и_загрузка() {
        let c = db();
        add(&c, "Песня", Some("Кто-то"), None, None, 180_000, "/м/а.mp3");
        add(&c, " песня ", Some("КТО-ТО"), None, None, 180_500, "/м/б.flac");
        add(&c, "Другая", Some("Кто-то"), None, None, 180_000, "/м/в.mp3");

        let h = health(&c, &Ident::default()).unwrap();
        assert_eq!(h.duplicate_groups.len(), 1, "ожидалась одна группа: {:?}", h.duplicate_groups);
        assert_eq!(h.duplicate_groups[0].len(), 2);
    }

    #[test]
    fn здоровье_видит_только_свою_часть_библиотеки() {
        let c = db();
        let u = crate::users::create(&c, "б", "пароль12345", "user", 0).unwrap();
        add(&c, "Без года рок", Some("Кто-то"), Some("А"), None, 1000, "/м/рок/1.mp3");
        add(&c, "Без года джаз", Some("Кто-то"), Some("А"), None, 1000, "/м/джаз/1.mp3");
        c.execute(
            "INSERT INTO user_folder_access (user_id, folder_path) VALUES (?1, '/м/рок')",
            [u],
        )
        .unwrap();

        let h = health(&c, &Ident { user_id: Some(u), device_id: None }).unwrap();
        assert_eq!(h.tracks, 1);
        assert_eq!(h.without_year.count, 1);
        assert_eq!(h.without_year.items[0].title, "Без года рок");
    }

    /// Фикстура ответа MusicBrainz, а не живой запрос: тест не должен зависеть от сети
    /// и уж точно не должен долбить публичный API на каждом прогоне.
    #[test]
    fn разбирается_ответ_musicbrainz() {
        let body = r#"{"recordings":[{"id":"abc-123","title":"Песня",
            "artist-credit":[{"name":"Кто-то"}],
            "releases":[{"title":"Бутлег","status":"Bootleg","date":"2001-01-01"},
                        {"title":"Альбом","status":"Official","date":"1997-05-12"}]}]}"#;
        let c = parse_musicbrainz(body).unwrap();
        assert_eq!(c.mbid.as_deref(), Some("abc-123"));
        assert_eq!(c.album.as_deref(), Some("Альбом"));
        assert_eq!(c.album_artist.as_deref(), Some("Кто-то"));
        assert_eq!(c.year, Some(1997));

        assert_eq!(parse_musicbrainz(r#"{"recordings":[]}"#), None);
        assert_eq!(parse_musicbrainz("не json"), None);
    }

    #[test]
    fn обогащение_не_трогает_уже_заполненные_поля() {
        let c = db();
        let id = add(&c, "Песня", Some("Кто-то"), Some("Свой альбом"), None, 1000, "/м/1.mp3");
        // Ровно тот UPDATE, который делает enrich, но без похода в сеть.
        c.execute(
            "UPDATE tracks SET
                album = CASE WHEN COALESCE(TRIM(album),'')='' THEN COALESCE(?2, album) ELSE album END,
                year = CASE WHEN year IS NULL OR year=0 THEN COALESCE(?3, year) ELSE year END
             WHERE id=?1",
            rusqlite::params![id, "Чужой альбом", 1997],
        )
        .unwrap();
        let (album, year): (String, i64) = c
            .query_row("SELECT album, year FROM tracks WHERE id=?1", [id], |r| {
                Ok((r.get(0)?, r.get(1)?))
            })
            .unwrap();
        assert_eq!(album, "Свой альбом", "заполненное поле затирать нельзя");
        assert_eq!(year, 1997, "пустое - дополняется");
    }
}
