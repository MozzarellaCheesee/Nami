use lofty::file::{AudioFile, TaggedFileExt};
use lofty::probe::Probe;
use lofty::properties::FileProperties;
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
    /// Этап 4's "Аудиотракт" screen needs these to show the file's real format, not just its
    /// container extension -- None for lossy formats lofty can't report a bit depth for.
    pub sample_rate_hz: Option<u32>,
    pub bit_depth: Option<u8>,
    pub channels: Option<u8>,
}

fn map_tag(tag: &Tag, properties: &FileProperties) -> TagResult {
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
        duration_ms: properties.duration().as_millis() as u64,
        artwork: picture.map(|p| p.data().to_vec()),
        artwork_mime: picture.and_then(|p| p.mime_type()).map(|m| m.to_string()),
        lyrics: tag.get_string(&lofty::tag::ItemKey::Lyrics).map(|s| s.to_string()),
        sample_rate_hz: properties.sample_rate(),
        bit_depth: properties.bit_depth(),
        channels: properties.channels(),
    }
}

#[uniffi::export]
pub fn read_tags(path: String) -> Option<TagResult> {
    let tagged_file = Probe::open(&path).ok()?.read().ok()?;
    let properties = tagged_file.properties().clone();
    let tag = tagged_file.primary_tag().or_else(|| tagged_file.first_tag())?;
    Some(map_tag(tag, &properties))
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

        let result = map_tag(&tag, &FileProperties::default());

        assert_eq!(result.title, Some("Window View".to_string()));
        assert_eq!(result.artist, Some("Farewell225".to_string()));
        assert_eq!(result.artwork, None);
    }

    #[test]
    fn missing_fields_map_to_none() {
        let tag = Tag::new(TagType::Id3v2);

        let result = map_tag(&tag, &FileProperties::default());

        assert_eq!(result.title, None);
        assert_eq!(result.genre, None);
        assert_eq!(result.track_no, None);
    }
}
