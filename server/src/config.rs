use std::path::PathBuf;

use serde::Deserialize;

/// Конфигурация сервера: config.toml + переопределения из окружения.
#[derive(Debug, Clone, Deserialize)]
pub struct Config {
    #[serde(default = "default_port")]
    pub port: u16,
    #[serde(default)]
    pub music_dirs: Vec<PathBuf>,
    #[serde(default = "default_db")]
    pub db_path: PathBuf,
    #[serde(default = "default_data_dir")]
    pub data_dir: PathBuf,
    #[serde(default = "default_tls")]
    pub tls: bool,
    /// Слежение за music_dirs (inotify/FSEvents/ReadDirectoryChangesW).
    #[serde(default = "default_true")]
    pub watch: bool,
    /// Куда складываются готовые транскоды. Относительный путь считается от data_dir.
    #[serde(default = "default_transcode_cache")]
    pub transcode_cache_dir: PathBuf,
    /// Потолок кеша транскодов в мегабайтах: старейшие файлы вытесняются.
    #[serde(default = "default_cache_mb")]
    pub transcode_cache_mb: u64,
    /// Сколько дней хранить tombstone-записи синхронизации до физического удаления.
    #[serde(default = "default_tombstone_days")]
    pub tombstone_ttl_days: i64,
}

fn default_port() -> u16 {
    4533
}
fn default_db() -> PathBuf {
    PathBuf::from("nami.db")
}
fn default_data_dir() -> PathBuf {
    PathBuf::from(".")
}
fn default_tls() -> bool {
    true
}
fn default_true() -> bool {
    true
}
fn default_transcode_cache() -> PathBuf {
    PathBuf::from("transcode_cache")
}
fn default_cache_mb() -> u64 {
    2048
}
fn default_tombstone_days() -> i64 {
    30
}

impl Default for Config {
    fn default() -> Self {
        Self {
            port: default_port(),
            music_dirs: Vec::new(),
            db_path: default_db(),
            data_dir: default_data_dir(),
            tls: default_tls(),
            watch: default_true(),
            transcode_cache_dir: default_transcode_cache(),
            transcode_cache_mb: default_cache_mb(),
            tombstone_ttl_days: default_tombstone_days(),
        }
    }
}

impl Config {
    /// Абсолютный путь кеша транскодов: относительный считается от data_dir,
    /// чтобы в Docker всё писалось в один том /data.
    pub fn cache_dir(&self) -> PathBuf {
        if self.transcode_cache_dir.is_absolute() {
            self.transcode_cache_dir.clone()
        } else {
            self.data_dir.join(&self.transcode_cache_dir)
        }
    }

    /// Читает config.toml (если есть) и накладывает сверху переменные окружения NAMI_*.
    /// Отсутствующий файл - не ошибка: в Docker всё задаётся окружением.
    pub fn load(path: &std::path::Path) -> crate::Res<Self> {
        let mut cfg = match std::fs::read_to_string(path) {
            Ok(text) => toml::from_str::<Config>(&text)
                .map_err(|e| format!("config.toml: {e}"))?,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Config::default(),
            Err(e) => return Err(format!("не читается {}: {e}", path.display()).into()),
        };

        if let Ok(v) = std::env::var("NAMI_PORT") {
            cfg.port = v.parse().map_err(|_| format!("NAMI_PORT: не число: {v}"))?;
        }
        if let Ok(v) = std::env::var("NAMI_MUSIC_DIRS") {
            cfg.music_dirs = v
                .split(';')
                .filter(|s| !s.trim().is_empty())
                .map(PathBuf::from)
                .collect();
        }
        if let Ok(v) = std::env::var("NAMI_DB_PATH") {
            cfg.db_path = PathBuf::from(v);
        }
        if let Ok(v) = std::env::var("NAMI_DATA_DIR") {
            cfg.data_dir = PathBuf::from(v);
        }
        if let Ok(v) = std::env::var("NAMI_TLS") {
            cfg.tls = matches!(v.as_str(), "1" | "true" | "yes");
        }
        if let Ok(v) = std::env::var("NAMI_WATCH") {
            cfg.watch = matches!(v.as_str(), "1" | "true" | "yes");
        }
        if let Ok(v) = std::env::var("NAMI_TRANSCODE_CACHE_DIR") {
            cfg.transcode_cache_dir = PathBuf::from(v);
        }
        if let Ok(v) = std::env::var("NAMI_TRANSCODE_CACHE_MB") {
            cfg.transcode_cache_mb =
                v.parse().map_err(|_| format!("NAMI_TRANSCODE_CACHE_MB: не число: {v}"))?;
        }
        Ok(cfg)
    }
}
