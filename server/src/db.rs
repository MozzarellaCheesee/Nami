use std::path::Path;

use rusqlite::Connection;

/// Минимальная схема этапа 1: только то, что нужно для списка треков и отдачи файла.
///
/// ponytail: отдельных таблиц albums/artists нет - на этом этапе они целиком выводятся
/// из tracks (`SELECT DISTINCT album ...`). Заводить их стоит, когда появятся собственные
/// поля (обложка альбома, MBID исполнителя), а не ради нормализации ради нормализации.
const SCHEMA: &str = r#"
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;

CREATE TABLE IF NOT EXISTS tracks (
    id          INTEGER PRIMARY KEY,
    path        TEXT NOT NULL UNIQUE,
    title       TEXT NOT NULL,
    artist      TEXT,
    album       TEXT,
    album_artist TEXT,
    track_no    INTEGER,
    year        INTEGER,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    size_bytes  INTEGER NOT NULL DEFAULT 0,
    mtime       INTEGER NOT NULL DEFAULT 0,
    format      TEXT,
    seen_at     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_tracks_artist ON tracks(artist);
CREATE INDEX IF NOT EXISTS idx_tracks_album  ON tracks(album);

-- Постоянные токены устройств. Хранится только sha256 токена: утёкшая БД
-- не даёт войти, а отзыв - это просто DELETE, без blacklist-инфраструктуры JWT.
CREATE TABLE IF NOT EXISTS devices (
    id          INTEGER PRIMARY KEY,
    name        TEXT NOT NULL,
    token_hash  TEXT NOT NULL UNIQUE,
    created_at  INTEGER NOT NULL,
    last_seen_at INTEGER
);

-- Одноразовые коды сопряжения (10 минут, одно использование).
CREATE TABLE IF NOT EXISTS pairing_codes (
    code        TEXT PRIMARY KEY,
    created_at  INTEGER NOT NULL,
    expires_at  INTEGER NOT NULL,
    used_at     INTEGER
);
"#;

/// Открывает БД и накатывает схему (идемпотентно).
pub fn open(path: &Path) -> crate::Res<Connection> {
    let conn = Connection::open(path)?;
    conn.execute_batch(SCHEMA)?;
    Ok(conn)
}

/// Секунды unix-эпохи - единый формат времени во всей БД.
pub fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}
