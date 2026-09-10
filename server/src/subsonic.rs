//! Подмножество OpenSubsonic API под префиксом `/rest/` (План.md §41).
//!
//! Смысл слоя один: дать слушать библиотеку в чужом клиенте (Symfonium, Feishin, DSub) без
//! своего приложения. Поэтому реализовано ровно то, что для этого нужно, и ничего больше:
//! `ping`, `getLicense`, `getMusicFolders`, `getIndexes`/`getArtists`/`getArtist`,
//! `getAlbum`/`getAlbumList2`, `getSong`, `search3`, `getPlaylists`/`getPlaylist`,
//! `stream`/`download` и `scrobble`-заглушка. Весь остальной OpenSubsonic (звёзды,
//! радио, подкасты, обмен, редактирование плейлистов) отвечает "не поддерживается" -
//! честный код 0 лучше, чем правдоподобная пустышка, на которую клиент построит своё UI.
//!
//! # Почему отдельный Subsonic-пароль
//!
//! Историческая аутентификация Subsonic - это `t=md5(пароль + соль)`: сервер обязан знать
//! пароль в открытом (или обратимо зашифрованном) виде, чтобы посчитать тот же md5.
//! Наши пароли лежат argon2id-хешем, из которого пароль не достать - и это правильно.
//!
//! Ровно с этим живут все современные реализации: Navidrome хранит пароль обратимо
//! зашифрованным именно ради Subsonic-протокола и честно пишет об этом в документации.
//! Мы выбрали другой размен: основной пароль остаётся необратимым, а для `/rest/` человек
//! заводит ОТДЕЛЬНЫЙ Subsonic-пароль (`PUT /api/me/subsonic-password`). Он хранится как
//! есть - иначе протокол не работает - но это осознанно второй, одноразовый по смыслу
//! секрет: его утечка не даёт ни входа в основной аккаунт, ни прав владельца, и отозвать
//! его - это одна ручка, а не смена основного пароля.
//!
//! Пока Subsonic-пароль не задан, `/rest/` для этого пользователя просто не работает:
//! незаданный секрет не должен молча превращаться в разрешающий.

use std::collections::HashMap;

use axum::extract::{Path, Query, Request, State};
use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use rusqlite::types::Value as SqlValue;
use rusqlite::Connection;
use serde_json::{json, Value};

use crate::api::Shared;
use crate::users::Ident;

/// Версия протокола, которую объявляем. 1.16.1 - последняя версия Subsonic, от которой
/// отсчитывается OpenSubsonic; клиенты сверяют её с параметром `v` запроса.
pub const API_VERSION: &str = "1.16.1";
/// Артикли, которые клиенты игнорируют при сортировке. Значение по умолчанию Subsonic.
const IGNORED_ARTICLES: &str = "The El La Los Las Le Les";

/// Коды ошибок протокола (те из них, что мы реально возвращаем).
const E_MISSING_PARAM: i32 = 10;
const E_BAD_CREDENTIALS: i32 = 40;
const E_NOT_FOUND: i32 = 70;
const E_NOT_SUPPORTED: i32 = 0;

pub fn router() -> axum::Router<Shared> {
    // Один обработчик на все действия: разбор идёт по последнему сегменту пути.
    // Отдельный route на каждое действие - это тридцать одинаковых строк ради ровно
    // того же match внутри.
    axum::Router::new().route("/rest/{action}", axum::routing::get(dispatch).post(dispatch))
}

// ---------------------------------------------------------------- формат ответа

/// Оборачивает тело в `subsonic-response` и отдаёт в том формате, который просил клиент.
fn respond(params: &HashMap<String, String>, body: Value) -> Response {
    let mut root = json!({
        "status": "ok",
        "version": API_VERSION,
        // Поля OpenSubsonic-расширения: по ним клиент понимает, что можно спрашивать больше.
        "type": "nami",
        "serverVersion": env!("CARGO_PKG_VERSION"),
        "openSubsonic": true,
    });
    if let (Some(obj), Value::Object(extra)) = (root.as_object_mut(), body) {
        obj.extend(extra);
    }
    finish(params, root)
}

fn fail(params: &HashMap<String, String>, code: i32, message: &str) -> Response {
    finish(
        params,
        json!({
            "status": "failed",
            "version": API_VERSION,
            "type": "nami",
            "serverVersion": env!("CARGO_PKG_VERSION"),
            "openSubsonic": true,
            "error": { "code": code, "message": message },
        }),
    )
}

