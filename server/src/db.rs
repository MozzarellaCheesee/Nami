use std::path::Path;

use rusqlite::Connection;

/// Минимальная схема этапа 1: только то, что нужно для списка треков и отдачи файла.
///
/// ponytail: отдельных таблиц albums/artists нет - на этом этапе они целиком выводятся
/// из tracks (`SELECT DISTINCT album ...`). Заводить их стоит, когда появятся собственные
/// поля (обложка альбома, MBID исполнителя), а не ради нормализации ради нормализации.
pub const SCHEMA: &str = r#"
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
    seen_at     INTEGER NOT NULL DEFAULT 0,
    library_id  INTEGER NOT NULL DEFAULT 0,
    file_hash   TEXT,
    mbid        TEXT,       -- MusicBrainz recording id, если нашёлся при обогащении
    enriched_at INTEGER     -- когда трек рассматривали в MusicBrainz (NULL - ещё нет)
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
    last_seen_at INTEGER,
    user_id     INTEGER            -- NULL - устройство из одиночного режима (этапы 1-2)
);

-- Одноразовые коды сопряжения (10 минут, одно использование).
CREATE TABLE IF NOT EXISTS pairing_codes (
    code        TEXT PRIMARY KEY,
    created_at  INTEGER NOT NULL,
    expires_at  INTEGER NOT NULL,
    used_at     INTEGER,
    user_id     INTEGER            -- кому будет принадлежать сопряжённое устройство
);

-- Синхронизируемое состояние: плейлисты, рейтинги, теги, моменты, петли, заметки,
-- история прослушиваний. Строка = ОДНО ПОЛЕ одной записи со своей меткой времени -
-- именно это даёт last-write-wins на уровне поля, а не записи целиком.
-- Подробное обоснование такой формы - в доккомментарии модуля sync.
-- Удаление - строка с field='__deleted' (надгробие), физическая чистка по TTL.
-- user_id=0 - состояние одиночного режима (пользователей ещё нет).
CREATE TABLE IF NOT EXISTS state (
    user_id     INTEGER NOT NULL DEFAULT 0,
    entity      TEXT NOT NULL,
    id          TEXT NOT NULL,
    field       TEXT NOT NULL,
    value       TEXT NOT NULL,   -- JSON-значение
    updated_at  INTEGER NOT NULL,
    PRIMARY KEY (user_id, entity, id, field)
);
-- Запрос синхронизации ровно один: "всё, что новее метки" - под него и индекс.
CREATE INDEX IF NOT EXISTS idx_state_updated ON state(updated_at);

-- Позиция воспроизведения: высокочастотная, поэтому вне таблицы state (см. sync.rs).
-- Одна строка на устройство, история не копится.
CREATE TABLE IF NOT EXISTS playback_position (
    device_id   INTEGER PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    track_id    INTEGER NOT NULL,
    position_ms INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);

-- ---------------------------------------------------------------- этап 3

-- Раздельные библиотеки. id=0 - библиотека по умолчанию (music_dirs из config.toml),
-- строки заводятся только когда владелец включил режим separate.
CREATE TABLE IF NOT EXISTS libraries (
    id          INTEGER PRIMARY KEY,
    name        TEXT NOT NULL,
    dirs        TEXT NOT NULL DEFAULT '[]'   -- JSON-массив путей
);

-- Люди (не путать с devices: там сопряжение железки, тут вход человека).
CREATE TABLE IF NOT EXISTS users (
    id            INTEGER PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,             -- argon2id
    role          TEXT NOT NULL,             -- owner | user | guest
    library_id    INTEGER NOT NULL DEFAULT 0,
    now_playing_visible INTEGER NOT NULL DEFAULT 1,
    created_at    INTEGER NOT NULL
);

-- Режим "общая библиотека с ограничением доступа к папкам". Ни одной строки на
-- пользователя = видит всё; появилась хоть одна - видит только перечисленные поддеревья.
CREATE TABLE IF NOT EXISTS user_folder_access (
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    folder_path TEXT NOT NULL,
    PRIMARY KEY (user_id, folder_path)
);

-- Инвайты по ссылке. Как и везде, хранится только хеш токена.
CREATE TABLE IF NOT EXISTS invites (
    token_hash  TEXT PRIMARY KEY,
    created_by  INTEGER,
    role        TEXT NOT NULL DEFAULT 'user',
    library_id  INTEGER NOT NULL DEFAULT 0,
    created_at  INTEGER NOT NULL,
    expires_at  INTEGER NOT NULL,
    used_at     INTEGER,
    used_by     INTEGER
);

-- Сессии входа человека по паролю.
CREATE TABLE IF NOT EXISTS sessions (
    token_hash  TEXT PRIMARY KEY,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  INTEGER NOT NULL,
    expires_at  INTEGER NOT NULL
);

