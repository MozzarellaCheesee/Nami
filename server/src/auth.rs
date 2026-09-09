use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Mutex;

use rusqlite::Connection;
use sha2::{Digest, Sha256};

use crate::db::now;

/// Сколько живёт одноразовый код сопряжения.
pub const CODE_TTL_SECS: i64 = 600;
/// Не более стольких попыток обмена кода с одного IP за окно.
pub const RATE_LIMIT_ATTEMPTS: usize = 10;
pub const RATE_LIMIT_WINDOW_SECS: i64 = 60;

/// Криптостойкие случайные байты в hex. Источник - getrandom (системный CSPRNG),
/// отдельный PRNG-крейт ради этого не нужен.
fn random_hex(bytes: usize) -> String {
    let mut buf = vec![0u8; bytes];
    getrandom::fill(&mut buf).expect("системный источник случайности недоступен");
    hex::encode(buf)
}

/// В БД лежит только хеш токена - см. комментарий к схеме devices.
pub fn hash_token(token: &str) -> String {
    hex::encode(Sha256::digest(token.as_bytes()))
}

/// Создаёт одноразовый код сопряжения на 10 минут.
///
/// Код числовой (8 цифр) - его вводят руками, когда камера не сработала.
/// `user_id` - кому будет принадлежать сопряжённое устройство (None в одиночном режиме).
pub fn create_code(conn: &Connection, user_id: Option<i64>) -> crate::Res<String> {
    // Заодно чистим протухшие: отдельного сборщика мусора здесь не нужно.
    conn.execute("DELETE FROM pairing_codes WHERE expires_at < ?1", [now()])?;

    let mut raw = [0u8; 4];
    getrandom::fill(&mut raw)?;
    let code = format!("{:08}", u32::from_le_bytes(raw) % 100_000_000);
    let t = now();
    conn.execute(
        "INSERT OR REPLACE INTO pairing_codes (code, created_at, expires_at, used_at, user_id)
         VALUES (?1, ?2, ?3, NULL, ?4)",
        rusqlite::params![code, t, t + CODE_TTL_SECS, user_id],
    )?;
    Ok(code)
}

#[derive(Debug, PartialEq)]
pub enum PairError {
    /// Неверный, просроченный или уже использованный код - клиенту отвечаем одинаково,
    /// чтобы перебор не отличал "нет такого" от "уже использован".
    BadCode,
}

/// Обменивает код на постоянный токен устройства. Код сгорает в той же транзакции,
/// что и создание устройства - параллельные попытки не могут обменять его дважды.
pub fn pair(conn: &mut Connection, code: &str, device_name: &str) -> Result<String, PairError> {
    let t = now();
    let tx = conn.transaction().map_err(|_| PairError::BadCode)?;
    let owner: Option<i64> = tx
        .query_row("SELECT user_id FROM pairing_codes WHERE code = ?1", [code], |r| r.get(0))
        .ok()
        .flatten();
    let burned = tx
        .execute(
            "UPDATE pairing_codes SET used_at = ?2
             WHERE code = ?1 AND used_at IS NULL AND expires_at >= ?2",
            rusqlite::params![code, t],
        )
        .map_err(|_| PairError::BadCode)?;
    if burned != 1 {
        return Err(PairError::BadCode);
    }

    let token = random_hex(32);
    let name = if device_name.trim().is_empty() {
        "устройство"
    } else {
        device_name.trim()
    };
    tx.execute(
        "INSERT INTO devices (name, token_hash, created_at, user_id) VALUES (?1, ?2, ?3, ?4)",
        rusqlite::params![name, hash_token(&token), t, owner],
    )
    .map_err(|_| PairError::BadCode)?;
    tx.commit().map_err(|_| PairError::BadCode)?;
    Ok(token)
}

/// Проверяет Bearer-токен. Возвращает id устройства.
///
/// Токен opaque, а не JWT: отзыв - это DELETE строки, без blacklist и без ротации ключей.
/// Тот же механизм позже выдаст короткоживущий credential для TURN - это будет
/// производная от строки devices, а не отдельная система аутентификации.
pub fn verify(conn: &Connection, token: &str) -> Option<i64> {
    let hash = hash_token(token);
    let id: Option<i64> = conn
        .query_row("SELECT id FROM devices WHERE token_hash = ?1", [&hash], |r| r.get(0))
        .ok();
    if let Some(id) = id {
        let _ = conn.execute("UPDATE devices SET last_seen_at = ?2 WHERE id = ?1", rusqlite::params![id, now()]);
    }
    id
}

/// Ограничитель частоты попыток сопряжения по IP.
///
/// ponytail: карта в памяти, чистится лениво при обращении. Переезжать на общий
/// стор имеет смысл только когда серверов станет больше одного.
#[derive(Default)]
pub struct RateLimiter {
    hits: Mutex<HashMap<IpAddr, Vec<i64>>>,
}

impl RateLimiter {
    /// true - попытку можно засчитать, false - лимит исчерпан.
    pub fn allow(&self, ip: IpAddr) -> bool {
        let t = now();
        let mut map = self.hits.lock().unwrap();
        // Пока держим лок - выкидываем всех, чьё окно давно истекло.
        map.retain(|_, v| v.iter().any(|&x| t - x < RATE_LIMIT_WINDOW_SECS));
        let v = map.entry(ip).or_default();
        v.retain(|&x| t - x < RATE_LIMIT_WINDOW_SECS);
        if v.len() >= RATE_LIMIT_ATTEMPTS {
            return false;
        }
        v.push(t);
        true
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

    #[test]
    fn код_обменивается_на_рабочий_токен() {
        let mut c = db();
        let code = create_code(&c, None).unwrap();
        let token = pair(&mut c, &code, "Pixel").unwrap();
        assert!(verify(&c, &token).is_some());
        assert!(verify(&c, "мусор").is_none());
    }

    #[test]
    fn код_сгорает_после_использования() {
        let mut c = db();
        let code = create_code(&c, None).unwrap();
        pair(&mut c, &code, "первое").unwrap();
        assert_eq!(pair(&mut c, &code, "второе"), Err(PairError::BadCode));
    }

    #[test]
    fn просроченный_код_не_принимается() {
        let mut c = db();
        let code = create_code(&c, None).unwrap();
        // Отматываем срок годности назад - тест не должен ждать 10 минут.
        c.execute(
            "UPDATE pairing_codes SET expires_at = ?2 WHERE code = ?1",
            rusqlite::params![code, now() - 1],
        )
        .unwrap();
        assert_eq!(pair(&mut c, &code, "поздно"), Err(PairError::BadCode));
    }

    #[test]
    fn отзыв_токена_закрывает_доступ() {
        let mut c = db();
        let code = create_code(&c, None).unwrap();
        let token = pair(&mut c, &code, "Pixel").unwrap();
        let id = verify(&c, &token).unwrap();
        c.execute("DELETE FROM devices WHERE id = ?1", [id]).unwrap();
        assert!(verify(&c, &token).is_none());
    }

    #[test]
    fn rate_limit_срабатывает() {
        let rl = RateLimiter::default();
        let ip: IpAddr = "192.168.1.5".parse().unwrap();
        for _ in 0..RATE_LIMIT_ATTEMPTS {
            assert!(rl.allow(ip));
        }
        assert!(!rl.allow(ip), "лимит должен был сработать");
        // Другой IP лимитом соседа не задет.
        assert!(rl.allow("192.168.1.6".parse().unwrap()));
    }
}
