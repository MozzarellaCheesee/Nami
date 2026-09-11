//! Многопользовательность: люди, инвайты, сессии входа и видимость библиотеки.
//!
//! Не путать с `auth`: там сопряжение УСТРОЙСТВА с сервером (постоянный токен железки),
//! здесь - вход ЧЕЛОВЕКА по паролю. Оба вида токенов проверяются одной серединной
//! прослойкой в api.rs и оба сводятся к одному `Ident`.
//!
//! # Два режима библиотеки
//!
//! План требует ровно двух, и они несовместимы по данным:
//! - `shared` - одна библиотека, доступ ограничивается поддеревьями папок
//!   (`user_folder_access`); нет строк - пользователь видит всё;
//! - `separate` - у каждого пользователя своя `library_id`, треки помечены той же
//!   меткой, сканер обходит папки каждой библиотеки отдельно.
//!
//! Смена режима - административная ручка, которая ЧИСТИТ tracks и требует пересканирования:
//! автоматической миграции данных между режимами нет намеренно (так требует план -
//! "пересоздание с нуля при смене режима"), потому что осмысленного отображения
//! "папки -> библиотеки" не существует.

use rusqlite::types::Value;
use rusqlite::Connection;
use serde::{Deserialize, Serialize};

use crate::auth::hash_token;
use crate::db::{now, setting};

/// Сколько живёт сессия входа.
pub const SESSION_TTL_SECS: i64 = 30 * 24 * 3600;
/// Сколько живёт инвайт по умолчанию.
pub const INVITE_TTL_SECS: i64 = 7 * 24 * 3600;

/// Кто выполняет запрос. Оба поля опциональны: вход возможен и токеном устройства,
/// и сессией человека, а устройства этапов 1-2 вообще не привязаны к пользователю.
#[derive(Debug, Clone, Copy, Default)]
pub struct Ident {
    pub user_id: Option<i64>,
    pub device_id: Option<i64>,
}

impl Ident {
    /// Ключ для таблиц, где состояние раздельное по людям. 0 - одиночный режим.
    pub fn state_key(&self) -> i64 {
        self.user_id.unwrap_or(0)
    }
}

/// Случайный токен в hex.
pub fn random_token() -> String {
    let mut buf = [0u8; 32];
    getrandom::fill(&mut buf).expect("системный источник случайности недоступен");
    hex::encode(buf)
}

// ---------------------------------------------------------------- пароли

/// argon2id с параметрами по умолчанию крейта argon2 (19 МиБ памяти на проверку -
/// влезает в бюджет "512 МБ", логин редкий).
pub fn hash_password(password: &str) -> crate::Res<String> {
    use argon2::password_hash::{PasswordHasher, SaltString};
    let mut salt = [0u8; 16];
    getrandom::fill(&mut salt)?;
    let salt = SaltString::encode_b64(&salt).map_err(|e| e.to_string())?;
    Ok(argon2::Argon2::default()
        .hash_password(password.as_bytes(), &salt)
        .map_err(|e| e.to_string())?
        .to_string())
}

pub fn verify_password(password: &str, hash: &str) -> bool {
    use argon2::password_hash::{PasswordHash, PasswordVerifier};
    PasswordHash::new(hash).is_ok_and(|h| {
        argon2::Argon2::default().verify_password(password.as_bytes(), &h).is_ok()
    })
}

// ---------------------------------------------------------------- пользователи

#[derive(Debug, Serialize)]
pub struct User {
    pub id: i64,
    pub username: String,
    pub role: String,
    pub library_id: i64,
    pub now_playing_visible: bool,
    pub created_at: i64,
    pub has_subsonic: bool,
}

#[derive(Debug, PartialEq)]
pub enum UserError {
    /// Такое имя уже занято.
    Taken,
    /// Пустое имя или слишком короткий пароль.
    BadInput,
    /// Инвайт неверен, просрочен или уже использован.
    BadInvite,
    /// Неверная пара логин/пароль.
    BadCredentials,
    Db(String),
}

impl std::fmt::Display for UserError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            UserError::Taken => "такое имя пользователя уже занято",
            UserError::BadInput => "имя не должно быть пустым, пароль - от 8 символов",
            UserError::BadInvite => "инвайт неверен, просрочен или уже использован",
            UserError::BadCredentials => "неверный логин или пароль",
            UserError::Db(e) => e,
        };
        f.write_str(s)
    }
}

impl From<rusqlite::Error> for UserError {
    fn from(e: rusqlite::Error) -> Self {
        UserError::Db(e.to_string())
    }
}

