//! Транскодинг через внешний процесс `ffmpeg` + кеш готовых результатов на диске.
//!
//! Библиотеку ffmpeg не линкуем намеренно: для бюджета "1 ядро / 512 МБ" это лишние
//! десятки мегабайт бинарника и отдельный лицензионный разговор, а нужен нам ровно один
//! вызов кодировщика. Требование - `ffmpeg` в PATH.
//!
//! Память ffmpeg - это память ОТДЕЛЬНОГО процесса: в RSS сервера она не видна, но на хосте
//! она есть (порядка 30-60 МБ на активный транскод) и в бюджет её считать надо.

use std::path::{Path, PathBuf};
use std::process::Command;

use serde::Serialize;

/// Профиль транскодинга. Набор закрытый: сервер исполняет то, что просит клиент,
/// но произвольную строку параметров ffmpeg наружу не пускаем - это ключ от чужого CPU.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub struct Profile {
    pub name: &'static str,
    pub codec: &'static str,
    pub bitrate_kbps: u32,
    /// Расширение файла-контейнера и одновременно ключ MIME-типа.
    pub ext: &'static str,
    pub mime: &'static str,
}

/// Opus - основной набор (Android и большинство современных клиентов его умеют),
/// AAC - запасной для тех, кто Opus не понимает.
pub const PROFILES: &[Profile] = &[
    Profile { name: "opus96", codec: "libopus", bitrate_kbps: 96, ext: "opus", mime: "audio/ogg" },
    Profile { name: "opus128", codec: "libopus", bitrate_kbps: 128, ext: "opus", mime: "audio/ogg" },
    Profile { name: "opus192", codec: "libopus", bitrate_kbps: 192, ext: "opus", mime: "audio/ogg" },
    Profile { name: "aac128", codec: "aac", bitrate_kbps: 128, ext: "m4a", mime: "audio/mp4" },
    Profile { name: "aac192", codec: "aac", bitrate_kbps: 192, ext: "m4a", mime: "audio/mp4" },
];

/// Профиль по имени из query-параметра.
pub fn profile(name: &str) -> Option<&'static Profile> {
    PROFILES.iter().find(|p| p.name == name)
}

