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
    host_ident: (Option<i64>, Option<i64>),
    /// Общая очередь - её может пополнить любой участник.
    queue: Vec<i64>,
    pub current_track: Option<i64>,
    pub position_ms: i64,
    pub at: i64,
    tx: broadcast::Sender<String>,
}

/// Все живые сессии процесса.
#[derive(Default)]
pub struct Registry(
    Mutex<HashMap<String, Session>>,
    Mutex<HashMap<(Option<i64>, Option<i64>), String>>,
);

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
    /// Проверяет, активна ли сессия с таким кодом в памяти.
    pub fn contains(&self, code: &str) -> bool {
        self.0.lock().unwrap().contains_key(code)
    }

    /// Список кодов всех активных в памяти сессий.
    pub fn active_codes(&self) -> Vec<String> {
        self.0.lock().unwrap().keys().cloned().collect()
    }

    fn register(&self, ident: &Ident, code: &str) {
        self.1.lock().unwrap().insert((ident.user_id, ident.device_id), code.to_string());
    }

    fn unregister(&self, ident: &Ident) {
        self.1.lock().unwrap().remove(&(ident.user_id, ident.device_id));
    }

    pub fn can_access_track(&self, ident: &Ident, track_id: i64) -> bool {
        let code = self.1.lock().unwrap().get(&(ident.user_id, ident.device_id)).cloned();
        let Some(code) = code else { return false };
        self.0.lock().unwrap().get(&code).is_some_and(|s| {
            s.current_track == Some(track_id) || s.queue.contains(&track_id)
        })
    }

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

