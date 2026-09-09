//! Синхронизация состояния между клиентами: плейлисты, рейтинги, теги, моменты, петли,
//! заметки, история прослушиваний. Плюс отдельно - позиция воспроизведения.
//!
//! # Почему одна таблица, а не таблица на сущность
//!
//! План требует last-write-wins **на уровне поля**: два клиента, поменявшие разные поля
//! одной записи, должны сохранить оба изменения. Прямая реализация - у каждой колонки
//! каждой из девяти таблиц своя колонка `*_updated_at` и своя ветка сравнения: девять
//! наборов почти одинакового SQL, которые надо трогать при каждом новом поле.
//!
//! Вместо этого состояние хранится по одной строке на ПОЛЕ (`entity`, `id`, `field`,
//! `value`, `updated_at`). Тогда per-field LWW - это одна строчка `ON CONFLICT ... WHERE
//! excluded.updated_at > state.updated_at`, отдача изменений - один индексный запрос
//! по `updated_at` сразу по всем сущностям, а новое поле у плейлиста не требует миграции.
//!
//! Расплата: сервер не понимает семантику полей (он и не должен - типизированная схема
//! живёт в клиенте, в Room), и по такой таблице неудобно делать серверные запросы вида
//! "плейлисты с более чем 10 треками". Когда серверу реально понадобится читать состояние
//! (веб-клиент, OpenSubsonic) - поверх этой таблицы строятся производные представления,
//! а протокол синхронизации остаётся тем же.

use rusqlite::Connection;
use serde::{Deserialize, Serialize};

use crate::db::now;

/// Допустимые сущности. Список закрытый: иначе первая же опечатка клиента заведёт
/// новую "сущность", которая будет молча копиться в БД вечно.
pub const ENTITIES: &[&str] = &[
    "playlist",
    "playlist_track",
    "rating",
    "tag",
    "tag_assignment",
    "moment",
    "loop",
    "track_note",
    "listening_history",
];

/// Псевдополе-надгробие: удаление записи целиком. Хранится как обычное поле со своим
/// `updated_at`, поэтому воскрешение ("удалил на телефоне, но потом переименовал на
/// планшете позже") решается тем же сравнением меток, что и всё остальное.
pub const DELETED: &str = "__deleted";

/// Потолок ответа: без него первый же запрос с `since=0` по большой библиотеке
/// вытянет всю историю изменений в память.
///
/// ponytail: при `truncated=true` клиент повторяет запрос с `since` последнего полученного
/// изменения. Курсора точнее (пара updated_at+rowid) нет намеренно - изменения состояния
/// редкие, до потолка в реальной жизни доходит только первая синхронизация.
pub const MAX_CHANGES: usize = 10_000;

/// Одно изменение одного поля.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Change {
    pub entity: String,
    pub id: String,
    pub field: String,
    /// JSON-значение поля. `null` - поле сброшено (не путать с удалением записи).
    #[serde(default)]
    pub value: serde_json::Value,
    pub updated_at: i64,
}

#[derive(Debug, Serialize)]
pub struct Pull {
    pub now: i64,
    pub changes: Vec<Change>,
    /// true - изменений больше, чем влезло: повторить с `since` последнего изменения.
    pub truncated: bool,
}

#[derive(Debug, Default, Serialize)]
pub struct PushReport {
    /// Сколько изменений реально записано.
    pub applied: usize,
    /// Сколько отброшено как устаревшие (на сервере лежит более свежая метка поля).
    pub stale: usize,
    /// Сколько отвергнуто как некорректные (чужая сущность, метка из будущего).
    pub rejected: usize,
    pub now: i64,
}

/// Насколько метка клиента может опережать серверные часы. Часы на телефоне
/// бывают сбиты; изменение "из 2099 года" иначе навсегда выиграет все конфликты.
pub const MAX_SKEW_SECS: i64 = 300;