/// Есть ли ffmpeg в PATH. Проверяется один раз на старте - дёргать `ffmpeg -version`
/// на каждый запрос незачем.
pub fn ffmpeg_available() -> bool {
    Command::new("ffmpeg")
        .arg("-version")
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

/// Имя файла в кеше. size и mtime исходника входят в ключ - правка файла
/// инвалидирует кеш сама, отдельного сторожа не нужно (тот же приём, что в сканере).
fn cache_name(track_id: i64, size: i64, mtime: i64, p: &Profile) -> String {
    format!("{track_id}-{size}-{mtime}-{}.{}", p.name, p.ext)
}

/// Готовый транскод: путь в кеше и признак, считался ли он прямо сейчас.
pub struct Ready {
    pub path: PathBuf,
    /// false - взято из кеша, ffmpeg не запускался.
    pub encoded: bool,
}

/// Возвращает путь к транскоду, посчитав его при необходимости.
///
/// Синхронная и блокирующая (ждёт завершения ffmpeg) - вызывающий обязан звать её
/// из `spawn_blocking`.
///
/// ponytail: отдаём только целиком готовый файл, а не поток по мере кодирования. Зато
/// Range-запросы и перемотка работают из коробки, а второй запрос того же профиля берётся
/// из кеша мгновенно. Потоковую отдачу на лету добавлять, если задержка старта на длинных
/// треках окажется заметной на практике.
///
/// ponytail: два одновременных запроса одного ключа посчитают транскод дважды (каждый в
/// свой временный файл, финальный rename атомарен - гонки за результат нет, есть лишняя
/// работа). Карта "кто уже считает" нужна, когда это станет видно в профиле.
pub fn ensure(
    cache_dir: &Path,
    src: &Path,
    track_id: i64,
    size: i64,
    mtime: i64,
    p: &Profile,
) -> crate::Res<Ready> {
    std::fs::create_dir_all(cache_dir)?;
    let out = cache_dir.join(cache_name(track_id, size, mtime, p));
    if out.is_file() {
        return Ok(Ready { path: out, encoded: false });
    }

    // Во временный файл рядом: оборвавшийся ffmpeg не оставит битый файл под ключом кеша.
    // Расширение у временного файла сохраняем: ffmpeg выбирает контейнер именно по нему.
    let tmp = cache_dir.join(format!(
        "{}.part{}.{}",
        cache_name(track_id, size, mtime, p),
        std::process::id(),
        p.ext
    ));
    let status = Command::new("ffmpeg")
        .args(["-nostdin", "-loglevel", "error", "-y", "-i"])
        .arg(src)
        .args(["-vn", "-map_metadata", "0", "-c:a", p.codec, "-b:a"])
        .arg(format!("{}k", p.bitrate_kbps))
        .arg(&tmp)
        .status();

    match status {
        Ok(s) if s.success() => {}
        Ok(s) => {
            let _ = std::fs::remove_file(&tmp);
            return Err(format!("ffmpeg завершился с кодом {s}").into());
        }
        Err(e) => {
            let _ = std::fs::remove_file(&tmp);
            return Err(format!("ffmpeg не запустился ({e}) - нужен ffmpeg в PATH").into());
        }
    }
    std::fs::rename(&tmp, &out)?;
    Ok(Ready { path: out, encoded: true })
}

/// Вытесняет самые старые файлы, пока кеш не влезет в лимит.
///
/// ponytail: LRU по mtime файла и полный обход директории. При тысячах файлов это
/// десятки миллисекунд раз в транскод - индекс в БД заводить не за чем.
pub fn evict(cache_dir: &Path, limit_mb: u64) -> crate::Res<u64> {
    let limit = limit_mb.saturating_mul(1024 * 1024);
    let mut files: Vec<(std::time::SystemTime, u64, PathBuf)> = Vec::new();
    let mut total = 0u64;
    let entries = match std::fs::read_dir(cache_dir) {
        Ok(e) => e,
        Err(_) => return Ok(0),
    };
    for e in entries.flatten() {
        let Ok(m) = e.metadata() else { continue };
        if !m.is_file() {
            continue;
        }
        total += m.len();
        files.push((m.modified().unwrap_or(std::time::UNIX_EPOCH), m.len(), e.path()));
    }
    if total <= limit {
        return Ok(0);
    }
    files.sort_by_key(|(t, _, _)| *t);
    let mut freed = 0;
    for (_, len, path) in files {
        if total <= limit {
            break;
        }
        if std::fs::remove_file(&path).is_ok() {
            total -= len;
            freed += len;
        }
    }
    Ok(freed)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/sample.wav")
    }

    #[test]
    fn профиль_ищется_по_имени() {
        assert_eq!(profile("opus128").unwrap().bitrate_kbps, 128);
        assert_eq!(profile("aac192").unwrap().codec, "aac");
        assert!(profile("opus999").is_none(), "чужие профили наружу не принимаем");
        assert!(profile("-i /etc/passwd").is_none());
    }

    #[test]
    fn ключ_кеша_меняется_вместе_с_исходником() {
        let p = profile("opus96").unwrap();
        assert_ne!(cache_name(1, 100, 5, p), cache_name(1, 101, 5, p));
        assert_ne!(cache_name(1, 100, 5, p), cache_name(1, 100, 6, p));
        assert_ne!(cache_name(1, 100, 5, p), cache_name(1, 100, 5, profile("opus128").unwrap()));
    }

    #[test]
    fn второй_запрос_берётся_из_кеша_без_вызова_ffmpeg() {
        if !ffmpeg_available() {
            eprintln!("ffmpeg не найден - тест транскодинга пропущен");
            return;
        }
        let dir = std::env::temp_dir().join(format!("nami-transcode-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let p = profile("opus96").unwrap();

        let first = ensure(&dir, &fixture(), 1, 100, 5, p).unwrap();
        assert!(first.encoded, "первый раз должен реально кодировать");
        assert!(first.path.metadata().unwrap().len() > 0);

        let second = ensure(&dir, &fixture(), 1, 100, 5, p).unwrap();
        assert!(!second.encoded, "второй раз ffmpeg запускаться не должен");
        assert_eq!(first.path, second.path);

        // Изменился исходник (другой mtime) - кеш обязан промахнуться.
        let changed = ensure(&dir, &fixture(), 1, 100, 6, p).unwrap();
        assert!(changed.encoded, "после правки файла кеш должен промахнуться");

        // Нулевой лимит вытесняет всё.
        assert!(evict(&dir, 0).unwrap() > 0);
        assert!(!first.path.exists());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
