//! Метрики Prometheus и health endpoint.
//!
//! ponytail: счётчики в памяти (AtomicU64), без prometheus-клиентской библиотеки.
//! Формат Prometheus text: `metric_name{label="value"} count`.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

/// Глобальные метрики сервера
#[derive(Clone)]
pub struct Metrics {
    /// Время запуска сервера (UNIX timestamp)
    pub start_time: u64,
    /// Количество HTTP запросов (по статус-кодам)
    pub http_requests_2xx: Arc<AtomicU64>,
    pub http_requests_4xx: Arc<AtomicU64>,
    pub http_requests_5xx: Arc<AtomicU64>,
    /// Количество активных WebSocket соединений
    pub ws_connections: Arc<AtomicU64>,
    /// Количество стримов аудио
    pub audio_streams: Arc<AtomicU64>,
    /// Количество запросов на транскодинг
    pub transcode_requests: Arc<AtomicU64>,
    /// Количество треков в библиотеке
    pub library_tracks: Arc<AtomicU64>,
    /// Количество сканирований библиотеки
    pub scans_total: Arc<AtomicU64>,
}

impl Metrics {
    pub fn new() -> Self {
        Self {
            start_time: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_secs(),
            http_requests_2xx: Arc::new(AtomicU64::new(0)),
            http_requests_4xx: Arc::new(AtomicU64::new(0)),
            http_requests_5xx: Arc::new(AtomicU64::new(0)),
            ws_connections: Arc::new(AtomicU64::new(0)),
            audio_streams: Arc::new(AtomicU64::new(0)),
            transcode_requests: Arc::new(AtomicU64::new(0)),
            library_tracks: Arc::new(AtomicU64::new(0)),
            scans_total: Arc::new(AtomicU64::new(0)),
        }
    }

    /// Инкремент счётчика HTTP-запросов по статус-коду
    pub fn record_http(&self, status: u16) {
        match status {
            200..=299 => self.http_requests_2xx.fetch_add(1, Ordering::Relaxed),
            400..=499 => self.http_requests_4xx.fetch_add(1, Ordering::Relaxed),
            500..=599 => self.http_requests_5xx.fetch_add(1, Ordering::Relaxed),
            _ => return,
        };
    }

    /// Формат Prometheus text
    pub fn export(&self) -> String {
        format!(
            r#"# HELP nami_start_timestamp_seconds Время запуска сервера
# TYPE nami_start_timestamp_seconds gauge
nami_start_timestamp_seconds {}

# HELP nami_http_requests_total Количество HTTP запросов
# TYPE nami_http_requests_total counter
nami_http_requests_total{{status="2xx"}} {}
nami_http_requests_total{{status="4xx"}} {}
nami_http_requests_total{{status="5xx"}} {}

# HELP nami_ws_connections_active Активные WebSocket соединения
# TYPE nami_ws_connections_active gauge
nami_ws_connections_active {}

# HELP nami_audio_streams_total Количество стримов аудио
# TYPE nami_audio_streams_total counter
nami_audio_streams_total {}

# HELP nami_transcode_requests_total Количество запросов на транскодинг
# TYPE nami_transcode_requests_total counter
nami_transcode_requests_total {}

# HELP nami_library_tracks_total Треков в библиотеке
# TYPE nami_library_tracks_total gauge
nami_library_tracks_total {}

# HELP nami_scans_total Количество сканирований библиотеки
# TYPE nami_scans_total counter
nami_scans_total {}
"#,
            self.start_time,
            self.http_requests_2xx.load(Ordering::Relaxed),
            self.http_requests_4xx.load(Ordering::Relaxed),
            self.http_requests_5xx.load(Ordering::Relaxed),
            self.ws_connections.load(Ordering::Relaxed),
            self.audio_streams.load(Ordering::Relaxed),
            self.transcode_requests.load(Ordering::Relaxed),
            self.library_tracks.load(Ordering::Relaxed),
            self.scans_total.load(Ordering::Relaxed),
        )
    }
}

impl Default for Metrics {
    fn default() -> Self {
        Self::new()
    }
}
