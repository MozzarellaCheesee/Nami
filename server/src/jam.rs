//! Джем: совместное прослушивание через сервер.
//!
//! Принципиально отличается от P2P-раздачи в Android-приложении: сервер НЕ раздаёт
//! байты трека от одного участника другому. Он дирижирует таймингом - рассылает
//! "сейчас играет трек X с позиции Y на момент времени T", а каждый клиент качает
//! этот трек сам, обычным `/api/tracks/{id}/stream` из общей библиотеки сервера.
//! Поэтому никакой новой инфраструктуры раздачи файла здесь нет - только координация.
//!
//! Канал тот же, что у синхронизации (`/api/ws`): второй сокет ради джема не заводится.
//!
//! ponytail: живая сессия - это broadcast-канал в памяти процесса, перезапуск она не
//! переживает (клиенты всё равно рвут сокет, проще создать джем заново). В БД
//! (`jam_sessions`) ведётся только ЖУРНАЛ: код, библиотека, когда началась и кончилась,
//! сколько треков прозвучало, сколько было участников в пике. Один процесс - ровно то,
//! подо что писан весь сервер (см. бюджет в main.rs).

use std::collections::HashMap;
use std::sync::Mutex;

use rusqlite::Connection;
use serde::{Deserialize, Serialize};
use tokio::sync::broadcast;

use crate::api::Shared;
use crate::users::{self, Ident};

/// Сколько событий держит буфер сессии. Больше не нужно: отставший участник должен
/// получить ТЕКУЩЕЕ состояние, а не доигрывать пропущенное.
const BUFFER: usize = 16;

pub struct Session {
    /// Ключ библиотеки: участвовать можно только внутри одной (см. `library_key`).
    library: i64,
    /// Общая очередь - её может пополнить любой участник.
    queue: Vec<i64>,
    tx: broadcast::Sender<String>,
}

/// Все живые сессии процесса.
#[derive(Default)]
pub struct Registry(Mutex<HashMap<String, Session>>);

/// Одна запись журнала джемов.
#[derive(Serialize)]
pub struct JamRecord {
    pub code: String,
    pub created_at: i64,
    pub ended_at: Option<i64>,
    pub tracks_played: i64,
    pub peak_members: i64,
}

