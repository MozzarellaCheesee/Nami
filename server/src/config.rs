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

impl Default for Config {
    fn default() -> Self {
        Self {
            port: default_port(),
            music_dirs: Vec::new(),
            db_path: default_db(),
            data_dir: default_data_dir(),
            tls: default_tls(),
        }
    }
}

impl Config {
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
        Ok(cfg)
    }
}
