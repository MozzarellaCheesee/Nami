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
    /// Куда складываются файлы, загруженные клиентами. Относительный путь - от data_dir.
    /// Эта папка сканируется наравне с music_dirs, иначе первый же пересканер вычистил бы
    /// загруженное как "файлы, которых больше нет".
    #[serde(default = "default_upload_dir")]
    pub upload_dir: PathBuf,
    /// Сколько дней хранить tombstone-записи синхронизации до физического удаления.
    #[serde(default = "default_tombstone_days")]
    pub tombstone_ttl_days: i64,
    /// Ключ DeepL для перевода лирики. Пусто - сервер просто не переводит
    /// (см. doc-комментарий lyrics.rs: своего ключа сервер не заводит).
    #[serde(default)]
    pub deepl_api_key: String,
    /// Язык перевода лирики по умолчанию, код DeepL.
    #[serde(default = "default_lyrics_lang")]
    pub lyrics_target_lang: String,
    /// Шаблон раскладки загруженных файлов внутри папки загрузок. Плейсхолдеры:
    /// `%artist% %albumartist% %album% %title% %track% %year%`. Пусто - без подпапок,
    /// как было. Расширение добавляется само.
    #[serde(default = "default_import_pattern")]
    pub import_pattern: String,
    /// Внешний адрес сервера (домен, Tailscale MagicDNS) - попадает в QR как `ext=`,
    /// чтобы клиент мог подключаться и вне домашней сети. Пусто - только локальные адреса.
    #[serde(default)]
    pub external_url: String,
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
fn default_upload_dir() -> PathBuf {
    PathBuf::from("uploads")
}
fn default_tombstone_days() -> i64 {
    30
}
fn default_lyrics_lang() -> String {
    "RU".into()
}
fn default_import_pattern() -> String {
    "%albumartist%/%album%/%track% %title%".into()
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
            upload_dir: default_upload_dir(),
            tombstone_ttl_days: default_tombstone_days(),
            deepl_api_key: String::new(),
            lyrics_target_lang: default_lyrics_lang(),
            import_pattern: default_import_pattern(),
            external_url: String::new(),
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

    /// Папка загрузок конкретной библиотеки: у каждой своя, чтобы пересканирование
    /// одной не задевало чужие файлы.
    pub fn upload_dir(&self, library_id: i64) -> PathBuf {
        let base = if self.upload_dir.is_absolute() {
            self.upload_dir.clone()
        } else {
            self.data_dir.join(&self.upload_dir)
        };
        base.join(library_id.to_string())
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
        if let Ok(v) = std::env::var("NAMI_UPLOAD_DIR") {
            cfg.upload_dir = PathBuf::from(v);
        }
        if let Ok(v) = std::env::var("NAMI_DEEPL_API_KEY") {
            cfg.deepl_api_key = v;
        }
        if let Ok(v) = std::env::var("NAMI_LYRICS_TARGET_LANG") {
            cfg.lyrics_target_lang = v;
        }
        if let Ok(v) = std::env::var("NAMI_IMPORT_PATTERN") {
            cfg.import_pattern = v;
        }
        if let Ok(v) = std::env::var("NAMI_EXTERNAL_URL") {
            cfg.external_url = v;
        }
        if let Ok(v) = std::env::var("NAMI_TRANSCODE_CACHE_MB") {
            cfg.transcode_cache_mb =
                v.parse().map_err(|_| format!("NAMI_TRANSCODE_CACHE_MB: не число: {v}"))?;
        }
        Ok(cfg)
    }
}

/// Ищет файл config.toml: сначала проверяет переменную NAMI_CONFIG_PATH,
/// затем текущую директорию, затем /var/lib/nami/config.toml, затем /etc/nami/config.toml.
pub fn find_config_path() -> PathBuf {
    if let Ok(env_path) = std::env::var("NAMI_CONFIG_PATH") {
        let p = PathBuf::from(env_path);
        if p.exists() {
            return p;
        }
    }
    for p in ["config.toml", "/var/lib/nami/config.toml", "/etc/nami/config.toml"] {
        let path = PathBuf::from(p);
        if path.exists() {
            return path;
        }
    }
    PathBuf::from("config.toml")
}
