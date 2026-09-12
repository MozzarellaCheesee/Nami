//! Лирика на сервере: поиск в LRCLIB, кеш в БД, перевод через DeepL.
//!
//! Смысл переноса на сервер (План.md §34): клиент не ходит в сеть за каждым треком и не
//! разбирает текст сам - ручка отдаёт готовый размеченный JSON, который остаётся только
//! показать. Один раз найденное лежит в таблице `lyrics` и переживает перезапуск.
//!
//! # Что отложено: морфологический разбор японского (фуригана/романизация)
//!
//! План разрешал отложить, и это тот случай. `lindera` работает только со словарём:
//! ipadic - примерно 50 МБ в собранном виде, unidic - больше 100 МБ. Словарь либо
//! вшивается в бинарник (крейт `lindera-ipadic` при сборке скачивает архив и
//! разворачивает его в `OUT_DIR`, что и раздувает образ, и делает сборку зависимой от
//! сети), либо кладётся рядом отдельным томом, который надо доставить в Docker-образ.
//! На бюджет "1 ядро / 512 МБ", ради которого весь сервер писан без единой тяжёлой
//! зависимости, это несоразмерно: словарь один весит больше, чем всё остальное вместе.
//!
//! Поэтому фуригана/романизация остаются задачей отдельного опционального модуля
//! (собираемого под флагом cargo и включаемого в отдельный "толстый" образ), а этот этап
//! закрывает самодостаточную часть: поиск, кеш и перевод. Поля `furigana`/`romaji` в
//! ответе намеренно отсутствуют, а не отдаются пустыми - клиенту незачем гадать,
//! "не нашлось" это или "сервер не умеет".

use rusqlite::Connection;
use serde::Serialize;

/// Сколько ждать внешний сервис. Лирика - не критичный путь, вечно висеть незачем.
const TIMEOUT_SECS: u64 = 8;

/// Одна строка лирики.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Line {
    /// Метка времени в миллисекундах. None - текст без синхронизации.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub time_ms: Option<i64>,
    pub text: String,
    /// Перевод этой строки, если он запрошен и получен.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub translation: Option<String>,
}

/// Ответ ручки.
#[derive(Debug, Serialize)]
pub struct Lyrics {
    pub track_id: i64,
    /// lrclib | none - откуда взят текст.
    pub source: String,
    /// Есть ли метки времени.
    pub synced: bool,
    pub lines: Vec<Line>,
    /// Когда положено в кеш (unix-секунды).
    pub fetched_at: i64,
}

/// Разбирает LRC: `[mm:ss.xx] текст`, несколько меток на строку - это одна и та же
/// строка, повторённая в разных местах песни (припев). Строки без меток сохраняются
/// как есть - тогда лирика считается несинхронизированной.
pub fn parse_lrc(raw: &str) -> Vec<Line> {
    let mut out = Vec::new();
    for line in raw.lines() {
        let mut rest = line;
        let mut stamps = Vec::new();
        while let Some(close) = rest.strip_prefix('[').and_then(|r| r.find(']')) {
            let inside = &rest[1..close + 1];
            match parse_stamp(inside) {
                Some(ms) => {
                    stamps.push(ms);
                    rest = &rest[close + 2..];
                }
                // [ar:...], [ti:...] и прочие метаданные LRC - не текст, строку пропускаем.
                None => {
                    rest = "";
                    break;
                }
            }
        }
        let text = rest.trim().to_string();
        if stamps.is_empty() {
            if !text.is_empty() {
                out.push(Line { time_ms: None, text, translation: None });
            }
            continue;
        }
        for ms in stamps {
            out.push(Line { time_ms: Some(ms), text: text.clone(), translation: None });
        }
    }
    out.sort_by_key(|l| l.time_ms.unwrap_or(0));
    out
}

