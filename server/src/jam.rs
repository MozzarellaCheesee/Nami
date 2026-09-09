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
//! ponytail: сессии живут в памяти процесса, в БД их нет. Джем - это то, что происходит
//! прямо сейчас; переживать перезапуск сервера ему незачем, а один процесс - ровно
//! то, подо что писан весь сервер (см. бюджет в main.rs).

use std::collections::HashMap;
use std::sync::Mutex;

use serde::Deserialize;
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
fn library_key(st: &Shared, ident: &Ident) -> i64 {
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
            let mut reg = st.jams.0.lock().unwrap();
            // Заодно выкидываем сессии, из которых все разошлись.
            reg.retain(|_, s| s.tx.receiver_count() > 0);
            let code = new_code();
            let (tx, rx) = broadcast::channel(BUFFER);
            reg.insert(code.clone(), Session { library, queue: Vec::new(), tx });
            m.code = Some(code.clone());
            m.rx = Some(rx);
            Some(serde_json::json!({ "type": "jam_created", "code": code }).to_string())
        }
        "jam_join" => {
            let library = library_key(st, ident);
            let code = msg.code?.trim().to_uppercase();
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
            Some(
                serde_json::json!({ "type": "jam_joined", "code": code, "queue": queue })
                    .to_string(),
            )
        }
        "jam_leave" => {
            m.rx = None;
            m.code = None;
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