/// Отдаёт все изменения новее `since` по всем сущностям одним ответом.
pub fn pull(conn: &Connection, since: i64) -> rusqlite::Result<Pull> {
    let mut stmt = conn.prepare(
        "SELECT entity, id, field, value, updated_at FROM state
         WHERE updated_at > ?1 ORDER BY updated_at LIMIT ?2",
    )?;
    let mut changes = stmt
        .query_map(rusqlite::params![since, MAX_CHANGES as i64 + 1], |r| {
            let raw: String = r.get(3)?;
            Ok(Change {
                entity: r.get(0)?,
                id: r.get(1)?,
                field: r.get(2)?,
                value: serde_json::from_str(&raw).unwrap_or(serde_json::Value::Null),
                updated_at: r.get(4)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    let truncated = changes.len() > MAX_CHANGES;
    changes.truncate(MAX_CHANGES);
    Ok(Pull { now: now(), changes, truncated })
}

/// Применяет изменения клиента. LWW по полю: побеждает более поздняя метка,
/// при равных метках остаётся уже записанное (иначе повторная отправка одного и того
/// же пакета бесконечно переписывала бы строки).
pub fn push(conn: &mut Connection, changes: &[Change]) -> rusqlite::Result<PushReport> {
    let t = now();
    let mut rep = PushReport { now: t, ..Default::default() };
    let tx = conn.transaction()?;
    {
        let mut stmt = tx.prepare(
            "INSERT INTO state (entity, id, field, value, updated_at) VALUES (?1,?2,?3,?4,?5)
             ON CONFLICT(entity, id, field) DO UPDATE SET
                value = excluded.value, updated_at = excluded.updated_at
             WHERE excluded.updated_at > state.updated_at",
        )?;
        for c in changes {
            if !ENTITIES.contains(&c.entity.as_str())
                || c.id.is_empty()
                || c.field.is_empty()
                || c.updated_at <= 0
                || c.updated_at > t + MAX_SKEW_SECS
            {
                rep.rejected += 1;
                continue;
            }
            let value = c.value.to_string();
            let n = stmt.execute(rusqlite::params![
                c.entity,
                c.id,
                c.field,
                value,
                c.updated_at
            ])?;
            if n == 1 {
                rep.applied += 1;
            } else {
                rep.stale += 1;
            }
        }
    }
    tx.commit()?;
    Ok(rep)
}

/// Физически удаляет поля записей, помеченных удалёнными раньше, чем `ttl_days` назад.
///
/// Раньше этого срока строки-надгробия держатся намеренно: клиент, не заходивший
/// неделю, обязан узнать об удалении, а не воскресить запись своей копией.
pub fn purge_tombstones(conn: &Connection, ttl_days: i64) -> rusqlite::Result<usize> {
    let cutoff = now() - ttl_days.max(1) * 86_400;
    conn.execute(
        "DELETE FROM state WHERE (entity, id) IN (
             SELECT entity, id FROM state
             WHERE field = ?1 AND value = 'true' AND updated_at < ?2)",
        rusqlite::params![DELETED, cutoff],
    )
}

/// Позиция воспроизведения - отдельно от общего sync-потока.
///
/// Она обновляется каждые несколько секунд, пока трек играет, а плейлисты и теги -
/// раз в несколько дней. В одной таблице частое перебивало бы редкое: клиент,
/// спрашивающий изменения раз в минуту, каждый раз получал бы пачку позиций
/// вместо одного нужного ему переименования плейлиста.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Position {
    pub track_id: i64,
    pub position_ms: i64,
    #[serde(default)]
    pub updated_at: i64,
}

/// Пишет позицию для устройства. История не ведётся - одна строка на устройство:
/// нужна только последняя позиция, копить их незачем.
pub fn set_position(conn: &Connection, device_id: i64, p: &Position) -> rusqlite::Result<i64> {
    let t = if p.updated_at > 0 { p.updated_at.min(now() + MAX_SKEW_SECS) } else { now() };
    conn.execute(
        "INSERT INTO playback_position (device_id, track_id, position_ms, updated_at)
         VALUES (?1,?2,?3,?4)
         ON CONFLICT(device_id) DO UPDATE SET
            track_id = excluded.track_id, position_ms = excluded.position_ms,
            updated_at = excluded.updated_at
         WHERE excluded.updated_at >= playback_position.updated_at",
        rusqlite::params![device_id, p.track_id, p.position_ms, t],
    )?;
    Ok(t)
}

/// Позиции всех устройств новее `since` - чтобы телефон мог продолжить с места,
/// на котором остановился ноутбук.
pub fn positions(conn: &Connection, since: i64) -> rusqlite::Result<Vec<Position>> {
    let mut stmt = conn.prepare(
        "SELECT track_id, position_ms, updated_at FROM playback_position
         WHERE updated_at > ?1 ORDER BY updated_at DESC",
    )?;
    let rows = stmt
        .query_map([since], |r| {
            Ok(Position { track_id: r.get(0)?, position_ms: r.get(1)?, updated_at: r.get(2)? })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn db() -> Connection {
        let mut c = Connection::open_in_memory().unwrap();
        c.execute_batch(crate::db::SCHEMA).unwrap();
        // Устройство для тестов позиции.
        c.execute(
            "INSERT INTO devices (id, name, token_hash, created_at) VALUES (1,'т','h',0)",
            [],
        )
        .unwrap();
        let _ = &mut c;
        c
    }

    fn ch(id: &str, field: &str, value: &str, at: i64) -> Change {
        Change {
            entity: "playlist".into(),
            id: id.into(),
            field: field.into(),
            value: serde_json::Value::String(value.into()),
            updated_at: at,
        }
    }

    #[test]
    fn разные_поля_одной_записи_не_затирают_друг_друга() {
        let mut c = db();
        let t = now();
        // Два клиента одновременно правят одну запись, но разные её поля.
        push(&mut c, &[ch("p1", "name", "Утро", t)]).unwrap();
        push(&mut c, &[ch("p1", "cover", "cover.jpg", t)]).unwrap();

        let got = pull(&c, 0).unwrap().changes;
        assert_eq!(got.len(), 2, "оба изменения обязаны сохраниться: {got:?}");
        let name = got.iter().find(|x| x.field == "name").unwrap();
        assert_eq!(name.value, serde_json::json!("Утро"));
        let cover = got.iter().find(|x| x.field == "cover").unwrap();
        assert_eq!(cover.value, serde_json::json!("cover.jpg"));
    }

    #[test]
    fn одно_поле_выигрывает_более_поздняя_метка() {
        let mut c = db();
        let t = now();
        push(&mut c, &[ch("p1", "name", "поздний", t)]).unwrap();
        // Более раннее изменение того же поля приходит вторым - отбрасывается.
        let r = push(&mut c, &[ch("p1", "name", "ранний", t - 10)]).unwrap();
        assert_eq!(r.stale, 1);
        assert_eq!(r.applied, 0);
        assert_eq!(pull(&c, 0).unwrap().changes[0].value, serde_json::json!("поздний"));

        // А более позднее - побеждает.
        let r = push(&mut c, &[ch("p1", "name", "новейший", t + 5)]).unwrap();
        assert_eq!(r.applied, 1);
        assert_eq!(pull(&c, 0).unwrap().changes[0].value, serde_json::json!("новейший"));
    }

    #[test]
    fn since_отдаёт_только_новое() {
        let mut c = db();
        let t = now();
        push(&mut c, &[ch("p1", "name", "старое", t - 100)]).unwrap();
        push(&mut c, &[ch("p2", "name", "новое", t)]).unwrap();
        let got = pull(&c, t - 1).unwrap().changes;
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].id, "p2");
    }

    #[test]
    fn удаление_это_надгробие_которое_доезжает_до_клиента() {
        let mut c = db();
        let t = now();
        push(&mut c, &[ch("p1", "name", "Утро", t)]).unwrap();
        push(
            &mut c,
            &[Change {
                entity: "playlist".into(),
                id: "p1".into(),
                field: DELETED.into(),
                value: serde_json::Value::Bool(true),
                updated_at: t + 1,
            }],
        )
        .unwrap();
        let got = pull(&c, t).unwrap().changes;
        assert!(got.iter().any(|x| x.field == DELETED), "надгробие должно уехать клиенту");

        // Свежее надгробие не чистится, старое - чистится.
        assert_eq!(purge_tombstones(&c, 30).unwrap(), 0);
        c.execute(
            "UPDATE state SET updated_at = ?1 WHERE field = ?2",
            rusqlite::params![t - 40 * 86_400, DELETED],
        )
        .unwrap();
        assert_eq!(purge_tombstones(&c, 30).unwrap(), 2, "уходит вся запись целиком");
        assert!(pull(&c, 0).unwrap().changes.is_empty());
    }

    #[test]
    fn чужая_сущность_и_метка_из_будущего_отвергаются() {
        let mut c = db();
        let mut bad = ch("p1", "name", "x", now());
        bad.entity = "рандом".into();
        let future = ch("p2", "name", "x", now() + 10_000);
        let r = push(&mut c, &[bad, future]).unwrap();
        assert_eq!(r.rejected, 2);
        assert!(pull(&c, 0).unwrap().changes.is_empty());
    }

    #[test]
    fn позиция_хранится_отдельно_и_не_попадает_в_общий_поток() {
        let c = db();
        let t = now();
        set_position(&c, 1, &Position { track_id: 7, position_ms: 1000, updated_at: t }).unwrap();
        set_position(&c, 1, &Position { track_id: 7, position_ms: 5000, updated_at: t + 3 })
            .unwrap();
        let p = positions(&c, 0).unwrap();
        assert_eq!(p.len(), 1, "на устройство одна строка, история не копится");
        assert_eq!(p[0].position_ms, 5000);
        // Устаревшая позиция не откатывает свежую.
        set_position(&c, 1, &Position { track_id: 7, position_ms: 10, updated_at: t }).unwrap();
        assert_eq!(positions(&c, 0).unwrap()[0].position_ms, 5000);
        assert!(pull(&c, 0).unwrap().changes.is_empty(), "позиции нет в sync-потоке");
    }
}
