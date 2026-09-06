uniffi::setup_scaffolding!();

#[derive(uniffi::Record)]
pub struct WordTiming {
    pub word: String,
    pub start_ms: i64,
    pub end_ms: i64,
}

/// Runs whisper.cpp with word-level (token) timestamps over `pcm` -- 16kHz mono f32 samples,
/// caller decodes/resamples the track beforehand (whisper.cpp only accepts this exact format).
/// The known lyrics text isn't fed in here: whisper transcribes fresh and we return its own
/// words with their timestamps; matching those back onto the LRC's already-known line text
/// happens on the Kotlin side, since that's where the LRC data lives.
#[uniffi::export]
pub fn align_words(model_path: String, pcm: Vec<f32>, language: Option<String>) -> Vec<WordTiming> {
    imp::align_words(model_path, pcm, language)
}

#[cfg(target_os = "android")]
mod imp {
    use super::WordTiming;
    use whisper_rs::{FullParams, SamplingStrategy, WhisperContext, WhisperContextParameters};

    pub fn align_words(model_path: String, pcm: Vec<f32>, language: Option<String>) -> Vec<WordTiming> {
        let ctx = match WhisperContext::new_with_params(&model_path, WhisperContextParameters::default()) {
            Ok(ctx) => ctx,
            Err(_) => return Vec::new(),
        };
        let mut state = match ctx.create_state() {
            Ok(state) => state,
            Err(_) => return Vec::new(),
        };

        let mut params = FullParams::new(SamplingStrategy::Greedy { best_of: 1 });
        params.set_token_timestamps(true);
        params.set_print_progress(false);
        params.set_print_special(false);
        params.set_print_realtime(false);
        params.set_print_timestamps(false);
        if let Some(lang) = language.as_deref() {
            params.set_language(Some(lang));
        }

        if state.full(params, &pcm).is_err() {
            return Vec::new();
        }

        let n_segments = state.full_n_segments().unwrap_or(0);
        let mut words = Vec::new();
        for segment in 0..n_segments {
            let n_tokens = state.full_n_tokens(segment).unwrap_or(0);
            for token in 0..n_tokens {
                let text = match state.full_get_token_text(segment, token) {
                    Ok(t) => t,
                    Err(_) => continue,
                };
                // Whisper emits special/control tokens (e.g. "[_BEG_]", timestamp tokens) inline
                // with real words -- they're wrapped in brackets, real words never are.
                let trimmed = text.trim();
                if trimmed.is_empty() || trimmed.starts_with('[') || trimmed.starts_with('<') {
                    continue;
                }
                let data = match state.full_get_token_data(segment, token) {
                    Ok(d) => d,
                    Err(_) => continue,
                };
                // whisper.cpp timestamps are in centiseconds (10ms units).
                words.push(WordTiming {
                    word: trimmed.to_string(),
                    start_ms: data.t0 * 10,
                    end_ms: data.t1 * 10,
                });
            }
        }
        words
    }
}

/// Host build (Windows dev machine, for uniffi-bindgen only) never runs real inference --
/// whisper.cpp is never cross-compiled for it.
#[cfg(not(target_os = "android"))]
mod imp {
    use super::WordTiming;

    pub fn align_words(_model_path: String, _pcm: Vec<f32>, _language: Option<String>) -> Vec<WordTiming> {
        Vec::new()
    }
}