/// `mm:ss.xx` или `mm:ss` -> миллисекунды. Не метка времени - None.
fn parse_stamp(s: &str) -> Option<i64> {
    let (m, rest) = s.split_once(':')?;
    let minutes: i64 = m.trim().parse().ok()?;
    let (sec, frac) = match rest.split_once(['.', ':']) {
        Some((a, b)) => (a, b),
        None => (rest, ""),
    };
    let seconds: i64 = sec.trim().parse().ok()?;
    // Сотые в LRC, но встречаются и тысячные - считаем по фактической длине.
    let frac_digits: String = frac.chars().filter(|c| c.is_ascii_digit()).collect();
    let millis = match frac_digits.len() {
        0 => 0,
        1 => frac_digits.parse::<i64>().ok()? * 100,
        2 => frac_digits.parse::<i64>().ok()? * 10,
        _ => frac_digits[..3].parse::<i64>().ok()?,
    };
    Some(minutes * 60_000 + seconds * 1000 + millis)
}

/// Простейшее percent-кодирование для query-параметров.
///
/// ponytail: отдельный крейт ради одной функции не нужен - здесь кодируется всё,
/// кроме незарезервированного набора RFC 3986, и этого достаточно.
pub fn urlencode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.as_bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*b as char)
            }
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

fn http_get(url: &str) -> Option<String> {
    ureq::get(url)
        .config()
        .timeout_global(Some(std::time::Duration::from_secs(TIMEOUT_SECS)))
        .build()
        .header("User-Agent", concat!("Nami server ", env!("CARGO_PKG_VERSION")))
        .call()
        .ok()?
        .body_mut()
        .read_to_string()
        .ok()
}

/// Достаёт синхронизированный (а если нет - обычный) текст из объекта ответа LRCLIB.
/// Инструментал - это не "не нашлось", но и не текст: считаем, что текста нет.
fn pick(v: &serde_json::Value) -> Option<(String, bool)> {
    if v.get("instrumental").and_then(|x| x.as_bool()).unwrap_or(false) {
        return None;
    }
    let s = |k: &str| {
        v.get(k)
            .and_then(|x| x.as_str())
            .map(str::trim)
            .filter(|x| !x.is_empty())
            .map(str::to_string)
    };
    s("syncedLyrics").map(|t| (t, true)).or_else(|| s("plainLyrics").map(|t| (t, false)))
}

/// Поиск в LRCLIB тем же путём, что уже проверен в Android-клиенте
/// (`data/LrcLibClient.kt`): сначала точный `/get` по названию, исполнителю и
/// длительности, затем `/search` как запасной вариант.
///
/// Синхронная функция: вызывающий обязан запускать её в `spawn_blocking`.
pub fn fetch_lrclib(
    title: &str,
    artist: Option<&str>,
    album: Option<&str>,
    duration_ms: i64,
) -> Option<(String, bool)> {
    let base = "https://lrclib.net/api";
    let mut url = format!("{base}/get?track_name={}", urlencode(title));
    if let Some(a) = artist.filter(|a| !a.is_empty()) {
        url.push_str(&format!("&artist_name={}", urlencode(a)));
    }
    if let Some(a) = album.filter(|a| !a.is_empty()) {
        url.push_str(&format!("&album_name={}", urlencode(a)));
    }
    if duration_ms > 0 {
        url.push_str(&format!("&duration={}", duration_ms / 1000));
    }
    if let Some(found) = http_get(&url)
        .and_then(|b| serde_json::from_str::<serde_json::Value>(&b).ok())
        .as_ref()
        .and_then(pick)
    {
        return Some(found);
    }

    let mut url = format!("{base}/search?track_name={}", urlencode(title));
    if let Some(a) = artist.filter(|a| !a.is_empty()) {
        url.push_str(&format!("&artist_name={}", urlencode(a)));
    }
    let body = http_get(&url)?;
    let arr: Vec<serde_json::Value> = serde_json::from_str(&body).ok()?;
    // Синхронизированный вариант предпочтительнее любого обычного, поэтому два прохода.
    arr.iter()
        .find_map(|v| pick(v).filter(|(_, synced)| *synced))
        .or_else(|| arr.iter().find_map(pick))
}

