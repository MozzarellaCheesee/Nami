//! Скробблинг с сервера в ListenBrainz.
//!
//! Событие «трек прослушан» присылает клиент (Android через `POST /api/scrobble` или
//! Subsonic-клиент через `/rest/scrobble`) - сервер сам ничего не играет. Событие
//! ложится в `scrobble_queue`, фоновый поток раз в минуту отправляет накопившееся.
//! Офлайн сервера или ListenBrainz переживается очередью и повторами.
//!
//! ponytail: только ListenBrainz - его `submit-listens` это один POST с Bearer-токеном.
//! Last.fm и Maloja требуют session-key и подпись MD5 (отдельный поток авторизации
//! пользователя) - добавить, когда понадобится. Токен - на пользователя
//! (`users.listenbrainz_token`), у каждого свой аккаунт.

use std::time::Duration;

use rusqlite::Connection;

use crate::api::Shared;

/// Сколько раз пытаемся доставить одно прослушивание, прежде чем бросить.
const MAX_ATTEMPTS: i64 = 5;
const FLUSH_EVERY: Duration = Duration::from_secs(60);
const HTTP_TIMEOUT: Duration = Duration::from_secs(10);

/// Кладёт прослушивание в очередь. Пустой исполнитель/название ListenBrainz всё равно
/// отклонит - такое не сохраняем.
pub fn enqueue(
    conn: &Connection,
    user_id: i64,
    artist: &str,
    title: &str,
    album: Option<&str>,
    played_at: i64,
) -> rusqlite::Result<()> {
    if artist.trim().is_empty() || title.trim().is_empty() {
        return Ok(());
    }
    conn.execute(
        "INSERT INTO scrobble_queue (user_id, artist, title, album, played_at)
         VALUES (?1,?2,?3,?4,?5)",
        rusqlite::params![user_id, artist.trim(), title.trim(), album, played_at],
    )?;
    Ok(())
}

/// Фоновый поток отправки. Живёт до конца процесса.
pub fn spawn(state: Shared) {
    std::thread::spawn(move || loop {
        std::thread::sleep(FLUSH_EVERY);
        let conn = state.db.lock().unwrap();
        if let Err(e) = flush(&conn) {
            tracing::warn!("скробблинг: проход очереди не удался: {e}");
        }
    });
}

/// Одна попытка разослать очередь. Берём только тех пользователей, у кого задан токен.
pub fn flush(conn: &Connection) -> rusqlite::Result<()> {
    let rows: Vec<(i64, i64, String, String, Option<String>, i64, i64)> = {
        let mut stmt = conn.prepare(
            "SELECT q.id, q.user_id, q.artist, q.title, q.album, q.played_at, q.attempts
             FROM scrobble_queue q JOIN users u ON u.id = q.user_id
             WHERE q.sent = 0 AND COALESCE(u.listenbrainz_token, '') <> ''
             ORDER BY q.played_at LIMIT 100",
        )?;
        let rows = stmt
            .query_map([], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?, r.get(5)?, r.get(6)?))
            })?
            .collect::<rusqlite::Result<_>>()?;
        rows
    };

    for (id, user_id, artist, title, album, played_at, attempts) in rows {
        let token: String =
            conn.query_row("SELECT listenbrainz_token FROM users WHERE id=?1", [user_id], |r| {
                r.get(0)
            })?;
        if submit(&token, &artist, &title, album.as_deref(), played_at) {
            conn.execute("UPDATE scrobble_queue SET sent=1 WHERE id=?1", [id])?;
        } else if attempts + 1 >= MAX_ATTEMPTS {
            conn.execute("UPDATE scrobble_queue SET sent=1, attempts=attempts+1 WHERE id=?1", [id])?;
            tracing::warn!("скробблинг: прослушивание {id} брошено после {MAX_ATTEMPTS} попыток");
        } else {
            conn.execute("UPDATE scrobble_queue SET attempts=attempts+1 WHERE id=?1", [id])?;
        }
    }
    Ok(())
}

/// Отправляет одно прослушивание в ListenBrainz. true - принято.
fn submit(token: &str, artist: &str, title: &str, album: Option<&str>, played_at: i64) -> bool {
    let mut meta = serde_json::json!({ "artist_name": artist, "track_name": title });
    if let Some(a) = album.filter(|a| !a.trim().is_empty()) {
        meta["release_name"] = serde_json::json!(a);
    }
    let body = serde_json::json!({
        "listen_type": "single",
        "payload": [{ "listened_at": played_at, "track_metadata": meta }],
    });
    ureq::post("https://api.listenbrainz.org/1/submit-listens")
        .config()
        .timeout_global(Some(HTTP_TIMEOUT))
        .build()
        .header("Authorization", format!("Token {token}"))
        .header("Content-Type", "application/json")
        .send(body.to_string().as_bytes())
        .map(|r| r.status().is_success())
        .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn db() -> Connection {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch(crate::db::SCHEMA).unwrap();
        c
    }

    #[test]
    fn очередь_копит_и_пропускает_пустые() {
        let c = db();
        enqueue(&c, 1, "Boris", "Farewell", Some("Pink"), 1000).unwrap();
        enqueue(&c, 1, "  ", "нет исполнителя", None, 1001).unwrap();
        let n: i64 =
            c.query_row("SELECT COUNT(*) FROM scrobble_queue", [], |r| r.get(0)).unwrap();
        assert_eq!(n, 1, "пустой исполнитель в очередь не попадает");
    }

    #[test]
    fn flush_без_токена_ничего_не_трогает() {
        let c = db();
        c.execute(
            "INSERT INTO users (username, password_hash, role, created_at) VALUES ('u','h','user',0)",
            [],
        )
        .unwrap();
        let uid = c.last_insert_rowid();
        enqueue(&c, uid, "A", "B", None, 1).unwrap();
        flush(&c).unwrap();
        let sent: i64 = c
            .query_row("SELECT sent FROM scrobble_queue WHERE user_id=?1", [uid], |r| r.get(0))
            .unwrap();
        assert_eq!(sent, 0, "без listenbrainz_token запись остаётся в очереди");
    }
}
