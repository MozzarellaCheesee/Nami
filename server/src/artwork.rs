//! Обложка трека: встроенная (ID3 APIC / FLAC PICTURE через lofty) либо файл рядом
//! с треком (`cover`/`folder`/`front`/`AlbumArt.{jpg,jpeg,png,webp}`).
//!
//! ponytail: отдаём картинку как есть, с годовым `Cache-Control ... immutable`.
//! Ресайз в несколько размеров и перекодирование в WebP (план §34) не делаем - это
//! отдельная тяжёлая зависимость (`image`) против бюджета «1 ядро / 512 МБ». Добавить,
//! когда появится реальная жалоба на вес обложек в мобильной сети; клиент пока
//! уменьшает сам, а HTTP-кеш закрывает «CDN-подобные заголовки».

use std::path::Path;

/// Имена файлов-обложек рядом с треком, в порядке предпочтения.
const SIDECAR_NAMES: &[&str] = &["cover", "folder", "front", "AlbumArt", "album"];
const SIDECAR_EXTS: &[&str] = &["jpg", "jpeg", "png", "webp"];

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
}