/// Перевод построчно через DeepL - тем же способом, что и в Android-клиенте
/// (`data/DeeplClient.kt`): один POST на весь пакет, форма, ключ в теле.
///
/// План разрешал не реализовывать перевод на сервере вовсе. Реализован вариант (а):
/// ключ берётся из конфигурации СЕРВЕРА (`deepl_api_key`), и если его нет - ручка
/// просто не переводит. Своего ключа сервер не заводит и клиентский не спрашивает.
pub fn translate_deepl(lines: &[String], api_key: &str, target: &str) -> Option<Vec<String>> {
    if lines.is_empty() {
        return Some(Vec::new());
    }
    let non_empty: Vec<(usize, &String)> = lines.iter().enumerate().filter(|(_, l)| !l.trim().is_empty()).collect();
    if non_empty.is_empty() {
        return Some(vec![String::new(); lines.len()]);
    }
    let host = if api_key.trim().ends_with(":fx") { "api-free.deepl.com" } else { "api.deepl.com" };
    let mut body = format!("auth_key={}&target_lang={}", urlencode(api_key), urlencode(target));
    for (_, l) in &non_empty {
        body.push_str(&format!("&text={}", urlencode(l)));
    }
    let raw = ureq::post(format!("https://{host}/v2/translate"))
        .config()
        .timeout_global(Some(std::time::Duration::from_secs(TIMEOUT_SECS + 4)))
        .build()
        .header("Content-Type", "application/x-www-form-urlencoded")
        .send(body.as_bytes())
        .ok()?
        .body_mut()
        .read_to_string()
        .ok()?;
    let v: serde_json::Value = serde_json::from_str(&raw).ok()?;
    let arr = v.get("translations")?.as_array()?;
    let mut result = vec![String::new(); lines.len()];
    for ((index, _), translated) in non_empty.into_iter().zip(arr) {
        result[index] = translated.get("text").and_then(|x| x.as_str()).unwrap_or("").to_string();
    }
    Some(result)
}

// ---------------------------------------------------------------- кеш

/// Строка кеша: сырой текст как пришёл от источника плюс отдельно перевод.
pub struct Cached {
    pub raw: String,
    pub synced: bool,
    pub source: String,
    pub fetched_at: i64,
    /// Перевод построчно (по одной строке на строку `raw` после разбора), JSON-массив.
    pub translation: Option<String>,
}

pub fn load(conn: &Connection, track_id: i64) -> Option<Cached> {
    conn.query_row(
        "SELECT raw, synced, source, fetched_at, translation FROM lyrics WHERE track_id=?1",
        [track_id],
        |r| {
            Ok(Cached {
                raw: r.get(0)?,
                synced: r.get::<_, i64>(1)? != 0,
                source: r.get(2)?,
                fetched_at: r.get(3)?,
                translation: r.get(4)?,
            })
        },
    )
    .ok()
}

pub fn store(
    conn: &Connection,
    track_id: i64,
    raw: &str,
    synced: bool,
    source: &str,
) -> rusqlite::Result<()> {
    conn.execute(
        "INSERT INTO lyrics (track_id, raw, synced, source, fetched_at)
         VALUES (?1,?2,?3,?4,?5)
         ON CONFLICT(track_id) DO UPDATE SET
            raw=?2, synced=?3, source=?4, fetched_at=?5, translation=NULL",
        rusqlite::params![track_id, raw, synced as i64, source, crate::db::now()],
    )?;
    Ok(())
}

pub fn store_translation(conn: &Connection, track_id: i64, json: &str) -> rusqlite::Result<()> {
    conn.execute(
        "UPDATE lyrics SET translation=?2 WHERE track_id=?1",
        rusqlite::params![track_id, json],
    )?;
    Ok(())
}

