//! Гостевые ссылки: послушать трек или плейлист без приложения и без логина.
//!
//! Ссылка - это токен, у которого свой срок жизни и свой счётчик прослушиваний.
//! Проверка честная, на КАЖДЫЙ запрос: и на открытие страницы, и на каждый поток -
//! иначе вкладка, открытая до истечения, играла бы вечно.
//!
//! # Почему состав ссылки фиксируется при создании
//!
//! Плейлисты живут в таблице `state` непрозрачным JSON: сервер намеренно не понимает
//! их семантику (см. doc-комментарий sync.rs). Поэтому "поделиться плейлистом" - это
//! список id треков, который присылает тот, кто делится. Ссылка не меняется вслед за
//! плейлистом, и это правильно: гость получил ровно то, чем с ним поделились.

use rusqlite::Connection;
use serde::{Deserialize, Serialize};

use crate::auth::hash_token;
use crate::db::now;
use crate::users::random_token;

/// Что отдаётся при создании ссылки.
#[derive(Debug, Serialize)]
pub struct Share {
    pub token: String,
    pub url: String,
    pub title: String,
    pub track_ids: Vec<i64>,
    pub expires_at: Option<i64>,
    pub max_plays: Option<i64>,
}

#[derive(Debug, Deserialize)]
pub struct NewShare {
    #[serde(default)]
    pub title: String,
    /// Один трек или целый плейлист - одна и та же форма, разных сущностей не заводим.
    pub track_ids: Vec<i64>,
    /// Сколько секунд жить ссылке. None - бессрочно.
    pub ttl_secs: Option<i64>,
    /// Сколько прослушиваний разрешено. None - без ограничения.
    pub max_plays: Option<i64>,
}

pub fn create(conn: &Connection, created_by: Option<i64>, req: &NewShare) -> rusqlite::Result<Share> {
    let t = now();
    let token = random_token();
    let title = if req.title.trim().is_empty() { "Треки из NAMI" } else { req.title.trim() };
    let expires_at = req.ttl_secs.map(|s| t + s.max(60));
    let ids = serde_json::to_string(&req.track_ids).unwrap_or_else(|_| "[]".into());
    conn.execute(
        "INSERT INTO shares (token_hash, title, track_ids, created_by, created_at,
                             expires_at, max_plays)
         VALUES (?1,?2,?3,?4,?5,?6,?7)",
        rusqlite::params![
            hash_token(&token),
            title,
            ids,
            created_by,
            t,
            expires_at,
            req.max_plays
        ],
    )?;
    Ok(Share {
        url: format!("/share/{token}"),
        token,
        title: title.into(),
        track_ids: req.track_ids.clone(),
        expires_at,
        max_plays: req.max_plays,
    })
}

/// Живая гостевая ссылка.
#[derive(Debug)]
pub struct Live {
    pub title: String,
    pub track_ids: Vec<i64>,
    pub plays_left: Option<i64>,
}

/// Находит ссылку и проверяет, что она ещё действует. Возвращает None и когда токена
/// нет, и когда он протух: гостю разница не сообщается.
pub fn lookup(conn: &Connection, token: &str) -> Option<Live> {
    let (title, ids, expires_at, max_plays, play_count): (
        String,
        String,
        Option<i64>,
        Option<i64>,
        i64,
    ) = conn
        .query_row(
            "SELECT title, track_ids, expires_at, max_plays, play_count
             FROM shares WHERE token_hash=?1",
            [hash_token(token)],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
        )
        .ok()?;
    if expires_at.is_some_and(|e| e < now()) {
        return None;
    }
    let plays_left = max_plays.map(|m| m - play_count);
    if plays_left.is_some_and(|l| l <= 0) {
        return None;
    }
    Some(Live {
        title,
        track_ids: serde_json::from_str(&ids).unwrap_or_default(),
        plays_left,
    })
}

/// Засчитывает прослушивание. Инкремент условный (`play_count < max_plays`), поэтому
/// два параллельных запроса не могут перескочить лимит: второй просто не пройдёт.
///
/// ponytail: счётчик растёт на КАЖДЫЙ запрос потока без Range - то есть на старт
/// воспроизведения. Перемотка (Range с ненулевым началом) не считается, повторное
/// открытие страницы - считается. Точнее (доиграл ли до конца) сервер не узнает,
/// не получая от гостя телеметрию, а её у гостевой ссылки нет по определению.
pub fn count_play(conn: &Connection, token: &str) -> bool {
    conn.execute(
        "UPDATE shares SET play_count = play_count + 1
         WHERE token_hash = ?1 AND (max_plays IS NULL OR play_count < max_plays)
           AND (expires_at IS NULL OR expires_at >= ?2)",
        rusqlite::params![hash_token(token), now()],
    )
    .unwrap_or(0)
        == 1
}

/// Отзыв ссылки. Проверка владельца живёт здесь, а не у вызывающего: токен - единственное,
/// что нужно знать для отзыва, так что без сверки `created_by` любой аутентифицированный
/// пользователь гасил бы чужую ссылку, подсмотрев токен.
///
/// `created_by IS ?2`, а не `= ?2`: в режиме без пользователей `created_by` и `requester` оба
/// NULL, и обычное сравнение там никогда не совпало бы.
pub fn revoke(
    conn: &Connection,
    token: &str,
    requester: Option<i64>,
    is_owner: bool,
) -> rusqlite::Result<usize> {
    if is_owner {
        return conn.execute("DELETE FROM shares WHERE token_hash=?1", [hash_token(token)]);
    }
    conn.execute(
        "DELETE FROM shares WHERE token_hash=?1 AND created_by IS ?2",
        rusqlite::params![hash_token(token), requester],
    )
}

