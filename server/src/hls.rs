//! HLS с адаптивным битрейтом (§33, пункт 3).
//!
//! Мастер-плейлист перечисляет два AAC-варианта (128 и 192 кбит/с). Контейнер сегментов -
//! MPEG-TS: он совместим со всем, а Opus в TS не кладут (нужен fMP4 - лишняя возня на
//! первую итерацию). Каждый вариант ffmpeg нарезает ОДИН раз в кеш, дальше сегменты и
//! `index.m3u8` отдаются статикой, перемотку/Range берёт ServeFile.
//!
//! ponytail: HLS-кеш пока не вытесняется (в отличие от кеша транскодов). HLS - опция под
//! адаптивный битрейт, включают её редко; сторож по размеру добавить, когда каталог
//! реально начнёт занимать место.

use std::path::{Path, PathBuf};
use std::process::Command;

/// Варианты мастер-плейлиста: имя профиля -> (битрейт кбит/с, примерный BANDWIDTH).
pub const VARIANTS: &[(&str, u32, u32)] = &[("aac128", 128, 160_000), ("aac192", 192, 232_000)];

/// Длина сегмента. 6 с - обычный для VOD-HLS компромисс задержки и накладных расходов.
const SEGMENT_SECS: u32 = 6;

/// Разрешён ли профиль как HLS-вариант.
pub fn is_variant(profile: &str) -> bool {
    VARIANTS.iter().any(|(n, _, _)| *n == profile)
}

/// Имя сегмента должно быть `s<цифры>.ts` - проверка цифр заодно отсекает `..` и
/// разделители пути.
pub fn valid_segment(name: &str) -> bool {
    name.len() >= 7
        && name.starts_with('s')
        && name.ends_with(".ts")
        && name[1..name.len() - 3].chars().all(|c| c.is_ascii_digit())
}

/// Мастер-плейлист трека - статический, от файла не зависит.
pub fn master_playlist() -> String {
    let mut s = String::from("#EXTM3U\n#EXT-X-VERSION:3\n");
    for (name, _, bandwidth) in VARIANTS {
        s.push_str(&format!(
            "#EXT-X-STREAM-INF:BANDWIDTH={bandwidth},CODECS=\"mp4a.40.2\"\n{name}/index.m3u8\n"
        ));
    }
    s
}

/// Каталог кеша одного варианта. size+mtime в ключе - правка файла инвалидирует сама.
fn variant_dir(cache_dir: &Path, id: i64, size: i64, mtime: i64, profile: &str) -> PathBuf {
    cache_dir.join("hls").join(format!("{id}-{size}-{mtime}-{profile}"))
}

/// Готовит (при необходимости нарезает) HLS-вариант, возвращает его каталог с
/// `index.m3u8` и сегментами. Синхронная и блокирующая - звать из `spawn_blocking`.
pub fn ensure(
    cache_dir: &Path,
    src: &Path,
    id: i64,
    size: i64,
    mtime: i64,
    profile: &str,
    bitrate_kbps: u32,
) -> crate::Res<PathBuf> {
    let dir = variant_dir(cache_dir, id, size, mtime, profile);
    if dir.join("index.m3u8").is_file() {
        return Ok(dir);
    }

    let tmp = dir.with_file_name(format!(
        "{}.part{}",
        dir.file_name().unwrap().to_string_lossy(),
        std::process::id()
    ));
    let _ = std::fs::remove_dir_all(&tmp);
    std::fs::create_dir_all(&tmp)?;

    let status = Command::new("ffmpeg")
        .args(["-nostdin", "-loglevel", "error", "-y", "-i"])
        .arg(src)
        .args([
            "-vn",
            "-c:a",
            "aac",
            "-b:a",
            &format!("{bitrate_kbps}k"),
            "-f",
            "hls",
            "-hls_time",
            &SEGMENT_SECS.to_string(),
            "-hls_playlist_type",
            "vod",
            "-hls_flags",
            "independent_segments",
            "-hls_segment_type",
            "mpegts",
            "-hls_segment_filename",
        ])
        .arg(tmp.join("s%04d.ts"))
        .arg(tmp.join("index.m3u8"))
        .status();

    match status {
        Ok(s) if s.success() => {}
        Ok(s) => {
            let _ = std::fs::remove_dir_all(&tmp);
            return Err(format!("ffmpeg завершился с кодом {s}").into());
        }
        Err(e) => {
            let _ = std::fs::remove_dir_all(&tmp);
            return Err(format!("ffmpeg не запустился ({e}) - нужен ffmpeg в PATH").into());
        }
    }

    if let Some(parent) = dir.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::rename(&tmp, &dir)?;
    Ok(dir)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn имя_сегмента_валидируется() {
        assert!(valid_segment("s0000.ts"));
        assert!(valid_segment("s0421.ts"));
        assert!(!valid_segment("s12.ts"));
        assert!(!valid_segment("../x.ts"));
        assert!(!valid_segment("s0000.tsx"));
        assert!(!valid_segment("index.m3u8"));
    }

    #[test]
    fn мастер_плейлист_перечисляет_варианты() {
        let m = master_playlist();
        assert!(m.starts_with("#EXTM3U"));
        assert!(m.contains("aac128/index.m3u8"));
        assert!(m.contains("aac192/index.m3u8"));
        assert!(m.contains("BANDWIDTH="));
    }

    #[test]
    fn вариантом_считается_только_свой_профиль() {
        assert!(is_variant("aac128"));
        assert!(!is_variant("opus96"));
        assert!(!is_variant("../../etc/passwd"));
    }
}