impl Registry {
    /// Журнал джемов библиотеки, свежие сверху.
    pub fn history(conn: &Connection, library_id: i64, limit: i64) -> rusqlite::Result<Vec<JamRecord>> {
        let mut stmt = conn.prepare(
            "SELECT code, created_at, ended_at, tracks_played, peak_members FROM jam_sessions
             WHERE library_id=?1 ORDER BY created_at DESC LIMIT ?2",
        )?;
        let rows = stmt
            .query_map(rusqlite::params![library_id, limit], |r| {
                Ok(JamRecord {
                    code: r.get(0)?,
                    created_at: r.get(1)?,
                    ended_at: r.get(2)?,
                    tracks_played: r.get(3)?,
                    peak_members: r.get(4)?,
                })
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        Ok(rows)
    }
}

/// Отмечает сессию завершённой в журнале (идемпотентно).
fn record_end(conn: &Connection, code: &str) {
    let _ = conn.execute(
        "UPDATE jam_sessions SET ended_at=?2 WHERE code=?1 AND ended_at IS NULL",
        rusqlite::params![code, crate::db::now()],
    );
}

/// Убирает сессии, из которых все разошлись, и помечает их завершёнными в журнале.
fn sweep(reg: &mut HashMap<String, Session>, conn: &Connection) {
    reg.retain(|code, s| {
        let alive = s.tx.receiver_count() > 0;
        if !alive {
            record_end(conn, code);
        }
        alive
    });
}

/// Состояние одного WebSocket-подключения относительно джема.
#[derive(Default)]
pub struct Membership {
    pub code: Option<String>,
    rx: Option<broadcast::Receiver<String>>,
}

impl Membership {
    /// Следующее событие сессии. Вне сессии - вечное ожидание: так эту ветку
    /// можно безусловно класть в `tokio::select!`.
    pub async fn recv(&mut self) -> Result<String, broadcast::error::RecvError> {
        match self.rx.as_mut() {
            Some(rx) => rx.recv().await,
            None => std::future::pending().await,
        }
    }
}

/// Ключ библиотеки участника. В режиме раздельных библиотек это её id, в общем
/// режиме - 0 (библиотека одна на всех). Ровно это и делает раздельные библиотеки
/// закрытыми друг для друга, отдельной ветки под режим не нужно.
pub fn library_key(st: &Shared, ident: &Ident) -> i64 {
    let db = st.db.lock().unwrap();
    match users::library_mode(&db) {
        users::LibraryMode::Separate => ident
            .user_id
            .and_then(|id| users::get(&db, id).map(|u| u.library_id))
            .unwrap_or(0),
        users::LibraryMode::Shared => 0,
    }
}

/// Код сессии: 6 символов без похожих друг на друга (0/O, 1/I) - его диктуют голосом.
fn new_code() -> String {
    const ALPHABET: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    let mut raw = [0u8; 6];
    getrandom::fill(&mut raw).expect("системный источник случайности недоступен");
    raw.iter().map(|b| ALPHABET[*b as usize % ALPHABET.len()] as char).collect()
}

fn err(text: &str) -> Option<String> {
    Some(serde_json::json!({ "type": "jam_error", "error": text }).to_string())
}

#[derive(Deserialize)]
struct Incoming {
    #[serde(rename = "type")]
    kind: String,
    code: Option<String>,
    track_id: Option<i64>,
    #[serde(default)]
    position_ms: i64,
    /// Метка времени клиента (мс) - клиенты по ней компенсируют задержку сети.
    at: Option<i64>,
}

/// Разбирает сообщение клиента. Возвращает ответ лично этому подключению
/// (события всем участникам уходят через broadcast сессии).
///
/// Не async: вся работа - это лок хеш-карты и `broadcast::send`, оба не ждут.
pub fn handle(st: &Shared, ident: &Ident, m: &mut Membership, text: &str) -> Option<String> {
    let msg: Incoming = serde_json::from_str(text).ok()?;
    match msg.kind.as_str() {
        "jam_create" => {
            let library = library_key(st, ident);
            let now = crate::db::now();
            let code = new_code();
            let rx = {
                let db = st.db.lock().unwrap();
                db.execute(
                    "INSERT INTO jam_sessions (code, library_id, created_at) VALUES (?1,?2,?3)",
                    rusqlite::params![code, library, now],
                )
                .ok();
                let mut reg = st.jams.0.lock().unwrap();
                sweep(&mut reg, &db);
                let (tx, rx) = broadcast::channel(BUFFER);
                reg.insert(code.clone(), Session { library, queue: Vec::new(), tx });
                rx
            };
            m.code = Some(code.clone());
            m.rx = Some(rx);
            Some(serde_json::json!({ "type": "jam_created", "code": code }).to_string())
        }
        "jam_join" => {
            let library = library_key(st, ident);
            let code = msg.code?.trim().to_uppercase();
            let db = st.db.lock().unwrap();
            let mut reg = st.jams.0.lock().unwrap();
            let Some(s) = reg.get_mut(&code) else {
                return err("нет такой сессии");
            };
            // Раздельные библиотеки закрыты друг для друга по определению.
            if s.library != library {
                return err("сессия в другой библиотеке");
            }
            m.rx = Some(s.tx.subscribe());
            m.code = Some(code.clone());
            let queue = s.queue.clone();
            // receiver_count включает только что оформленную подписку.
            let members = s.tx.receiver_count() as i64;
            let _ = db.execute(
                "UPDATE jam_sessions SET peak_members=MAX(peak_members, ?2) WHERE code=?1",
                rusqlite::params![code, members],
            );
            Some(
                serde_json::json!({ "type": "jam_joined", "code": code, "queue": queue })
                    .to_string(),
            )
        }
        "jam_leave" => {
            m.rx = None;
            if m.code.take().is_some() {
                let db = st.db.lock().unwrap();
                let mut reg = st.jams.0.lock().unwrap();
                sweep(&mut reg, &db);
            }
            Some(serde_json::json!({ "type": "jam_left" }).to_string())
        }
        "jam_play" => {
            let code = m.code.clone()?;
            let track_id = msg.track_id?;
            // Ставить можно только то, что видно самому. Кому трек не виден, тот
            // просто получит 404 на потоке - проверка видимости живёт там же, где
            // отдача файла, и обходить её джемом нельзя.
            if !users::can_see_track(&st.db.lock().unwrap(), ident, track_id) {
                return err("трек вам не виден");
            }
            broadcast_to(
                st,
                &code,
                serde_json::json!({
                    "type": "jam_play",
                    "code": code,
                    "track_id": track_id,
                    "position_ms": msg.position_ms,
                    "at": msg.at.unwrap_or_else(|| crate::db::now() * 1000),
                    "by": ident.user_id,
                }),
            );
            let _ = st.db.lock().unwrap().execute(
                "UPDATE jam_sessions SET tracks_played=tracks_played+1 WHERE code=?1",
                [&code],
            );
            None
        }
        "jam_queue_add" => {
            let code = m.code.clone()?;
            let track_id = msg.track_id?;
            if !users::can_see_track(&st.db.lock().unwrap(), ident, track_id) {
                return err("трек вам не виден");
            }
            let queue = {
                let mut reg = st.jams.0.lock().unwrap();
                let s = reg.get_mut(&code)?;
                s.queue.push(track_id);
                s.queue.clone()
            };
            let _ = st.db.lock().unwrap().execute(
                "UPDATE jam_sessions SET queue=?2 WHERE code=?1",
                rusqlite::params![code, serde_json::to_string(&queue).unwrap_or_else(|_| "[]".into())],
            );
            broadcast_to(
                st,
                &code,
                serde_json::json!({ "type": "jam_queue", "code": code, "queue": queue }),
            );
            None
        }
        _ => None,
    }
}

fn broadcast_to(st: &Shared, code: &str, ev: serde_json::Value) {
    if let Some(s) = st.jams.0.lock().unwrap().get(code) {
        let _ = s.tx.send(ev.to_string());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn код_читаемый_и_не_повторяется() {
        let a = new_code();
        assert_eq!(a.len(), 6);
        assert!(a.chars().all(|c| c.is_ascii_uppercase() || c.is_ascii_digit()));
        assert!(!a.contains('O') && !a.contains('I'), "похожие символы исключены");
        assert_ne!(a, new_code());
    }

    #[test]
    fn журнал_пишется_и_виден_только_своей_библиотеке() {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch(crate::db::SCHEMA).unwrap();
        c.execute(
            "INSERT INTO jam_sessions (code, library_id, created_at) VALUES ('ABC234', 0, 100)",
            [],
        )
        .unwrap();
        c.execute("UPDATE jam_sessions SET tracks_played=3 WHERE code='ABC234'", []).unwrap();
        record_end(&c, "ABC234");

        let h = Registry::history(&c, 0, 10).unwrap();
        assert_eq!(h.len(), 1);
        assert_eq!(h[0].code, "ABC234");
        assert_eq!(h[0].tracks_played, 3);
        assert!(h[0].ended_at.is_some(), "record_end проставляет метку завершения");
        assert!(Registry::history(&c, 7, 10).unwrap().is_empty(), "чужая библиотека журнал не видит");
    }

    #[test]
    fn сессия_рассылает_событие_всем_участникам() {
        // Сама рассылка - это broadcast-канал; проверяем именно её поведение,
        // не поднимая ради этого HTTP-сервер.
        let (tx, mut a) = broadcast::channel::<String>(BUFFER);
        let mut b = tx.subscribe();
        tx.send("играет трек 7".into()).unwrap();
        assert_eq!(a.try_recv().unwrap(), "играет трек 7");
        assert_eq!(b.try_recv().unwrap(), "играет трек 7");
    }
}