-- Гостевые ссылки. track_ids - JSON-массив: сервер не понимает семантику плейлистов
-- (они живут в таблице state как непрозрачный JSON), поэтому состав ссылки
-- фиксируется в момент создания тем, кто делится.
CREATE TABLE IF NOT EXISTS shares (
    token_hash  TEXT PRIMARY KEY,
    title       TEXT NOT NULL,
    track_ids   TEXT NOT NULL,
    created_by  INTEGER,
    created_at  INTEGER NOT NULL,
    expires_at  INTEGER,               -- NULL - без срока
    max_plays   INTEGER,               -- NULL - без ограничения
    play_count  INTEGER NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------- этап 4

-- Кеш лирики. Сырой текст как пришёл от источника (LRC или обычный) - разбор дешёвый
-- и делается на отдаче, а хранить разобранное значило бы держать два формата сразу.
-- Перевод отдельной колонкой: он запрашивается не всегда и сбрасывается при обновлении
-- текста (иначе строки разъедутся).
CREATE TABLE IF NOT EXISTS lyrics (
    track_id    INTEGER PRIMARY KEY REFERENCES tracks(id) ON DELETE CASCADE,
    raw         TEXT NOT NULL,
    synced      INTEGER NOT NULL DEFAULT 0,
    source      TEXT NOT NULL,          -- lrclib | none
    fetched_at  INTEGER NOT NULL,
    translation TEXT                    -- JSON-массив строк, по одной на строку raw
);

-- Файлы, которые сканер не смог открыть. Здоровью библиотеки нужен именно факт
-- "не открылось при сканировании", а перепроверять 50 000 файлов на каждый запрос
-- ручки нельзя - поэтому неудачи записываются в момент прохода сканера.
CREATE TABLE IF NOT EXISTS scan_failures (
    path       TEXT PRIMARY KEY,
    error      TEXT NOT NULL,
    library_id INTEGER NOT NULL DEFAULT 0,
    failed_at  INTEGER NOT NULL
);

-- Настройки сервера, которые меняются в рантайме (в отличие от config.toml).
CREATE TABLE IF NOT EXISTS settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"#;

/// Открывает БД, накатывает схему и миграции (идемпотентно).
pub fn open(path: &Path) -> crate::Res<Connection> {
    let conn = Connection::open(path)?;
    conn.execute_batch(SCHEMA)?;
    migrate(&conn)?;
    Ok(conn)
}

/// Есть ли колонка в таблице.
fn has_column(conn: &Connection, table: &str, column: &str) -> bool {
    conn.prepare(&format!("PRAGMA table_info({table})"))
        .and_then(|mut s| {
            s.query_map([], |r| r.get::<_, String>(1))?
                .collect::<rusqlite::Result<Vec<_>>>()
        })
        .map(|cols| cols.iter().any(|c| c == column))
        .unwrap_or(false)
}

/// Догоняет схему БД, созданной предыдущими этапами, до текущей.
///
/// ponytail: без таблицы версий - проверяем наличие конкретной колонки. Пока
/// миграции наперечёт, это короче и не врёт (версия не может разъехаться с фактом).
pub fn migrate(conn: &Connection) -> crate::Res<()> {
    let add = |table: &str, column: &str, decl: &str| -> crate::Res<()> {
        if !has_column(conn, table, column) {
            conn.execute_batch(&format!("ALTER TABLE {table} ADD COLUMN {column} {decl}"))?;
        }
        Ok(())
    };
    add("tracks", "library_id", "INTEGER NOT NULL DEFAULT 0")?;
    add("tracks", "file_hash", "TEXT")?;
    add("tracks", "mbid", "TEXT")?;
    add("tracks", "enriched_at", "INTEGER")?;
    add("devices", "user_id", "INTEGER")?;
    add("pairing_codes", "user_id", "INTEGER")?;

    // state пересобирается: у неё меняется первичный ключ (добавляется user_id),
    // ALTER TABLE так не умеет. Существующие строки уезжают к user_id=0 -
    // это состояние одиночного режима, которое и должно остаться общим.
    if !has_column(conn, "state", "user_id") {
        conn.execute_batch(
            "BEGIN;
             ALTER TABLE state RENAME TO state_old;
             CREATE TABLE state (
                user_id    INTEGER NOT NULL DEFAULT 0,
                entity     TEXT NOT NULL,
                id         TEXT NOT NULL,
                field      TEXT NOT NULL,
                value      TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (user_id, entity, id, field)
             );
             INSERT INTO state (user_id, entity, id, field, value, updated_at)
                SELECT 0, entity, id, field, value, updated_at FROM state_old;
             DROP TABLE state_old;
             CREATE INDEX IF NOT EXISTS idx_state_updated ON state(updated_at);
             COMMIT;",
        )?;
    }
    conn.execute_batch(
        "CREATE INDEX IF NOT EXISTS idx_tracks_library ON tracks(library_id);
         CREATE INDEX IF NOT EXISTS idx_tracks_hash ON tracks(file_hash);",
    )?;
    Ok(())
}

/// Читает настройку из таблицы settings.
pub fn setting(conn: &Connection, key: &str) -> Option<String> {
    conn.query_row("SELECT value FROM settings WHERE key=?1", [key], |r| r.get(0)).ok()
}

/// Пишет настройку.
pub fn set_setting(conn: &Connection, key: &str, value: &str) -> rusqlite::Result<()> {
    conn.execute(
        "INSERT INTO settings (key, value) VALUES (?1, ?2)
         ON CONFLICT(key) DO UPDATE SET value=?2",
        rusqlite::params![key, value],
    )?;
    Ok(())
}

/// Секунды unix-эпохи - единый формат времени во всей БД.
pub fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}
