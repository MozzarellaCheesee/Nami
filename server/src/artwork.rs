//! Обложка трека: встроенная (ID3 APIC / FLAC PICTURE через lofty) либо файл рядом
//! с треком (`cover`/`folder`/`front`/`AlbumArt.{jpg,jpeg,png,webp}`).
//!
//! ponytail: отдаём картинку как есть, с годовым `Cache-Control ... immutable`.
//! Ресайз в несколько размеров и перекодирование в WebP (план §34) не делаем - это
//! отдельная тяжёлая зависимость (`image`) против бюджета «1 ядро / 512 МБ». Добавить,
//! когда появится реальная жалоба на вес обложек в мобильной сети; клиент пока
//! уменьшает сам, а HTTP-кеш закрывает «CDN-подобные заголовки».

use std::path::Path;

pub fn save_override(data_dir: &Path, track_id: i64, bytes: &[u8], mime: &str) -> std::io::Result<()> {
    let dir = data_dir.join("artwork-overrides");
    std::fs::create_dir_all(&dir)?;
    std::fs::write(dir.join(format!("{track_id}.image")), bytes)?;
    std::fs::write(dir.join(format!("{track_id}.mime")), mime)
}

pub fn load_override(data_dir: &Path, track_id: i64) -> Option<(Vec<u8>, String)> {
    let dir = data_dir.join("artwork-overrides");
    let bytes = std::fs::read(dir.join(format!("{track_id}.image"))).ok()?;
    let mime = std::fs::read_to_string(dir.join(format!("{track_id}.mime"))).unwrap_or_else(|_| "image/jpeg".into());
    Some((bytes, mime))
}

pub fn delete_override(data_dir: &Path, track_id: i64) {
    let dir = data_dir.join("artwork-overrides");
    let _ = std::fs::remove_file(dir.join(format!("{track_id}.image")));
    let _ = std::fs::remove_file(dir.join(format!("{track_id}.mime")));
}

/// Имена файлов-обложек рядом с треком, в порядке предпочтения.
const SIDECAR_NAMES: &[&str] = &["cover", "folder", "front", "AlbumArt", "album"];
const SIDECAR_EXTS: &[&str] = &["jpg", "jpeg", "png", "webp"];

/// Определяет тип картинки по её содержимому, а не по заголовку запроса.
///
/// Заголовок пишет клиент, и `image/svg+xml` проходил проверку "начинается с image/":
/// SVG - это документ со скриптом внутри, и отданный с таким Content-Type он выполняется
/// при открытии по прямой ссылке (Subsonic-клиенты, браузер). Поэтому тип берём из
/// сигнатуры файла и разрешаем только растровые форматы.
pub fn sniff_image_mime(bytes: &[u8]) -> Option<&'static str> {
    if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        return Some("image/jpeg");
    }
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        return Some("image/png");
    }
    if bytes.len() >= 12 && bytes.starts_with(b"RIFF") && &bytes[8..12] == b"WEBP" {
        return Some("image/webp");
    }
    if bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a") {
        return Some("image/gif");
    }
    if bytes.len() >= 12 && &bytes[4..8] == b"ftyp" && (&bytes[8..12] == b"heic" || &bytes[8..12] == b"heif") {
        return Some("image/heic");
    }
    None
}

#[cfg(test)]
mod sniff_tests {
    use super::sniff_image_mime;

    #[test]
    fn растровые_форматы_узнаются_а_svg_отвергается() {
        assert_eq!(sniff_image_mime(&[0xFF, 0xD8, 0xFF, 0xE0]), Some("image/jpeg"));
        assert_eq!(sniff_image_mime(b"\x89PNG\r\n\x1a\n\x00"), Some("image/png"));
        assert_eq!(sniff_image_mime(b"RIFF\x00\x00\x00\x00WEBPVP8 "), Some("image/webp"));
        assert_eq!(sniff_image_mime(b"GIF89a\x00"), Some("image/gif"));
        // Ровно тот случай, ради которого проверка и делалась.
        assert_eq!(sniff_image_mime(b"<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"), None);
        assert_eq!(sniff_image_mime(b""), None);
        assert_eq!(sniff_image_mime(b"not an image at all"), None);
    }
}

fn mime_for_ext(ext: &str) -> &'static str {
    match ext.to_ascii_lowercase().as_str() {
        "png" => "image/png",
        "webp" => "image/webp",
        _ => "image/jpeg",
    }
}

/// Байты обложки и её mime-тип. Сначала встроенная картинка, потом файл рядом.
pub fn load(track_path: &str) -> Option<(Vec<u8>, String)> {
    let p = Path::new(track_path);

    if let Ok(tagged) = lofty::read_from_path(p) {
        use lofty::prelude::TaggedFileExt;
        if let Some(pic) = tagged
            .primary_tag()
            .or_else(|| tagged.first_tag())
            .and_then(|t| t.pictures().first())
        {
            let mime = pic
                .mime_type()
                .map(|m| m.as_str().to_string())
                .unwrap_or_else(|| "image/jpeg".into());
            return Some((pic.data().to_vec(), mime));
        }
    }

    let dir = p.parent()?;
    for name in SIDECAR_NAMES {
        for ext in SIDECAR_EXTS {
            let f = dir.join(format!("{name}.{ext}"));
            if let Ok(bytes) = std::fs::read(&f) {
                return Some((bytes, mime_for_ext(ext).to_string()));
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn обложка_из_файла_рядом_с_треком() {
        let dir = std::env::temp_dir().join(format!("nami-art-{}", crate::db::now()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("cover.png"), b"\x89PNG\r\n\x1a\nfake").unwrap();

        let track = dir.join("01 - song.flac");
        std::fs::write(&track, b"not really flac").unwrap();

        let (bytes, mime) = load(track.to_str().unwrap()).unwrap();
        assert_eq!(mime, "image/png");
        assert!(bytes.starts_with(b"\x89PNG"));

        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn нет_обложки_нет_паники() {
        let dir = std::env::temp_dir().join(format!("nami-art-none-{}", crate::db::now()));
        std::fs::create_dir_all(&dir).unwrap();
        let track = dir.join("x.mp3");
        std::fs::write(&track, b"x").unwrap();
        assert!(load(track.to_str().unwrap()).is_none());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn пользовательская_обложка_имеет_приоритет_и_сохраняет_mime() {
        let dir = std::env::temp_dir().join(format!("nami-art-override-{}", crate::db::now()));
        save_override(&dir, 7, b"picture", "image/webp").unwrap();
        let (bytes, mime) = load_override(&dir, 7).unwrap();
        assert_eq!(bytes, b"picture");
        assert_eq!(mime, "image/webp");
        std::fs::remove_dir_all(&dir).ok();
    }
}
