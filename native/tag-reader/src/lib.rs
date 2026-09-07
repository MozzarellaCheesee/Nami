use lofty::file::{AudioFile, TaggedFileExt};
use lofty::probe::Probe;
use lofty::tag::{Accessor, Tag};

uniffi::setup_scaffolding!();

#[derive(uniffi::Record, Debug, PartialEq)]
pub struct TagResult {
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub album_artist: Option<String>,
    pub track_no: Option<u32>,
    pub disc_no: Option<u32>,
    pub genre: Option<String>,
    pub year: Option<u32>,
    pub duration_ms: u64,
    pub artwork: Option<Vec<u8>>,
    pub artwork_mime: Option<String>,
    /// Embedded USLT (ID3v2) / LYRICS (Vorbis comment) / \xa9lyr (MP4) tag, raw text -- often a
    /// full LRC-formatted synced blob pasted into the tag by whoever ripped the file, sometimes
    /// just plain unsynced text. Caller decides what to do with it (LrcParser only keeps synced
    /// lines, so plain text quietly yields nothing rather than crashing).
    pub lyrics: Option<String>,
}

fn map_tag(tag: &Tag, duration_ms: u64) -> TagResult {
    let picture = tag.pictures().first();
    TagResult {
        title: tag.title().map(|s| s.to_string()),
        artist: tag.artist().map(|s| s.to_string()),
        album: tag.album().map(|s| s.to_string()),
        album_artist: tag.get_string(&lofty::tag::ItemKey::AlbumArtist).map(|s| s.to_string()),
        track_no: tag.track(),
        disc_no: tag.disk(),
        genre: tag.genre().map(|s| s.to_string()),
        year: tag.year(),
        duration_ms,
        artwork: picture.map(|p| p.data().to_vec()),
        artwork_mime: picture.and_then(|p| p.mime_type()).map(|m| m.to_string()),
        lyrics: tag.get_string(&lofty::tag::ItemKey::Lyrics).map(|s| s.to_string()),
    }
}

#[uniffi::export]
pub fn read_tags(path: String) -> Option<TagResult> {
    let tagged_file = Probe::open(&path).ok()?.read().ok()?;
    let duration_ms = tagged_file.properties().duration().as_millis() as u64;
    let tag = tagged_file.primary_tag().or_else(|| tagged_file.first_tag())?;
    Some(map_tag(tag, duration_ms))
}

#[cfg(test)]
mod tests {
    use super::*;
    use lofty::tag::{ItemKey, ItemValue, Tag, TagItem, TagType};

    #[test]
    fn maps_title_and_artist_from_tag() {
        let mut tag = Tag::new(TagType::Id3v2);
        tag.push(TagItem::new(ItemKey::TrackTitle, ItemValue::Text("Window View".to_string())));
        tag.push(TagItem::new(ItemKey::TrackArtist, ItemValue::Text("Farewell225".to_string())));

        let result = map_tag(&tag, 180_000);

        assert_eq!(result.title, Some("Window View".to_string()));
        assert_eq!(result.artist, Some("Farewell225".to_string()));
        assert_eq!(result.duration_ms, 180_000);
        assert_eq!(result.artwork, None);
    }

    #[test]
    fn missing_fields_map_to_none() {
        let tag = Tag::new(TagType::Id3v2);

        let result = map_tag(&tag, 1000);

        assert_eq!(result.title, None);
        assert_eq!(result.genre, None);
        assert_eq!(result.track_no, None);
    }
}