/// Сколько всего пользователей заведено. 0 - сервер ещё в одиночном режиме,
/// первая регистрация создаёт владельца без инвайта.
pub fn count(conn: &Connection) -> i64 {
    conn.query_row("SELECT COUNT(*) FROM users", [], |r| r.get(0)).unwrap_or(0)
}

/// Заводит пользователя. Первый становится владельцем.
pub fn create(
    conn: &Connection,
    username: &str,
    password: &str,
    role: &str,
    library_id: i64,
) -> Result<i64, UserError> {
    let username = username.trim();
    // Длина в символах, а не в байтах: кириллический пароль иначе "длиннее", чем есть.
    if username.is_empty() || password.chars().count() < 8 {
        return Err(UserError::BadInput);
    }
    let hash = hash_password(password).map_err(|e| UserError::Db(e.to_string()))?;
    conn.execute(
        "INSERT INTO users (username, password_hash, role, library_id, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5)",
        rusqlite::params![username, hash, role, library_id, now()],
    )
    .map_err(|e| match e {
        rusqlite::Error::SqliteFailure(f, _)
            if f.code == rusqlite::ErrorCode::ConstraintViolation =>
        {
            UserError::Taken
        }
        e => UserError::Db(e.to_string()),
    })?;
    Ok(conn.last_insert_rowid())
}

pub fn get(conn: &Connection, id: i64) -> Option<User> {
    conn.query_row(
        "SELECT id, username, role, library_id, now_playing_visible, created_at,
         (subsonic_password IS NOT NULL AND subsonic_password != '')
         FROM users WHERE id=?1",
        [id],
        row_to_user,
    )
    .ok()
}

fn row_to_user(r: &rusqlite::Row<'_>) -> rusqlite::Result<User> {
    Ok(User {
        id: r.get(0)?,
        username: r.get(1)?,
        role: r.get(2)?,
        library_id: r.get(3)?,
        now_playing_visible: r.get::<_, i64>(4)? != 0,
        created_at: r.get(5)?,
        has_subsonic: r.get::<_, i64>(6)? != 0,
    })
}

pub fn list(conn: &Connection) -> rusqlite::Result<Vec<User>> {
    let mut stmt = conn.prepare(
        "SELECT id, username, role, library_id, now_playing_visible, created_at,
         (subsonic_password IS NOT NULL AND subsonic_password != '')
         FROM users ORDER BY id",
    )?;
    let v = stmt.query_map([], row_to_user)?.collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(v)
}

/// Владелец ли. Всё административное (инвайты, смена режима, чужие настройки) - только ему.
pub fn is_owner(conn: &Connection, id: i64) -> bool {
    conn.query_row("SELECT role FROM users WHERE id=?1", [id], |r| r.get::<_, String>(0))
        .map(|r| r == "owner")
        .unwrap_or(false)
}

// ---------------------------------------------------------------- вход и сессии