/// Экранирование под HTML-текст: заголовок ссылки и теги приходят от пользователя.
fn esc(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

/// Минимальная страница гостя: список треков и по `<audio>` на каждый.
///
/// Никакого JS и никаких внешних ресурсов: страницу открывают из мессенджера,
/// на чужом телефоне, иногда без интернета кроме самого сервера.
pub fn page(conn: &Connection, token: &str, live: &Live) -> String {
    let mut items = String::new();
    for id in &live.track_ids {
        let (title, artist): (String, Option<String>) = conn
            .query_row("SELECT title, artist FROM tracks WHERE id=?1", [id], |r| {
                Ok((r.get(0)?, r.get(1)?))
            })
            .unwrap_or_else(|_| (format!("трек {id}"), None));
        let artist = artist.map(|a| format!(" - {}", esc(&a))).unwrap_or_default();
        items.push_str(&format!(
            "<li><div class=t>{}{artist}</div>\
             <audio controls preload=none src=\"/share/{token}/stream/{id}\"></audio></li>",
            esc(&title)
        ));
    }
    let limit = match live.plays_left {
        Some(n) => format!("<p class=note>Осталось прослушиваний: {n}</p>"),
        None => String::new(),
    };
    format!(
        "<!doctype html><meta charset=utf-8><meta name=viewport \
         content=\"width=device-width,initial-scale=1\"><title>{}</title>\
         <style>body{{font:16px system-ui;max-width:640px;margin:32px auto;padding:0 16px}}\
         ul{{list-style:none;padding:0}}li{{margin:0 0 20px}}audio{{width:100%}}\
         .t{{margin-bottom:6px}}.note{{color:#666;font-size:14px}}</style>\
         <h1>{}</h1><ul>{items}</ul>{limit}\
         <p class=note>Ссылка выдана из NAMI и может перестать работать.</p>",
        esc(&live.title),
        esc(&live.title)
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn db() -> Connection {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch(crate::db::SCHEMA).unwrap();
        c.execute("INSERT INTO tracks (id, path, title) VALUES (1,'/a.mp3','Трек')", []).unwrap();
        c
    }

    fn req(ttl: Option<i64>, max: Option<i64>) -> NewShare {
        NewShare { title: "Моё".into(), track_ids: vec![1], ttl_secs: ttl, max_plays: max }
    }

    #[test]
    fn ссылка_работает_и_отзывается() {
        let c = db();
        let s = create(&c, Some(1), &req(None, None)).unwrap();
        assert_eq!(lookup(&c, &s.token).unwrap().track_ids, vec![1]);
        assert!(page(&c, &s.token, &lookup(&c, &s.token).unwrap()).contains("Трек"));
        revoke(&c, &s.token, Some(1), false).unwrap();
        assert!(lookup(&c, &s.token).is_none());
    }

    #[test]
    fn чужую_ссылку_не_отозвать() {
        let c = db();
        let s = create(&c, Some(1), &req(None, None)).unwrap();

        // Пользователь 2 знает токен, но ссылка не его - отзыв не проходит.
        assert_eq!(revoke(&c, &s.token, Some(2), false).unwrap(), 0);
        assert!(lookup(&c, &s.token).is_some());

        // Владелец сервера гасит любую.
        assert_eq!(revoke(&c, &s.token, Some(2), true).unwrap(), 1);
        assert!(lookup(&c, &s.token).is_none());
    }

    #[test]
    fn без_пользователей_отзыв_своей_ссылки_работает() {
        let c = db();
        // Режим без учётных записей: created_by и requester оба NULL, сравнение должно совпасть.
        let s = create(&c, None, &req(None, None)).unwrap();
        assert_eq!(revoke(&c, &s.token, None, false).unwrap(), 1);
        assert!(lookup(&c, &s.token).is_none());
    }

    #[test]
    fn ссылка_истекает_по_времени() {
        let c = db();
        let s = create(&c, Some(1), &req(Some(3600), None)).unwrap();
        assert!(lookup(&c, &s.token).is_some());
        c.execute(
            "UPDATE shares SET expires_at=?1 WHERE token_hash=?2",
            rusqlite::params![now() - 1, hash_token(&s.token)],
        )
        .unwrap();
        assert!(lookup(&c, &s.token).is_none(), "просроченная ссылка не должна открываться");
        assert!(!count_play(&c, &s.token), "и поток по ней тоже");
    }

    #[test]
    fn ссылка_истекает_по_счётчику() {
        let c = db();
        let s = create(&c, Some(1), &req(None, Some(2))).unwrap();
        assert_eq!(lookup(&c, &s.token).unwrap().plays_left, Some(2));
        assert!(count_play(&c, &s.token));
        assert!(count_play(&c, &s.token));
        assert!(!count_play(&c, &s.token), "третье прослушивание сверх лимита");
        assert!(lookup(&c, &s.token).is_none(), "исчерпанная ссылка не открывается");
    }

    #[test]
    fn заголовок_не_протаскивает_html() {
        let c = db();
        let mut r = req(None, None);
        r.title = "<script>alert(1)</script>".into();
        let s = create(&c, Some(1), &r).unwrap();
        let html = page(&c, &s.token, &lookup(&c, &s.token).unwrap());
        assert!(!html.contains("<script>"), "заголовок обязан экранироваться");
    }
}