pub fn cleanup_host(st: &Shared, m: &mut Membership, ident: &Ident) {
    st.jams.unregister(ident);
    if let Some(code) = m.code.take() {
        let db = st.db.lock().unwrap();
        let mut reg = st.jams.0.lock().unwrap();
        let is_host = reg
            .get(&code)
            .is_some_and(|s| s.host_ident == (ident.user_id, ident.device_id));
        if is_host {
            if let Some(s) = reg.remove(&code) {
                let _ = s.tx.send(serde_json::json!({"type": "jam_closed", "reason": "host_left"}).to_string());
                record_end(&db, &code);
            }
        }
        sweep(&mut reg, &db);
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
                reg.insert(
                    code.clone(),
                    Session {
                        host_ident: (ident.user_id, ident.device_id),
                        queue: Vec::new(),
                        current_track: None,
                        position_ms: 0,
                        at: 0,
                        tx,
                    },
                );
                rx
            };
            m.code = Some(code.clone());
            m.rx = Some(rx);
            st.jams.register(ident, &code);
            Some(serde_json::json!({ "type": "jam_created", "code": code }).to_string())
        }
        "jam_join" => {
            let code = msg.code?.trim().to_uppercase();
            let db = st.db.lock().unwrap();
            let mut reg = st.jams.0.lock().unwrap();
            let Some(s) = reg.get_mut(&code) else {
                return err("нет такой сессии");
            };
            // Код комнаты даёт участнику доступ только к текущему треку и очереди.
            m.rx = Some(s.tx.subscribe());
            m.code = Some(code.clone());
            st.jams.register(ident, &code);
            let queue = s.queue.clone();
            let current_track = s.current_track;
            let position_ms = s.position_ms;
            let at = s.at;
            // receiver_count включает только что оформленную подписку.
            let members = s.tx.receiver_count() as i64;
            let _ = db.execute(
                "UPDATE jam_sessions SET peak_members=MAX(peak_members, ?2) WHERE code=?1",
                rusqlite::params![code, members],
            );
            Some(
                serde_json::json!({
                    "type": "jam_joined",
                    "code": code,
                    "queue": queue,
                    "current_track_id": current_track,
                    "position_ms": position_ms,
                    "at": at,
                })
                .to_string(),
            )
        }
        "jam_leave" => {
            st.jams.unregister(ident);
            m.rx = None;
            if let Some(code) = m.code.take() {
                let db = st.db.lock().unwrap();
                let mut reg = st.jams.0.lock().unwrap();
                let is_host = reg
                    .get(&code)
                    .is_some_and(|s| s.host_ident == (ident.user_id, ident.device_id));
                if is_host {
                    if let Some(s) = reg.remove(&code) {
                        let _ = s.tx.send(serde_json::json!({"type": "jam_closed", "reason": "host_left"}).to_string());
                        record_end(&db, &code);
                    }
                }
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
            let at = msg.at.unwrap_or_else(|| crate::db::now() * 1000);
            {
                let mut reg = st.jams.0.lock().unwrap();
                if let Some(s) = reg.get_mut(&code) {
                    s.current_track = Some(track_id);
                    s.position_ms = msg.position_ms;
                    s.at = at;
                }
            }
            broadcast_to(
                st,
                &code,
                serde_json::json!({
                    "type": "jam_play",
                    "code": code,
                    "track_id": track_id,
                    "position_ms": msg.position_ms,
                    "at": at,
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

/// Генерирует адаптивную HTML-страницу для перехода в Jam-сессию из браузера или мессенджера
pub fn page(code: &str, server_base: &str) -> String {
    let clean_code = code.trim().to_uppercase();
    let clean_base = server_base.trim_end_matches('/');
    let deep_link = format!("nami://jam?code={clean_code}&host={clean_base}");
    format!(
        r#"<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>NAMI Jam — {clean_code}</title>
<style>
body {{
  margin: 0;
  padding: 20px;
  background: #121216;
  color: #f5f5f7;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  box-sizing: border-box;
}}
.card {{
  background: #1c1c22;
  border-radius: 24px;
  padding: 36px 28px;
  max-width: 420px;
  width: 100%;
  text-align: center;
  box-shadow: 0 16px 40px rgba(0,0,0,0.6);
  border: 1px solid rgba(255,255,255,0.06);
}}
.badge {{
  display: inline-block;
  background: rgba(229, 83, 61, 0.15);
  color: #e5533d;
  font-weight: 700;
  font-size: 13px;
  letter-spacing: 1px;
  padding: 6px 16px;
  border-radius: 100px;
  margin-bottom: 18px;
}}
h1 {{
  margin: 0 0 10px;
  font-size: 24px;
  font-weight: 700;
}}
.code {{
  font-size: 38px;
  font-weight: 800;
  letter-spacing: 8px;
  color: #ffffff;
  margin: 24px 0;
  font-family: monospace;
}}
p {{
  color: rgba(245,245,247,0.7);
  font-size: 15px;
  line-height: 1.5;
  margin: 0 0 28px;
}}
.btn {{
  display: block;
  width: 100%;
  box-sizing: border-box;
  background: #e5533d;
  color: #ffffff;
  font-size: 16px;
  font-weight: 600;
  padding: 15px;
  border-radius: 14px;
  text-decoration: none;
  margin-bottom: 12px;
  transition: transform 0.1s, opacity 0.1s;
}}
.btn:active {{
  opacity: 0.85;
  transform: scale(0.98);
}}
.btn-sub {{
  display: block;
  width: 100%;
  box-sizing: border-box;
  background: rgba(255,255,255,0.06);
  color: rgba(245,245,247,0.8);
  font-size: 14px;
  font-weight: 500;
  padding: 13px;
  border-radius: 14px;
  text-decoration: none;
}}
</style>
</head>
<body>
<div class="card">
  <div class="badge">NAMI JAM</div>
  <h1>Совместное прослушивание</h1>
  <div class="code">{clean_code}</div>
  <p>Вас пригласили слушать музыку вместе. Нажмите кнопку ниже, чтобы присоединиться к комнате в приложении Nami.</p>
  <a class="btn" href="{deep_link}">Войти в Джем в приложении Nami</a>
  <a class="btn-sub" href="https://github.com/MozzarellaCheesee/Nami/releases/latest">Установить Nami (APK)</a>
</div>
<script>
if (navigator.userAgent.match(/Android/i)) {{
  setTimeout(function() {{
    window.location.href = "{deep_link}";
  }}, 300);
}}
</script>
</body>
</html>"#
    )
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

    #[test]
    fn участник_видит_только_треки_активного_джема() {
        let registry = Registry::default();
        let (tx, _rx) = broadcast::channel(BUFFER);
        registry.0.lock().unwrap().insert(
            "ABC234".into(),
            Session {
                host_ident: (Some(1), None),
                queue: vec![8],
                current_track: Some(7),
                position_ms: 0,
                at: 0,
                tx,
            },
        );
        let ident = Ident { user_id: Some(2), device_id: None };
        registry.register(&ident, "ABC234");

        assert!(registry.can_access_track(&ident, 7));
        assert!(registry.can_access_track(&ident, 8));
        assert!(!registry.can_access_track(&ident, 9));
        registry.unregister(&ident);
        assert!(!registry.can_access_track(&ident, 7));
    }

    #[test]
    fn гостевое_устройство_создаётся_по_актуальной_схеме() {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch(crate::db::SCHEMA).unwrap();
        let token = crate::api::create_jam_guest(&c, "ABC234").unwrap();
        crate::auth::verify(&c, &token).expect("гостевой токен должен работать");
    }

    #[tokio::test]
    async fn гость_получает_трек_хоста_только_пока_жив_джем() {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch(crate::db::SCHEMA).unwrap();
        c.execute("INSERT INTO tracks (id, path, title) VALUES (7, '/music/a.mp3', 'A')", [])
            .unwrap();
        let (events, _) = broadcast::channel(4);
        let (positions, _) = broadcast::channel(4);
        let st = std::sync::Arc::new(crate::api::AppState {
            db: Mutex::new(c),
            cfg: crate::config::Config::default(),
            fingerprint: None,
            rate: crate::auth::RateLimiter::default(),
            qr_challenges: crate::auth::QrChallenges::default(),
            ffmpeg: false,
            fpcalc: false,
            events,
            positions,
            jams: Registry::default(),
            metrics: crate::metrics::Metrics::new(),
        });
        let host = Ident { user_id: Some(1), device_id: Some(999) };
        let mut host_membership = Membership::default();
        let created = handle(&st, &host, &mut host_membership, r#"{"type":"jam_create"}"#).unwrap();
        let code = serde_json::from_str::<serde_json::Value>(&created).unwrap()["code"]
            .as_str().unwrap().to_string();

        let token = crate::api::create_jam_guest(&st.db.lock().unwrap(), &code).unwrap();
        let guest = crate::api::identify(&st.db.lock().unwrap(), &token).unwrap();
        let mut guest_membership = Membership::default();
        handle(
            &st,
            &guest,
            &mut guest_membership,
            &serde_json::json!({"type":"jam_join", "code":code}).to_string(),
        )
        .unwrap();
        let play_reply = handle(
            &st,
            &host,
            &mut host_membership,
            r#"{"type":"jam_play","track_id":7,"position_ms":1000}"#,
        );

        assert!(play_reply.is_none(), "jam_play отклонён: {play_reply:?}");
        assert!(st.jams.1.lock().unwrap().contains_key(&(guest.user_id, guest.device_id)));
        assert!(st.jams.can_access_track(&guest, 7));
        cleanup_host(&st, &mut host_membership, &host);
        assert!(!st.jams.can_access_track(&guest, 7));
        assert!(guest_membership.recv().await.unwrap().contains("jam_play"));
        assert!(guest_membership.recv().await.unwrap().contains("jam_closed"));
    }
}