/// Логин по паролю. Возвращает сессионный токен (в БД - только его хеш).
pub fn login(conn: &Connection, username: &str, password: &str) -> Result<(i64, String), UserError> {
    let row: Option<(i64, String)> = conn
        .query_row(
            "SELECT id, password_hash FROM users WHERE username=?1",
            [username.trim()],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .ok();
    let Some((id, hash)) = row else {
        // Считаем фиктивный хеш, чтобы по времени ответа не отличали "нет такого
        // пользователя" от "пароль не тот".
        let _ = verify_password(password, "$argon2id$v=19$m=19456,t=2,p=1$c2FsdHNhbHQ$0000000000000000000000000000000000000000000");
        return Err(UserError::BadCredentials);
    };
    if !verify_password(password, &hash) {
        return Err(UserError::BadCredentials);
    }
    Ok((id, start_session(conn, id)?))
}

/// Создаёт сессию, попутно вычищая протухшие.
pub fn start_session(conn: &Connection, user_id: i64) -> Result<String, UserError> {
    let t = now();
    conn.execute("DELETE FROM sessions WHERE expires_at < ?1", [t])?;
    let token = random_token();
    conn.execute(
        "INSERT INTO sessions (token_hash, user_id, created_at, expires_at)
         VALUES (?1, ?2, ?3, ?4)",
        rusqlite::params![hash_token(&token), user_id, t, t + SESSION_TTL_SECS],
    )?;
    Ok(token)
}

/// Проверяет сессионный токен, возвращает id пользователя.
pub fn verify_session(conn: &Connection, token: &str) -> Option<i64> {
    conn.query_row(
        "SELECT user_id FROM sessions WHERE token_hash=?1 AND expires_at >= ?2",
        rusqlite::params![hash_token(token), now()],
        |r| r.get(0),
    )
    .ok()
}

pub fn logout(conn: &Connection, token: &str) -> rusqlite::Result<usize> {
    conn.execute("DELETE FROM sessions WHERE token_hash=?1", [hash_token(token)])
}

/// Смена своего пароля: нужен текущий. Все сессии пользователя гасятся - утёкшая
/// сессия не должна пережить смену пароля. Токены устройств не трогаем: это
/// сопряжённое железо, а не украденный вход.
pub fn change_password(
    conn: &Connection,
    user_id: i64,
    old: &str,
    new: &str,
) -> Result<(), UserError> {
    if new.chars().count() < 8 {
        return Err(UserError::BadInput);
    }
    let hash: String =
        conn.query_row("SELECT password_hash FROM users WHERE id=?1", [user_id], |r| r.get(0))?;
    if !verify_password(old, &hash) {
        return Err(UserError::BadCredentials);
    }
    let new_hash = hash_password(new).map_err(|e| UserError::Db(e.to_string()))?;
    conn.execute(
        "UPDATE users SET password_hash=?2 WHERE id=?1",
        rusqlite::params![user_id, new_hash],
    )?;
    conn.execute("DELETE FROM sessions WHERE user_id=?1", [user_id])?;
    Ok(())
}

/// Сброс пароля владельцем: без текущего. Сессии сбрасываемого пользователя гасятся.
pub fn reset_password(conn: &Connection, target: i64, new: &str) -> Result<(), UserError> {
    if new.chars().count() < 8 {
        return Err(UserError::BadInput);
    }
    let new_hash = hash_password(new).map_err(|e| UserError::Db(e.to_string()))?;
    let n = conn.execute(
        "UPDATE users SET password_hash=?2 WHERE id=?1",
        rusqlite::params![target, new_hash],
    )?;
    if n == 0 {
        return Err(UserError::Db("нет такого пользователя".into()));
    }
    conn.execute("DELETE FROM sessions WHERE user_id=?1", [target])?;
    Ok(())
}

// ---------------------------------------------------------------- инвайты

#[derive(Debug, Serialize)]
pub struct Invite {
    pub token: String,
    pub role: String,
    pub library_id: i64,
    pub expires_at: i64,
}

pub fn create_invite(
    conn: &Connection,
    created_by: i64,
    role: &str,
    library_id: i64,
    ttl_secs: Option<i64>,
) -> rusqlite::Result<Invite> {
    let t = now();
    conn.execute("DELETE FROM invites WHERE expires_at < ?1 AND used_at IS NULL", [t])?;
    let token = random_token();
    let expires_at = t + ttl_secs.unwrap_or(INVITE_TTL_SECS).max(60);
    let role = if matches!(role, "user" | "guest") { role } else { "user" };
    conn.execute(
        "INSERT INTO invites (token_hash, created_by, role, library_id, created_at, expires_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        rusqlite::params![hash_token(&token), created_by, role, library_id, t, expires_at],
    )?;
    Ok(Invite { token, role: role.into(), library_id, expires_at })
}

/// Принимает инвайт: заводит пользователя и гасит инвайт в одной транзакции,
/// поэтому одну ссылку нельзя разменять дважды параллельными запросами.
pub fn accept_invite(
    conn: &mut Connection,
    token: &str,
    username: &str,
    password: &str,
) -> Result<i64, UserError> {
    let t = now();
    let tx = conn.transaction()?;
    let hash = hash_token(token);
    let (role, library_id): (String, i64) = tx
        .query_row(
            "SELECT role, library_id FROM invites
             WHERE token_hash=?1 AND used_at IS NULL AND expires_at >= ?2",
            rusqlite::params![hash, t],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .map_err(|_| UserError::BadInvite)?;
    // Гасим до создания пользователя: гонка проиграет здесь, а не оставит два аккаунта.
    if tx.execute(
        "UPDATE invites SET used_at=?2 WHERE token_hash=?1 AND used_at IS NULL",
        rusqlite::params![hash, t],
    )? != 1
    {
        return Err(UserError::BadInvite);
    }
    let id = create(&tx, username, password, &role, library_id)?;
    tx.execute("UPDATE invites SET used_by=?2 WHERE token_hash=?1", rusqlite::params![hash, id])?;
    tx.commit()?;
    Ok(id)
}

// ---------------------------------------------------------------- видимость библиотеки

/// Режим библиотеки сервера.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum LibraryMode {
    /// Одна библиотека на всех, доступ режется по папкам.
    Shared,
    /// У каждого пользователя своя библиотека.
    Separate,
}

pub fn library_mode(conn: &Connection) -> LibraryMode {
    match setting(conn, "library_mode").as_deref() {
        Some("separate") => LibraryMode::Separate,
        _ => LibraryMode::Shared,
    }
}

/// Меняет режим библиотеки. Треки вычищаются: осмысленного переноса между режимами
/// не существует (в shared трек принадлежит всем и режется папками, в separate - ровно
/// одной библиотеке), поэтому после смены нужен новый скан. Так требует план.
pub fn set_library_mode(conn: &Connection, mode: LibraryMode) -> rusqlite::Result<()> {
    let value = match mode {
        LibraryMode::Shared => "shared",
        LibraryMode::Separate => "separate",
    };
    conn.execute_batch("DELETE FROM tracks")?;
    crate::db::set_setting(conn, "library_mode", value)
}

/// Условие видимости треков для запрашивающего: SQL-фрагмент и параметры к нему.
///
/// Фрагмент всегда начинается с `AND` либо пуст, чтобы подставляться в любой запрос,
/// у которого уже есть `WHERE`. Пути подставляются параметрами, а не конкатенацией.
/// `prefix` - псевдоним таблицы tracks с точкой (`"t."`) или пустая строка.
pub fn visibility_at(conn: &Connection, ident: &Ident, prefix: &str) -> (String, Vec<Value>) {
    // Временный токен Jam открывает только треки активной сессии через jam::Registry.
    // Обычные непривязанные устройства сохраняют прежний доступ ко всей библиотеке.
    if is_jam_guest(conn, ident) {
        return (" AND 1=0".to_string(), Vec::new());
    }
    let Some(uid) = ident.user_id else {
        // Непривязанное устройство видит всё только до создания первого аккаунта.
        // После этого оно должно войти или пройти pairing от имени пользователя.
        return if count(conn) == 0 {
            (String::new(), Vec::new())
        } else {
            (" AND 1=0".to_string(), Vec::new())
        };
    };
    match library_mode(conn) {
        LibraryMode::Separate => {
            let lib: i64 = conn
                .query_row("SELECT library_id FROM users WHERE id=?1", [uid], |r| r.get(0))
                .unwrap_or(0);
            (format!(" AND {prefix}library_id = ?"), vec![Value::Integer(lib)])
        }
        LibraryMode::Shared => {
            let folders = folder_access(conn, uid);
            if folders.is_empty() {
                return (String::new(), Vec::new());
            }
            // LIKE 'префикс%' с ESCAPE: путь может содержать _ и %, которые в LIKE
            // значат "любой символ"/"что угодно" - без экранирования доступ протёк бы.
            let ors =
                vec![format!("{prefix}path LIKE ? ESCAPE '\\'"); folders.len()].join(" OR ");
            let params = folders
                .into_iter()
                .map(|f| {
                    let f = f.trim_end_matches(['/', '\\']).to_string();
                    Value::Text(format!("{}%", like_escape(&f)))
                })
                .collect();
            (format!(" AND ({ors})"), params)
        }
    }
}

/// То же без псевдонима таблицы - обычный случай.
pub fn visibility(conn: &Connection, ident: &Ident) -> (String, Vec<Value>) {
    visibility_at(conn, ident, "")
}

fn like_escape(s: &str) -> String {
    s.replace('\\', "\\\\").replace('%', "\\%").replace('_', "\\_")
}

pub fn folder_access(conn: &Connection, user_id: i64) -> Vec<String> {
    conn.prepare("SELECT folder_path FROM user_folder_access WHERE user_id=?1")
        .and_then(|mut s| {
            s.query_map([user_id], |r| r.get::<_, String>(0))?
                .collect::<rusqlite::Result<Vec<_>>>()
        })
        .unwrap_or_default()
}

/// Видит ли пользователь конкретный трек - та же проверка, что и в списке,
/// но одним запросом (нужна перед отдачей файла).
pub fn can_see_track(conn: &Connection, ident: &Ident, track_id: i64) -> bool {
    let (clause, mut params) = visibility(conn, ident);
    params.insert(0, Value::Integer(track_id));
    conn.query_row(
        &format!("SELECT 1 FROM tracks WHERE id = ?{clause}"),
        rusqlite::params_from_iter(params),
        |_| Ok(()),
    )
    .is_ok()
}

fn is_jam_guest(conn: &Connection, ident: &Ident) -> bool {
    ident.device_id.is_some_and(|id| {
        conn.query_row(
            "SELECT 1 FROM devices WHERE id=?1 AND name LIKE 'Jam Guest (%)'",
            [id],
            |_| Ok(()),
        )
        .is_ok()
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn db() -> Connection {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch(crate::db::SCHEMA).unwrap();
        c
    }

    fn add_track(c: &Connection, path: &str, library_id: i64) -> i64 {
        c.execute(
            "INSERT INTO tracks (path, title, library_id) VALUES (?1, ?1, ?2)",
            rusqlite::params![path, library_id],
        )
        .unwrap();
        c.last_insert_rowid()
    }

    fn visible(c: &Connection, ident: &Ident) -> Vec<String> {
        let (clause, params) = visibility(c, ident);
        let mut stmt = c
            .prepare(&format!("SELECT path FROM tracks WHERE 1=1{clause} ORDER BY path"))
            .unwrap();
        stmt.query_map(rusqlite::params_from_iter(params), |r| r.get(0))
            .unwrap()
            .collect::<rusqlite::Result<Vec<_>>>()
            .unwrap()
    }

    #[test]
    fn пароль_проверяется_и_не_хранится_в_открытом_виде() {
        let h = hash_password("длинный-пароль").unwrap();
        assert!(!h.contains("длинный-пароль"));
        assert!(verify_password("длинный-пароль", &h));
        assert!(!verify_password("другой-пароль", &h));
    }

    #[test]
    fn первый_пользователь_владелец_логин_работает() {
        let c = db();
        assert_eq!(count(&c), 0);
        let id = create(&c, "хозяин", "пароль12345", "owner", 0).unwrap();
        assert!(is_owner(&c, id));
        let (lid, token) = login(&c, "хозяин", "пароль12345").unwrap();
        assert_eq!(lid, id);
        assert_eq!(verify_session(&c, &token), Some(id));
        assert_eq!(login(&c, "хозяин", "не тот"), Err(UserError::BadCredentials));
        logout(&c, &token).unwrap();
        assert_eq!(verify_session(&c, &token), None);
    }

    #[test]
    fn смена_и_сброс_пароля() {
        let c = db();
        let id = create(&c, "хозяин", "пароль12345", "owner", 0).unwrap();
        let (_, tok) = login(&c, "хозяин", "пароль12345").unwrap();

        // Неверный текущий пароль - отказ, сессия жива.
        assert_eq!(
            change_password(&c, id, "не тот", "новыйпароль1"),
            Err(UserError::BadCredentials)
        );
        assert_eq!(verify_session(&c, &tok), Some(id));

        // Верный - пароль сменился, старая сессия погашена.
        change_password(&c, id, "пароль12345", "новыйпароль1").unwrap();
        assert_eq!(verify_session(&c, &tok), None);
        assert!(login(&c, "хозяин", "новыйпароль1").is_ok());
        assert_eq!(login(&c, "хозяин", "пароль12345"), Err(UserError::BadCredentials));

        // Короткий новый пароль отвергается.
        assert_eq!(change_password(&c, id, "новыйпароль1", "abc"), Err(UserError::BadInput));

        // Сброс владельцем - без текущего пароля.
        let (_, tok2) = login(&c, "хозяин", "новыйпароль1").unwrap();
        reset_password(&c, id, "сброшенный99").unwrap();
        assert_eq!(verify_session(&c, &tok2), None);
        assert!(login(&c, "хозяин", "сброшенный99").is_ok());
        assert!(matches!(reset_password(&c, 999, "сброшенный99"), Err(UserError::Db(_))));
    }

    #[test]
    fn короткий_пароль_и_занятое_имя_отвергаются() {
        let c = db();
        assert_eq!(create(&c, "а", "коротко", "owner", 0), Err(UserError::BadInput));
        create(&c, "а", "пароль12345", "owner", 0).unwrap();
        assert_eq!(create(&c, "а", "пароль12345", "user", 0), Err(UserError::Taken));
    }

    #[test]
    fn инвайт_одноразовый_и_протухает() {
        let mut c = db();
        let owner = create(&c, "хозяин", "пароль12345", "owner", 0).unwrap();
        let inv = create_invite(&c, owner, "user", 0, None).unwrap();
        let id = accept_invite(&mut c, &inv.token, "гость", "пароль12345").unwrap();
        assert_eq!(get(&c, id).unwrap().role, "user");
        assert_eq!(
            accept_invite(&mut c, &inv.token, "второй", "пароль12345"),
            Err(UserError::BadInvite)
        );

        let inv2 = create_invite(&c, owner, "user", 0, None).unwrap();
        c.execute(
            "UPDATE invites SET expires_at=?1 WHERE token_hash=?2",
            rusqlite::params![now() - 1, hash_token(&inv2.token)],
        )
        .unwrap();
        assert_eq!(
            accept_invite(&mut c, &inv2.token, "поздний", "пароль12345"),
            Err(UserError::BadInvite)
        );
    }

    #[test]
    fn раздельные_библиотеки_не_видят_друг_друга() {
        let c = db();
        set_library_mode(&c, LibraryMode::Separate).unwrap();
        let a = create(&c, "а", "пароль12345", "owner", 1).unwrap();
        let b = create(&c, "б", "пароль12345", "user", 2).unwrap();
        add_track(&c, "/муз/а/один.mp3", 1);
        add_track(&c, "/муз/б/два.mp3", 2);

        let va = visible(&c, &Ident { user_id: Some(a), device_id: None });
        let vb = visible(&c, &Ident { user_id: Some(b), device_id: None });
        assert_eq!(va, vec!["/муз/а/один.mp3"]);
        assert_eq!(vb, vec!["/муз/б/два.mp3"]);
    }

    #[test]
    fn непривязанное_устройство_не_видит_библиотеку_после_создания_аккаунта() {
        let c = db();
        let track = add_track(&c, "/муз/секрет.mp3", 0);
        c.execute(
            "INSERT INTO devices (name, token_hash, created_at) VALUES ('Телефон', 'h', 0)",
            [],
        )
        .unwrap();
        let ident = Ident { user_id: None, device_id: Some(c.last_insert_rowid()) };
        assert!(can_see_track(&c, &ident, track));

        create(&c, "хозяин", "пароль12345", "owner", 0).unwrap();
        assert!(!can_see_track(&c, &ident, track));
        assert!(visible(&c, &ident).is_empty());
    }

    #[test]
    fn общая_библиотека_режется_по_папкам() {
        let c = db();
        let a = create(&c, "а", "пароль12345", "owner", 0).unwrap();
        let b = create(&c, "б", "пароль12345", "user", 0).unwrap();
        let rock = add_track(&c, "/муз/рок/один.mp3", 0);
        add_track(&c, "/муз/джаз/два.mp3", 0);

        // Владелец без ограничений видит всё.
        assert_eq!(visible(&c, &Ident { user_id: Some(a), device_id: None }).len(), 2);

        c.execute(
            "INSERT INTO user_folder_access (user_id, folder_path) VALUES (?1, '/муз/рок')",
            [b],
        )
        .unwrap();
        let ident_b = Ident { user_id: Some(b), device_id: None };
        assert_eq!(visible(&c, &ident_b), vec!["/муз/рок/один.mp3"]);
        assert!(can_see_track(&c, &ident_b, rock));
        assert!(!can_see_track(&c, &ident_b, rock + 1));
    }

    #[test]
    fn гостевой_токен_джема_не_открывает_библиотеку() {
        let c = db();
        let track = add_track(&c, "/муз/секрет.mp3", 0);
        c.execute(
            "INSERT INTO devices (name, token_hash, created_at) VALUES ('Jam Guest (ABC234)', 'h', 0)",
            [],
        )
        .unwrap();
        let ident = Ident { user_id: None, device_id: Some(c.last_insert_rowid()) };

        assert!(!can_see_track(&c, &ident, track));
        assert_eq!(visible(&c, &ident), Vec::<String>::new());
    }

    #[test]
    fn подчёркивание_в_пути_не_работает_как_шаблон() {
        let c = db();
        let b = create(&c, "б", "пароль12345", "user", 0).unwrap();
        add_track(&c, "/муз/a_b/трек.mp3", 0);
        add_track(&c, "/муз/axb/чужой.mp3", 0);
        c.execute(
            "INSERT INTO user_folder_access (user_id, folder_path) VALUES (?1, '/муз/a_b')",
            [b],
        )
        .unwrap();
        assert_eq!(
            visible(&c, &Ident { user_id: Some(b), device_id: None }),
            vec!["/муз/a_b/трек.mp3"],
            "подчёркивание в LIKE должно быть экранировано"
        );
    }
}