/// Собирает ответ из кеша: разбирает сырой текст и подшивает перевод по индексу строки.
pub fn build(track_id: i64, c: &Cached) -> Lyrics {
    let mut lines = parse_lrc(&c.raw);
    if let Some(t) = c.translation.as_deref().and_then(|t| serde_json::from_str::<Vec<String>>(t).ok())
    {
        for (l, tr) in lines.iter_mut().zip(t) {
            if !tr.is_empty() {
                l.translation = Some(tr);
            }
        }
    }
    Lyrics {
        track_id,
        source: c.source.clone(),
        synced: c.synced && lines.iter().any(|l| l.time_ms.is_some()),
        lines,
        fetched_at: c.fetched_at,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn разбирается_синхронизированный_lrc() {
        let raw = "[ar:Кто-то]\n[00:12.34]Первая строка\n[01:05.5]Вторая\n[02:00]Третья";
        let lines = parse_lrc(raw);
        assert_eq!(lines.len(), 3, "метаданные [ar:] не должны попадать в текст: {lines:?}");
        assert_eq!(lines[0].time_ms, Some(12_340));
        assert_eq!(lines[0].text, "Первая строка");
        assert_eq!(lines[1].time_ms, Some(65_500));
        assert_eq!(lines[2].time_ms, Some(120_000));
    }

    #[test]
    fn несколько_меток_на_строке_дают_несколько_строк() {
        let lines = parse_lrc("[00:10.00][01:10.00]Припев");
        assert_eq!(lines.len(), 2);
        assert_eq!(lines[0].time_ms, Some(10_000));
        assert_eq!(lines[1].time_ms, Some(70_000));
        assert_eq!(lines[1].text, "Припев");
    }

    #[test]
    fn текст_без_меток_разбирается_как_несинхронизированный() {
        let lines = parse_lrc("Просто строка\n\nВторая");
        assert_eq!(lines.len(), 2, "пустые строки выбрасываются");
        assert!(lines.iter().all(|l| l.time_ms.is_none()));
    }

    /// Фикстура вместо живого запроса: тест не должен зависеть от доступности lrclib.
    #[test]
    fn разбирается_ответ_lrclib() {
        let ok = serde_json::json!({
            "id": 1, "trackName": "х", "instrumental": false,
            "plainLyrics": "строка", "syncedLyrics": "[00:01.00]строка"
        });
        assert_eq!(pick(&ok), Some(("[00:01.00]строка".into(), true)));

        let plain_only = serde_json::json!({ "instrumental": false, "syncedLyrics": "", "plainLyrics": "строка" });
        assert_eq!(pick(&plain_only), Some(("строка".into(), false)));

        let instrumental = serde_json::json!({ "instrumental": true, "syncedLyrics": "[00:01.00]х" });
        assert_eq!(pick(&instrumental), None);

        let nothing = serde_json::json!({ "instrumental": false });
        assert_eq!(pick(&nothing), None);
    }

    #[test]
    fn кеш_переживает_запись_и_чтение_а_перевод_подшивается_по_индексу() {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch(crate::db::SCHEMA).unwrap();
        c.execute("INSERT INTO tracks (id, path, title) VALUES (1, '/t.mp3', 'т')", []).unwrap();
        store(&c, 1, "[00:01.00]one\n[00:02.00]two", true, "lrclib").unwrap();
        store_translation(&c, 1, r#"["один","два"]"#).unwrap();

        let got = build(1, &load(&c, 1).unwrap());
        assert!(got.synced);
        assert_eq!(got.lines[0].translation.as_deref(), Some("один"));
        assert_eq!(got.lines[1].translation.as_deref(), Some("два"));

        // Новый текст сбрасывает старый перевод - иначе строки разъедутся.
        store(&c, 1, "[00:01.00]другое", true, "lrclib").unwrap();
        assert!(load(&c, 1).unwrap().translation.is_none());
    }

    /// Живой запрос в lrclib - под `#[ignore]`: обычный `cargo test` не должен зависеть
    /// от сети. Запускать руками: `cargo test живой_запрос -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn живой_запрос_в_lrclib_находит_известную_песню() {
        let got = fetch_lrclib("Bohemian Rhapsody", Some("Queen"), None, 0);
        let (raw, synced) = got.expect("lrclib должен знать эту песню");
        assert!(synced, "ожидался синхронизированный текст");
        assert!(parse_lrc(&raw).len() > 10, "разобралось подозрительно мало строк");
    }

    #[test]
    fn кодирование_параметров_не_ломает_юникод_и_пробелы() {
        assert_eq!(urlencode("a b"), "a%20b");
        assert_eq!(urlencode("Ай"), "%D0%90%D0%B9");
        assert_eq!(urlencode("safe-_.~"), "safe-_.~");
    }
}