/// JSON или XML - по параметру `f`. По спецификации умолчание - XML, и старые клиенты
/// (DSub) на нём и работают, поэтому оба формата настоящие, а не "json и как-нибудь".
fn finish(params: &HashMap<String, String>, root: Value) -> Response {
    let format = params.get("f").map(String::as_str).unwrap_or("xml");
    match format {
        "json" => (
            [(header::CONTENT_TYPE, "application/json; charset=utf-8")],
            serde_json::to_string(&json!({ "subsonic-response": root })).unwrap_or_default(),
        )
            .into_response(),
        "jsonp" => {
            let cb = params.get("callback").map(String::as_str).unwrap_or("callback");
            let body = serde_json::to_string(&json!({ "subsonic-response": root })).unwrap_or_default();
            (
                [(header::CONTENT_TYPE, "application/javascript; charset=utf-8")],
                format!("{cb}({body});"),
            )
                .into_response()
        }
        _ => {
            let mut out = String::from("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            to_xml("subsonic-response", &root, true, &mut out);
            ([(header::CONTENT_TYPE, "application/xml; charset=utf-8")], out).into_response()
        }
    }
}

/// JSON -> XML в форме, которую ждёт Subsonic: скаляры объекта становятся атрибутами,
/// вложенные объекты и массивы - дочерними элементами с тем же именем поля.
fn to_xml(name: &str, v: &Value, root: bool, out: &mut String) {
    let obj = match v {
        Value::Object(o) => o,
        // Скаляр в позиции элемента - редкость (её нет в наших ответах), но пусть
        // не теряется молча.
        other => {
            out.push_str(&format!("<{name}>{}</{name}>", xml_escape(&scalar(other))));
            return;
        }
    };
    out.push('<');
    out.push_str(name);
    if root {
        out.push_str(" xmlns=\"http://subsonic.org/restapi\"");
    }
    for (k, val) in obj.iter() {
        if !matches!(val, Value::Object(_) | Value::Array(_) | Value::Null) {
            out.push_str(&format!(" {k}=\"{}\"", xml_escape(&scalar(val))));
        }
    }
    let children: Vec<(&String, &Value)> = obj
        .iter()
        .filter(|(_, val)| matches!(val, Value::Object(_) | Value::Array(_)))
        .collect();
    if children.is_empty() {
        out.push_str("/>");
        return;
    }
    out.push('>');
    for (k, val) in children {
        match val {
            Value::Array(items) => {
                for item in items {
                    to_xml(k, item, false, out);
                }
            }
            other => to_xml(k, other, false, out),
        }
    }
    out.push_str(&format!("</{name}>"));
}

fn scalar(v: &Value) -> String {
    match v {
        Value::String(s) => s.clone(),
        Value::Null => String::new(),
        other => other.to_string(),
    }
}

fn xml_escape(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}

// ---------------------------------------------------------------- аутентификация

fn md5_hex(data: &[u8]) -> String {
    use md5::Digest;
    hex::encode(md5::Md5::digest(data))
}

/// Проверяет параметры `u` + (`t`,`s`) либо `p`. Возвращает id пользователя.
///
/// Сравнение хешей идёт по всей длине, без раннего выхода: `==` у String в Rust сначала
/// сверяет длину, но дальше сравнивает побайтово до первого различия. Для md5-токена,
/// который клиент и так пересылает открытым текстом по каждому запросу, тайминг-атака
/// не тот риск, ради которого стоит тащить constant-time сравнение.
pub fn authenticate(conn: &Connection, params: &HashMap<String, String>) -> Result<i64, (i32, &'static str)> {
    let user = params.get("u").map(String::as_str).unwrap_or("").trim();
    if user.is_empty() {
        return Err((E_MISSING_PARAM, "нужен параметр u"));
    }
    let row: Option<(i64, Option<String>)> = conn
        .query_row(
            "SELECT id, subsonic_password FROM users WHERE username=?1",
            [user],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .ok();
    let Some((id, Some(password))) = row else {
        // Нет пользователя и "есть, но Subsonic-пароль не задан" - для клиента одно и то же.
        return Err((E_BAD_CREDENTIALS, "неверный логин или Subsonic-пароль"));
    };

    let ok = match (params.get("t"), params.get("s"), params.get("p")) {
        (Some(token), Some(salt), _) => md5_hex(format!("{password}{salt}").as_bytes()) == token.to_lowercase(),
        // Старая форма: пароль прямо в запросе, иногда как "enc:<hex>".
        (_, _, Some(p)) => {
            let plain = match p.strip_prefix("enc:") {
                Some(h) => hex::decode(h).ok().and_then(|b| String::from_utf8(b).ok()),
                None => Some(p.clone()),
            };
            plain.as_deref() == Some(password.as_str())
        }
        _ => return Err((E_MISSING_PARAM, "нужны параметры t и s (или p)")),
    };
    if ok {
        Ok(id)
    } else {
        Err((E_BAD_CREDENTIALS, "неверный логин или Subsonic-пароль"))
    }
}

// ---------------------------------------------------------------- идентификаторы

/// Артисты и альбомы у нас не таблицы, а группировки по тегам (см. db.rs), поэтому их
/// id - это закодированное имя, а не номер строки. hex, а не base64: не требует
/// экранирования в URL и не зависит от диалекта base64 клиента.
fn enc_id(prefix: &str, s: &str) -> String {
    format!("{prefix}{}", hex::encode(s.as_bytes()))
}

fn dec_id<'a>(prefix: &str, id: &'a str) -> Option<String> {
    let hexpart = id.strip_prefix(prefix)?;
    String::from_utf8(hex::decode(hexpart).ok()?).ok()
}

const ARTIST: &str = "ar-";
const ALBUM: &str = "al-";
/// Разделитель "исполнитель/альбом" внутри id альбома - символ, которого не бывает в тегах.
const SEP: char = '\u{1}';

/// unix-секунды -> ISO 8601 UTC: Subsonic-клиенты сортируют по `created`, а формат там
/// строковый. Алгоритм civil_from_days - без крейта дат ради одной функции.
fn iso8601(ts: i64) -> String {
    let days = ts.div_euclid(86_400);
    let secs = ts.rem_euclid(86_400);
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    format!(
        "{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z",
        secs / 3600,
        secs / 60 % 60,
        secs % 60
    )
}

fn content_type(format: Option<&str>, path: &str) -> (String, String) {
    let suffix = std::path::Path::new(path)
        .extension()
        .map(|e| e.to_string_lossy().to_ascii_lowercase())
        .unwrap_or_else(|| format.unwrap_or("").to_ascii_lowercase());
    let mime = match suffix.as_str() {
        "mp3" => "audio/mpeg",
        "flac" => "audio/flac",
        "ogg" => "audio/ogg",
        "opus" => "audio/ogg",
        "m4a" | "mp4" | "aac" => "audio/mp4",
        "wav" => "audio/wav",
        _ => "application/octet-stream",
    };
    (suffix, mime.to_string())
}

// ---------------------------------------------------------------- выборки

/// Полный набор колонок трека для ответа-`child`.
const SONG_COLS: &str = "id, title, artist, album, album_artist, track_no, year,
     duration_ms, size_bytes, mtime, format, path";

/// Строка трека -> `child` в терминах Subsonic.
fn row_to_song(r: &rusqlite::Row<'_>) -> rusqlite::Result<Value> {
    let id: i64 = r.get(0)?;
    let title: String = r.get(1)?;
    let artist: Option<String> = r.get(2)?;
    let album: Option<String> = r.get(3)?;
    let album_artist: Option<String> = r.get(4)?;
    let track_no: Option<i64> = r.get(5)?;
    let year: Option<i64> = r.get(6)?;
    let duration_ms: i64 = r.get(7)?;
    let size: i64 = r.get(8)?;
    let mtime: i64 = r.get(9)?;
    let format: Option<String> = r.get(10)?;
    let path: String = r.get(11)?;

    let credited = album_artist.clone().or_else(|| artist.clone()).unwrap_or_default();
    let (suffix, mime) = content_type(format.as_deref(), &path);
    let duration = (duration_ms + 999) / 1000;
    let mut v = json!({
        "id": id.to_string(),
        "isDir": false,
        "title": title,
        "duration": duration,
        "size": size,
        "suffix": suffix,
        "contentType": mime,
        "created": iso8601(mtime),
        "type": "music",
        // Путь клиенту нужен для группировки по папкам; отдаём относительный хвост,
        // а не абсолютный путь на сервере.
        "path": path.rsplit(['/', '\\']).next().unwrap_or(&path),
        "bitRate": if duration > 0 { size * 8 / 1000 / duration } else { 0 },
    });
    let o = v.as_object_mut().unwrap();
    if let Some(a) = &artist {
        o.insert("artist".into(), json!(a));
    }
    if !credited.is_empty() {
        o.insert("artistId".into(), json!(enc_id(ARTIST, &credited)));
    }
    if let Some(a) = &album {
        o.insert("album".into(), json!(a));
        o.insert("albumId".into(), json!(enc_id(ALBUM, &format!("{credited}{SEP}{a}"))));
        o.insert("parent".into(), json!(enc_id(ALBUM, &format!("{credited}{SEP}{a}"))));
    }
    if let Some(t) = track_no {
        o.insert("track".into(), json!(t));
    }
    if let Some(y) = year {
        o.insert("year".into(), json!(y));
    }
    Ok(v)
}

/// Имя, под которым трек попадает в список исполнителей: альбом-исполнитель, иначе
/// исполнитель. Один и тот же SQL нужен и в списке артистов, и в фильтре альбомов.
const CREDITED: &str =
    "COALESCE(NULLIF(TRIM(album_artist),''), NULLIF(TRIM(artist),''), 'Unknown Artist')";

/// По Subsonic-id обложки находит трек, чью картинку отдать: сам номер трека,
/// либо любой видимый трек альбома (`al-`) или исполнителя (`ar-`).
fn cover_track_id(conn: &Connection, ident: &Ident, raw: &str) -> Option<i64> {
    if let Ok(id) = raw.parse::<i64>() {
        return crate::users::can_see_track(conn, ident, id).then_some(id);
    }
    let (clause, vis) = crate::users::visibility(conn, ident);
    let (extra, ps): (&str, Vec<SqlValue>) = if let Some(s) = dec_id(ALBUM, raw) {
        let (artist, album) = s.split_once(SEP)?;
        (
            "AND COALESCE(album,'') = ? AND {C} = ?",
            vec![SqlValue::Text(album.into()), SqlValue::Text(artist.into())],
        )
    } else if let Some(name) = dec_id(ARTIST, raw) {
        ("AND {C} = ?", vec![SqlValue::Text(name)])
    } else {
        return None;
    };
    let sql = format!(
        "SELECT id FROM tracks WHERE 1=1{clause} {} ORDER BY id LIMIT 1",
        extra.replace("{C}", CREDITED)
    );
    let all = vis.into_iter().chain(ps);
    conn.query_row(&sql, rusqlite::params_from_iter(all), |r| r.get(0)).ok()
}

fn songs_where(
    conn: &Connection,
    clause: &str,
    params: &[SqlValue],
    extra: &str,
    extra_params: Vec<SqlValue>,
    order: &str,
    limit: i64,
) -> rusqlite::Result<Vec<Value>> {
    let mut stmt = conn.prepare(&format!(
        "SELECT {SONG_COLS} FROM tracks WHERE 1=1{clause} {extra} ORDER BY {order} LIMIT {limit}"
    ))?;
    let all = params.iter().cloned().chain(extra_params);
    let rows = stmt
        .query_map(rusqlite::params_from_iter(all), row_to_song)?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

/// Альбомы (AlbumID3) с необязательным дополнительным условием.
fn albums(
    conn: &Connection,
    clause: &str,
    params: &[SqlValue],
    extra: &str,
    extra_params: Vec<SqlValue>,
    order: &str,
    limit: i64,
    offset: i64,
) -> rusqlite::Result<Vec<Value>> {
    let mut stmt = conn.prepare(&format!(
        "SELECT {CREDITED} AS credited, COALESCE(album,'') AS al, COUNT(*),
                SUM(duration_ms), MIN(NULLIF(year,0)), MAX(mtime)
         FROM tracks WHERE 1=1{clause} AND COALESCE(TRIM(album),'') <> '' {extra}
         GROUP BY credited, al ORDER BY {order} LIMIT {limit} OFFSET {offset}"
    ))?;
    let all = params.iter().cloned().chain(extra_params);
    let rows = stmt
        .query_map(rusqlite::params_from_iter(all), |r| {
            let credited: String = r.get(0)?;
            let name: String = r.get(1)?;
            let songs: i64 = r.get(2)?;
            let duration_ms: i64 = r.get(3).unwrap_or(0);
            let year: Option<i64> = r.get(4)?;
            let mtime: i64 = r.get(5).unwrap_or(0);
            let mut v = json!({
                "id": enc_id(ALBUM, &format!("{credited}{SEP}{name}")),
                "name": name,
                "title": name,
                "artist": credited,
                "artistId": enc_id(ARTIST, &credited),
                "songCount": songs,
                "duration": (duration_ms + 999) / 1000,
                "created": iso8601(mtime),
            });
            if let Some(y) = year {
                v.as_object_mut().unwrap().insert("year".into(), json!(y));
            }
            Ok(v)
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

fn artists(
    conn: &Connection,
    clause: &str,
    params: &[SqlValue],
    extra: &str,
    extra_params: Vec<SqlValue>,
) -> rusqlite::Result<Vec<Value>> {
    let mut stmt = conn.prepare(&format!(
        "SELECT {CREDITED} AS credited, COUNT(DISTINCT COALESCE(album,''))
         FROM tracks WHERE 1=1{clause} {extra} GROUP BY credited ORDER BY credited"
    ))?;
    let all = params.iter().cloned().chain(extra_params);
    let rows = stmt
        .query_map(rusqlite::params_from_iter(all), |r| {
            let name: String = r.get(0)?;
            Ok(json!({
                "id": enc_id(ARTIST, &name),
                "name": name,
                "albumCount": r.get::<_, i64>(1)?,
            }))
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

/// Разбивка списка артистов по первой букве - форма ответа `getIndexes`/`getArtists`.
fn index_of(list: Vec<Value>) -> Vec<Value> {
    let mut out: Vec<(String, Vec<Value>)> = Vec::new();
    for a in list {
        let name = a.get("name").and_then(|n| n.as_str()).unwrap_or("");
        let letter = name
            .chars()
            .next()
            .map(|c| c.to_uppercase().to_string())
            .filter(|c| c.chars().next().is_some_and(char::is_alphabetic))
            .unwrap_or_else(|| "#".into());
        match out.last_mut() {
            Some((l, v)) if *l == letter => v.push(a),
            _ => out.push((letter, vec![a])),
        }
    }
    out.into_iter().map(|(name, artist)| json!({ "name": name, "artist": artist })).collect()
}

// ---------------------------------------------------------------- плейлисты

/// Читает записи одной сущности из таблицы синхронизации, отбрасывая удалённые.
///
/// Плейлисты живут в `state` непрозрачным для сервера JSON (см. sync.rs), поэтому здесь -
/// единственное место, где сервер о форме этих полей что-то знает. Ожидается:
/// `playlist` с полем `name` и `playlist_track` с полями `playlist_id`, `track_id`,
/// `position`. Формат зафиксирован в README - Android-клиент к серверу этими сущностями
/// ещё не ходит, и договориться о форме надо было именно здесь.
/// Третий элемент кортежа - самая свежая метка времени записи: она идёт в `changed`,
/// когда клиент своих `created`/`changed` не прислал.
type Record = (String, HashMap<String, Value>, i64);

fn state_records(conn: &Connection, user_id: i64, entity: &str) -> rusqlite::Result<Vec<Record>> {
    let mut stmt = conn.prepare(
        "SELECT id, field, value, updated_at FROM state WHERE user_id=?1 AND entity=?2 ORDER BY id",
    )?;
    let mut map: HashMap<String, (HashMap<String, Value>, i64)> = HashMap::new();
    let mut order: Vec<String> = Vec::new();
    let mut rows = stmt.query(rusqlite::params![user_id, entity])?;
    while let Some(r) = rows.next()? {
        let id: String = r.get(0)?;
        let field: String = r.get(1)?;
        let raw: String = r.get(2)?;
        let at: i64 = r.get(3)?;
        let value: Value = serde_json::from_str(&raw).unwrap_or(Value::Null);
        if !map.contains_key(&id) {
            order.push(id.clone());
        }
        let entry = map.entry(id).or_default();
        entry.0.insert(field, value);
        entry.1 = entry.1.max(at);
    }
    Ok(order
        .into_iter()
        .filter_map(|id| {
            let (fields, at) = map.remove(&id)?;
            let deleted = fields.get(crate::sync::DELETED).and_then(|v| v.as_bool()).unwrap_or(false);
            if deleted {
                None
            } else {
                Some((id, fields, at))
            }
        })
        .collect())
}

fn field_str(f: &HashMap<String, Value>, key: &str) -> Option<String> {
    match f.get(key) {
        Some(Value::String(s)) => Some(s.clone()),
        Some(Value::Number(n)) => Some(n.to_string()),
        _ => None,
    }
}

/// Треки плейлиста в порядке `position`.
fn playlist_track_ids(conn: &Connection, user_id: i64, playlist_id: &str) -> rusqlite::Result<Vec<i64>> {
    let mut items: Vec<(i64, i64)> = state_records(conn, user_id, "playlist_track")?
        .into_iter()
        .filter(|(_, f, _)| field_str(f, "playlist_id").as_deref() == Some(playlist_id))
        .filter_map(|(_, f, _)| {
            let track = field_str(&f, "track_id")?.parse::<i64>().ok()?;
            let pos = field_str(&f, "position").and_then(|p| p.parse::<i64>().ok()).unwrap_or(0);
            Some((pos, track))
        })
        .collect();
    items.sort_by_key(|(pos, _)| *pos);
    Ok(items.into_iter().map(|(_, t)| t).collect())
}

/// Плейлист в терминах Subsonic. `songs` - уже собранные треки (для `getPlaylist`).
fn playlist_json(
    id: &str,
    fields: &HashMap<String, Value>,
    at: i64,
    owner: &str,
    songs: &[Value],
) -> Value {
    let duration: i64 = songs.iter().filter_map(|s| s.get("duration").and_then(|d| d.as_i64())).sum();
    let stamp = |key: &str| {
        iso8601(field_str(fields, key).and_then(|c| c.parse().ok()).unwrap_or(at))
    };
    json!({
        "id": id,
        "name": field_str(fields, "name").unwrap_or_else(|| id.to_string()),
        "owner": owner,
        "public": false,
        "songCount": songs.len(),
        "duration": duration,
        "created": stamp("created"),
        "changed": stamp("changed"),
    })
}

// ---------------------------------------------------------------- диспетчер

fn num(params: &HashMap<String, String>, key: &str, default: i64) -> i64 {
    params.get(key).and_then(|v| v.parse().ok()).unwrap_or(default)
}

/// Профиль транскодинга по `maxBitRate`/`format` Subsonic: клиент просит потолок битрейта,
/// мы отдаём ближайший профиль не выше него. 0 или отсутствие - оригинал байт-в-байт.
fn profile_for(params: &HashMap<String, String>) -> Option<&'static str> {
    let max = num(params, "maxBitRate", 0);
    if max <= 0 {
        return None;
    }
    let aac = params.get("format").map(|f| f == "aac" || f == "m4a").unwrap_or(false);
    let candidates: &[(&str, i64)] = if aac {
        &[("aac192", 192), ("aac128", 128)]
    } else {
        &[("opus192", 192), ("opus128", 128), ("opus96", 96)]
    };
    candidates
        .iter()
        .find(|(_, kbps)| *kbps <= max)
        .map(|(name, _)| *name)
        // Потолок ниже самого экономного профиля - всё равно отдаём самый экономный:
        // это ближе к просьбе клиента, чем полноразмерный оригинал.
        .or_else(|| candidates.last().map(|(name, _)| *name))
}

async fn dispatch(
    State(st): State<Shared>,
    Path(action): Path<String>,
    Query(params): Query<HashMap<String, String>>,
    req: Request,
) -> Response {
    let action = action.trim_end_matches(".view").to_string();

    // Аутентификация - до всего остального, включая ping: так требует протокол,
    // и клиенты именно ping'ом проверяют введённые логин с паролем.
    let user_id = {
        let db = st.db.lock().unwrap();
        match authenticate(&db, &params) {
            Ok(id) => id,
            Err((code, msg)) => return fail(&params, code, msg),
        }
    };
    let ident = Ident { user_id: Some(user_id), device_id: None };

    // Поток - единственное действие, которое отдаёт не XML/JSON, а файл.
    if action == "stream" || action == "download" {
        let Some(id) = params.get("id").and_then(|v| v.parse::<i64>().ok()) else {
            return fail(&params, E_MISSING_PARAM, "нужен параметр id");
        };
        if !crate::users::can_see_track(&st.db.lock().unwrap(), &ident, id) {
            return fail(&params, E_NOT_FOUND, "нет такого трека");
        }
        let profile = if action == "download" { None } else { profile_for(&params) };
        return crate::api::serve_track(st, id, profile, req).await;
    }

    // getCoverArt тоже отдаёт байты. id - номер трека, либо al-/ar- (тогда берём
    // обложку любого видимого трека этого альбома/исполнителя).
    if action == "getCoverArt" {
        let Some(raw) = params.get("id").cloned() else {
            return fail(&params, E_MISSING_PARAM, "нужен параметр id");
        };
        let track_id = {
            let db = st.db.lock().unwrap();
            cover_track_id(&db, &ident, &raw)
        };
        return match track_id {
            Some(tid) => crate::api::serve_artwork(st, tid, &ident).await,
            None => fail(&params, E_NOT_FOUND, "нет обложки"),
        };
    }

    // Звёзды и оценки пишутся в общий state (форма - README «Форма оценок в состоянии»),
    // откуда их подхватывает синхронизация и другие клиенты. Отдельная ветка до общего
    // лока БД: push() требует `&mut Connection`, а дальше лок держится как `&`.
    if matches!(action.as_str(), "star" | "unstar" | "setRating") {
        let Some(track_id) = params.get("id").and_then(|v| v.parse::<i64>().ok()) else {
            return fail(&params, E_MISSING_PARAM, "нужен числовой id трека");
        };
        {
            let db = st.db.lock().unwrap();
            if !crate::users::can_see_track(&db, &ident, track_id) {
                return fail(&params, E_NOT_FOUND, "нет такого трека");
            }
        }
        let (field, value) = match action.as_str() {
            "star" => ("starred", json!(crate::db::now())),
            "unstar" => ("starred", Value::Null),
            _ => {
                let r: i64 = params.get("rating").and_then(|v| v.parse().ok()).unwrap_or(0);
                ("stars", if (1..=5).contains(&r) { json!(r) } else { Value::Null })
            }
        };
        let change = crate::sync::Change {
            entity: "rating".into(),
            id: track_id.to_string(),
            field: field.into(),
            value,
            updated_at: crate::db::now(),
        };
        {
            let mut db = st.db.lock().unwrap();
            if crate::sync::push(&mut db, user_id, std::slice::from_ref(&change)).is_err() {
                return fail(&params, 0, "не удалось сохранить оценку");
            }
        }
        st.notify(json!({ "type": "changed", "user_id": user_id, "entities": ["rating"], "at": crate::db::now() }));
        return respond(&params, json!({}));
    }

    let db = st.db.lock().unwrap();
    let (clause, vis) = crate::users::visibility(&db, &ident);
    let owner = crate::users::get(&db, user_id).map(|u| u.username).unwrap_or_default();

    let body: rusqlite::Result<Value> = match action.as_str() {
        "ping" => Ok(json!({})),
        "getLicense" => Ok(json!({ "license": { "valid": true } })),
        "getMusicFolders" => {
            Ok(json!({ "musicFolders": { "musicFolder": [{ "id": 0, "name": "NAMI" }] } }))
        }
        "getIndexes" | "getArtists" => artists(&db, &clause, &vis, "", vec![]).map(|list| {
            let index = index_of(list);
            let inner = json!({
                "ignoredArticles": IGNORED_ARTICLES,
                "lastModified": crate::db::now() * 1000,
                "index": index,
            });
            if action == "getIndexes" {
                json!({ "indexes": inner })
            } else {
                json!({ "artists": inner })
            }
        }),
        "getArtist" => {
            let Some(name) = params.get("id").and_then(|id| dec_id(ARTIST, id)) else {
                return fail(&params, E_NOT_FOUND, "нет такого исполнителя");
            };
            albums(
                &db,
                &clause,
                &vis,
                &format!("AND {CREDITED} = ?"),
                vec![SqlValue::Text(name.clone())],
                "al",
                500,
                0,
            )
            .map(|album| {
                json!({ "artist": {
                    "id": enc_id(ARTIST, &name),
                    "name": name,
                    "albumCount": album.len(),
                    "album": album,
                }})
            })
        }
        "getAlbum" => {
            let Some((artist, name)) = params
                .get("id")
                .and_then(|id| dec_id(ALBUM, id))
                .and_then(|s| s.split_once(SEP).map(|(a, b)| (a.to_string(), b.to_string())))
            else {
                return fail(&params, E_NOT_FOUND, "нет такого альбома");
            };
            let extra = format!("AND COALESCE(album,'') = ? AND {CREDITED} = ?");
            let ps = vec![SqlValue::Text(name.clone()), SqlValue::Text(artist.clone())];
            match albums(&db, &clause, &vis, &extra, ps.clone(), "al", 1, 0) {
                Ok(mut a) if !a.is_empty() => {
                    songs_where(&db, &clause, &vis, &extra, ps, "track_no, title", 1000).map(|song| {
                        let mut al = a.remove(0);
                        al.as_object_mut().unwrap().insert("song".into(), json!(song));
                        json!({ "album": al })
                    })
                }
                Ok(_) => return fail(&params, E_NOT_FOUND, "нет такого альбома"),
                Err(e) => Err(e),
            }
        }
        "getAlbumList2" | "getAlbumList" => {
            let size = num(&params, "size", 20).clamp(1, 500);
            let offset = num(&params, "offset", 0).max(0);
            let order = match params.get("type").map(String::as_str) {
                Some("newest") => "MAX(mtime) DESC",
                Some("byYear") => "MIN(NULLIF(year,0))",
                Some("random") => "RANDOM()",
                _ => "credited, al",
            };
            albums(&db, &clause, &vis, "", vec![], order, size, offset).map(|album| {
                if action == "getAlbumList2" {
                    json!({ "albumList2": { "album": album } })
                } else {
                    json!({ "albumList": { "album": album } })
                }
            })
        }
        "getSong" => {
            let Some(id) = params.get("id").and_then(|v| v.parse::<i64>().ok()) else {
                return fail(&params, E_MISSING_PARAM, "нужен параметр id");
            };
            match songs_where(&db, &clause, &vis, "AND id = ?", vec![SqlValue::Integer(id)], "id", 1) {
                Ok(mut s) if !s.is_empty() => Ok(json!({ "song": s.remove(0) })),
                Ok(_) => return fail(&params, E_NOT_FOUND, "нет такого трека"),
                Err(e) => Err(e),
            }
        }
        "getStarred" | "getStarred2" => {
            // Звёзды лежат в state: entity='rating', field='starred', value - метка
            // времени (не null/0/false). CAST нужен, потому что id записи там строковый.
            let extra = "AND id IN (SELECT CAST(id AS INTEGER) FROM state
                 WHERE user_id=? AND entity='rating' AND field='starred'
                   AND value NOT IN ('null','0','false'))";
            songs_where(&db, &clause, &vis, extra, vec![SqlValue::Integer(user_id)], "artist, album, track_no", 500)
                .map(|song| {
                    let key = if action == "getStarred2" { "starred2" } else { "starred" };
                    json!({ key: { "song": song } })
                })
        }
        "search3" | "search2" => {
            let q = params.get("query").map(String::as_str).unwrap_or("").trim();
            // Пустой запрос в Subsonic значит "всё" - так его шлют клиенты при первом обходе.
            let like = SqlValue::Text(format!("%{}%", q.replace('%', "\\%").replace('_', "\\_")));
            let songs = songs_where(
                &db,
                &clause,
                &vis,
                "AND (title LIKE ? ESCAPE '\\' OR COALESCE(artist,'') LIKE ? ESCAPE '\\')",
                vec![like.clone(), like.clone()],
                "artist, album, track_no",
                num(&params, "songCount", 20).clamp(0, 500),
            );
            let arts = artists(
                &db,
                &clause,
                &vis,
                &format!("AND {CREDITED} LIKE ? ESCAPE '\\'"),
                vec![like.clone()],
            );
            let als = albums(
                &db,
                &clause,
                &vis,
                "AND COALESCE(album,'') LIKE ? ESCAPE '\\'",
                vec![like],
                "credited, al",
                num(&params, "albumCount", 20).clamp(0, 500),
                0,
            );
            match (arts, als, songs) {
                (Ok(artist), Ok(album), Ok(song)) => {
                    let key = if action == "search3" { "searchResult3" } else { "searchResult2" };
                    Ok(json!({ key: { "artist": artist, "album": album, "song": song } }))
                }
                (Err(e), _, _) | (_, Err(e), _) | (_, _, Err(e)) => Err(e),
            }
        }
        "getPlaylists" => state_records(&db, user_id, "playlist").map(|recs| {
            let list: Vec<Value> = recs
                .iter()
                .map(|(id, f, at)| {
                    let ids = playlist_track_ids(&db, user_id, id).unwrap_or_default();
                    // Треки не собираются целиком: для списка нужны только их число
                    // и суммарная длительность.
                    let stub: Vec<Value> = ids.iter().map(|_| json!({ "duration": 0 })).collect();
                    playlist_json(id, f, *at, &owner, &stub)
                })
                .collect();
            json!({ "playlists": { "playlist": list } })
        }),
        "getPlaylist" => {
            let Some(id) = params.get("id").cloned() else {
                return fail(&params, E_MISSING_PARAM, "нужен параметр id");
            };
            let recs = match state_records(&db, user_id, "playlist") {
                Ok(r) => r,
                Err(e) => return fail(&params, E_NOT_FOUND, &e.to_string()),
            };
            let Some((_, fields, at)) = recs.into_iter().find(|(rid, _, _)| *rid == id) else {
                return fail(&params, E_NOT_FOUND, "нет такого плейлиста");
            };
            let ids = playlist_track_ids(&db, user_id, &id).unwrap_or_default();
            let mut songs = Vec::new();
            for tid in ids {
                if let Ok(mut s) = songs_where(
                    &db,
                    &clause,
                    &vis,
                    "AND id = ?",
                    vec![SqlValue::Integer(tid)],
                    "id",
                    1,
                ) {
                    if !s.is_empty() {
                        songs.push(s.remove(0));
                    }
                }
            }
            let mut pl = playlist_json(&id, &fields, at, &owner, &songs);
            pl.as_object_mut().unwrap().insert("entry".into(), json!(songs));
            Ok(json!({ "playlist": pl }))
        }
        // Клиенты шлют его после каждого трека и ругаются на ошибку. Историю
        // прослушиваний ведёт клиент в своём состоянии (sync.rs), сервер её не выдумывает.
        "scrobble" => {
            // submission=false - это «сейчас играет», без записи в историю.
            let submit = params.get("submission").map(|v| v != "false").unwrap_or(true);
            if submit {
                if let Some(tid) = params.get("id").and_then(|v| v.parse::<i64>().ok()) {
                    if crate::users::can_see_track(&db, &ident, tid) {
                        if let Ok((artist, title, album)) = db.query_row(
                            "SELECT COALESCE(artist,''), title, album FROM tracks WHERE id=?1",
                            [tid],
                            |r| {
                                Ok((
                                    r.get::<_, String>(0)?,
                                    r.get::<_, String>(1)?,
                                    r.get::<_, Option<String>>(2)?,
                                ))
                            },
                        ) {
                            let at = params
                                .get("time")
                                .and_then(|v| v.parse::<i64>().ok())
                                .map(|ms| ms / 1000)
                                .unwrap_or_else(crate::db::now);
                            let _ = crate::scrobble::enqueue(
                                &db,
                                user_id,
                                &artist,
                                &title,
                                album.as_deref(),
                                at,
                            );
                        }
                    }
                }
            }
            Ok(json!({}))
        }
        _ => {
            return fail(
                &params,
                E_NOT_SUPPORTED,
                "это действие OpenSubsonic сервер не поддерживает",
            )
        }
    };

    match body {
        Ok(v) => respond(&params, v),
        Err(e) => {
            tracing::warn!("/rest/{action}: {e}");
            (StatusCode::INTERNAL_SERVER_ERROR, fail(&params, E_NOT_FOUND, "ошибка сервера"))
                .into_response()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn db() -> Connection {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch(crate::db::SCHEMA).unwrap();
        c
    }

    fn params(pairs: &[(&str, &str)]) -> HashMap<String, String> {
        pairs.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect()
    }

    #[test]
    fn токен_subsonic_проверяется_а_неверный_отвергается() {
        let c = db();
        let id = crate::users::create(&c, "вася", "пароль12345", "owner", 0).unwrap();
        c.execute("UPDATE users SET subsonic_password=?2 WHERE id=?1", rusqlite::params![id, "sonic"])
            .unwrap();

        let salt = "abc";
        let good = md5_hex(format!("sonic{salt}").as_bytes());
        assert_eq!(authenticate(&c, &params(&[("u", "вася"), ("t", &good), ("s", salt)])), Ok(id));
        // Тот же токен с другой солью не подходит.
        assert!(authenticate(&c, &params(&[("u", "вася"), ("t", &good), ("s", "другая")])).is_err());
        // Старая форма с паролем в запросе - и открытым, и hex-закодированным.
        assert_eq!(authenticate(&c, &params(&[("u", "вася"), ("p", "sonic")])), Ok(id));
        let enc = format!("enc:{}", hex::encode("sonic"));
        assert_eq!(authenticate(&c, &params(&[("u", "вася"), ("p", &enc)])), Ok(id));
        assert!(authenticate(&c, &params(&[("u", "вася"), ("p", "не тот")])).is_err());
        assert!(authenticate(&c, &params(&[("u", "нет такого"), ("p", "sonic")])).is_err());
    }

    #[test]
    fn без_заданного_subsonic_пароля_доступа_нет() {
        let c = db();
        crate::users::create(&c, "вася", "пароль12345", "owner", 0).unwrap();
        // Основной пароль для /rest/ не годится: он лежит argon2-хешем и md5 из него не собрать.
        assert_eq!(
            authenticate(&c, &params(&[("u", "вася"), ("p", "пароль12345")])),
            Err((E_BAD_CREDENTIALS, "неверный логин или Subsonic-пароль"))
        );
    }

    #[test]
    fn идентификаторы_артистов_и_альбомов_кодируются_обратимо() {
        let id = enc_id(ARTIST, "Кто-то / кто-то");
        assert_eq!(dec_id(ARTIST, &id).as_deref(), Some("Кто-то / кто-то"));
        assert_eq!(dec_id(ALBUM, &id), None, "чужой префикс не должен разбираться");
    }

    #[test]
    fn ответ_собирается_и_в_json_и_в_xml() {
        let body = json!({ "artists": { "index": [ { "name": "A", "artist": [
            { "id": "ar-1", "name": "A&B \"кавычки\"" } ] } ] } });

        let js = respond(&params(&[("f", "json")]), body.clone());
        assert_eq!(js.status(), StatusCode::OK);

        let mut out = String::new();
        to_xml("subsonic-response", &json!({ "status": "ok", "x": body }), true, &mut out);
        assert!(out.contains("xmlns=\"http://subsonic.org/restapi\""));
        assert!(out.contains("status=\"ok\""), "скаляр обязан стать атрибутом: {out}");
        assert!(out.contains("<artist id=\"ar-1\""), "массив - повторяющиеся элементы: {out}");
        assert!(out.contains("A&amp;B &quot;кавычки&quot;"), "экранирование: {out}");
    }

    #[test]
    fn трек_превращается_в_child_со_всеми_нужными_полями() {
        let c = db();
        c.execute(
            "INSERT INTO tracks (path, title, artist, album, album_artist, track_no, year,
                                 duration_ms, size_bytes, mtime, format)
             VALUES ('/м/а.flac','Песня','Кто-то','Альбом','Кто-то',3,1997,180000,5000000,1000000000,'Flac')",
            [],
        )
        .unwrap();
        let songs = songs_where(&c, "", &[], "", vec![], "id", 10).unwrap();
        let s = &songs[0];
        assert_eq!(s["title"], "Песня");
        assert_eq!(s["duration"], 180);
        assert_eq!(s["suffix"], "flac");
        assert_eq!(s["contentType"], "audio/flac");
        assert_eq!(s["isDir"], false);
        assert_eq!(s["track"], 3);
        assert_eq!(s["year"], 1997);
        assert_eq!(s["created"], "2001-09-09T01:46:40Z");
        assert_eq!(s["albumId"], enc_id(ALBUM, &format!("Кто-то{SEP}Альбом")));
        assert_eq!(s["artistId"], enc_id(ARTIST, "Кто-то"));
    }

    #[test]
    fn артисты_и_альбомы_группируются_по_тегам() {
        let c = db();
        for (i, (title, artist, album)) in [
            ("а", "Ария", "Альбом 1"),
            ("б", "Ария", "Альбом 1"),
            ("в", "Ария", "Альбом 2"),
            ("г", "Би-2", "Альбом 3"),
        ]
        .iter()
        .enumerate()
        {
            c.execute(
                "INSERT INTO tracks (path, title, artist, album, duration_ms) VALUES (?1,?2,?3,?4,60000)",
                rusqlite::params![format!("/м/{i}.mp3"), title, artist, album],
            )
            .unwrap();
        }
        let arts = artists(&c, "", &[], "", vec![]).unwrap();
        assert_eq!(arts.len(), 2);
        assert_eq!(arts[0]["name"], "Ария");
        assert_eq!(arts[0]["albumCount"], 2);

        let als = albums(&c, "", &[], "", vec![], "credited, al", 100, 0).unwrap();
        assert_eq!(als.len(), 3);
        assert_eq!(als[0]["songCount"], 2);
        assert_eq!(als[0]["duration"], 120);

        let idx = index_of(arts);
        assert_eq!(idx.len(), 2, "разные буквы - разные индексы: {idx:?}");
        assert_eq!(idx[0]["name"], "А");
    }

    #[test]
    fn плейлист_собирается_из_таблицы_состояния() {
        let mut c = db();
        c.execute(
            "INSERT INTO tracks (id, path, title, duration_ms) VALUES (1,'/м/1.mp3','Раз',60000)",
            [],
        )
        .unwrap();
        c.execute(
            "INSERT INTO tracks (id, path, title, duration_ms) VALUES (2,'/м/2.mp3','Два',60000)",
            [],
        )
        .unwrap();
        let t = crate::db::now();
        let ch = |entity: &str, id: &str, field: &str, value: Value| crate::sync::Change {
            entity: entity.into(),
            id: id.into(),
            field: field.into(),
            value,
            updated_at: t,
        };
        crate::sync::push(
            &mut c,
            1,
            &[
                ch("playlist", "p1", "name", json!("Утро")),
                ch("playlist", "p2", "name", json!("Удалённый")),
                ch("playlist", "p2", crate::sync::DELETED, json!(true)),
                ch("playlist_track", "pt1", "playlist_id", json!("p1")),
                ch("playlist_track", "pt1", "track_id", json!("2")),
                ch("playlist_track", "pt1", "position", json!("1")),
                ch("playlist_track", "pt2", "playlist_id", json!("p1")),
                ch("playlist_track", "pt2", "track_id", json!("1")),
                ch("playlist_track", "pt2", "position", json!("0")),
            ],
        )
        .unwrap();

        let recs = state_records(&c, 1, "playlist").unwrap();
        assert_eq!(recs.len(), 1, "удалённый плейлист не должен показываться");
        assert_eq!(field_str(&recs[0].1, "name").as_deref(), Some("Утро"));
        assert!(recs[0].2 >= t, "метка записи должна быть свежей");
        // Порядок - по position, а не по порядку записи.
        assert_eq!(playlist_track_ids(&c, 1, "p1").unwrap(), vec![1, 2]);
        // Чужое состояние в свой плейлист не подмешивается.
        assert!(state_records(&c, 2, "playlist").unwrap().is_empty());
    }

    #[test]
    fn getstarred_берёт_только_треки_с_непустым_starred() {
        let c = db();
        for (id, v) in [("1", "1699999999"), ("2", "null"), ("3", "0"), ("4", "false")] {
            c.execute(
                "INSERT INTO state (user_id,entity,id,field,value,updated_at)
                 VALUES (0,'rating',?1,'starred',?2,1)",
                rusqlite::params![id, v],
            )
            .unwrap();
        }
        let ids: Vec<i64> = c
            .prepare(
                "SELECT CAST(id AS INTEGER) FROM state WHERE user_id=0 AND entity='rating'
                 AND field='starred' AND value NOT IN ('null','0','false') ORDER BY 1",
            )
            .unwrap()
            .query_map([], |r| r.get(0))
            .unwrap()
            .collect::<rusqlite::Result<_>>()
            .unwrap();
        assert_eq!(ids, vec![1], "звезда только у трека 1");
    }

    #[test]
    fn профиль_выбирается_по_потолку_битрейта() {
        assert_eq!(profile_for(&params(&[])), None, "без maxBitRate - оригинал");
        assert_eq!(profile_for(&params(&[("maxBitRate", "0")])), None);
        assert_eq!(profile_for(&params(&[("maxBitRate", "320")])), Some("opus192"));
        assert_eq!(profile_for(&params(&[("maxBitRate", "128")])), Some("opus128"));
        assert_eq!(profile_for(&params(&[("maxBitRate", "64")])), Some("opus96"));
        assert_eq!(
            profile_for(&params(&[("maxBitRate", "256"), ("format", "aac")])),
            Some("aac192")
        );
    }
}
