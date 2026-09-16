use std::path::PathBuf;

use serde::Deserialize;

/// Конфигурация сервера: config.toml + переопределения из окружения.
#[derive(Debug, Clone, Deserialize)]
pub struct Config {
    /// OAuth secrets come from the server environment, never from client requests.
    #[serde(skip)]
    pub discord: Option<crate::discord::DiscordConfig>,
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
            discord: None,
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

        // Если конфиг загружен из директории (например /var/lib/nami/config.toml),
        // привязываем относительные пути к директории конфига
        if let Some(parent) = path.parent() {
            if parent != std::path::Path::new("") {
                if cfg.db_path.is_relative() {
                    cfg.db_path = parent.join(&cfg.db_path);
                }
                if cfg.data_dir.is_relative() && cfg.data_dir == std::path::Path::new(".") {
                    cfg.data_dir = parent.to_path_buf();
                }
            }
        }

        // Если конфиг в текущей папке без файла БД, но существует системная база /var/lib/nami/nami.db, используем её
        if cfg.db_path.is_relative() && !cfg.db_path.exists() {
            let var_lib = PathBuf::from("/var/lib/nami");
            let var_db = var_lib.join(&cfg.db_path);
            if var_db.exists() {
                cfg.db_path = var_db;
                if cfg.data_dir == std::path::Path::new(".") {
                    cfg.data_dir = var_lib;
                }
            }
        }

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
        load_discord_env_file(&cfg.data_dir);
        cfg.discord = crate::discord::DiscordConfig::from_env()?;
        Ok(cfg)
    }
}

/// `nami discord setup` (см. cli.rs) пишет сюда `NAMI_DISCORD_*` парами `KEY=VALUE`, по одной
/// на строку - тот же секрет, что раньше приходилось руками прописывать в окружение службы.
/// Загружается ДО `DiscordConfig::from_env()`, только если переменная ещё не задана снаружи -
/// настоящие env (Docker/systemd EnvironmentFile) всегда выигрывают у этого файла.
fn load_discord_env_file(data_dir: &std::path::Path) {
    let path = data_dir.join("discord.env");
    let Ok(text) = std::fs::read_to_string(&path) else { return };
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if let Some((key, value)) = line.split_once('=') {
            if std::env::var_os(key).is_none() {
                std::env::set_var(key, value);
            }
        }
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
    // Относительный путь считается от рабочего каталога, и у службы Windows это системная
    // папка. Конфигурация там - всегда след прошлой ошибки, а не осознанный выбор: считать её
    // рабочей значит запустить сервер с чужой пустой базой вместо настоящей библиотеки.
    if !cwd_is_system_dir() {
        let path = PathBuf::from("config.toml");
        if path.exists() {
            return path;
        }
    }
    for p in ["/var/lib/nami/config.toml", "/etc/nami/config.toml"] {
        let path = PathBuf::from(p);
        if path.exists() {
            return path;
        }
    }
    default_config_path()
}

/// Куда класть конфигурацию, когда её ещё нет.
///
/// Раньше здесь был относительный "config.toml", то есть рабочий каталог процесса. Это молча
/// ломало всю настройку на Windows: служба стартует с рабочим каталогом `C:\Windows\System32`,
/// а запущенный вручную процесс - с каталогом установки. Мастер настройки писал файл в один
/// каталог, сервер читал из другого, и выбранный порт (и вообще все настройки) не применялись.
///
/// Рядом с исполняемым файлом - каталог, одинаковый при любом способе запуска. На Linux,
/// где пакет кладёт данные в /var/lib/nami, этот каталог уже существует и выигрывает.
/// Является ли рабочий каталог системной папкой Windows. На других системах - никогда.
fn cwd_is_system_dir() -> bool {
    if !cfg!(windows) {
        return false;
    }
    let Ok(cwd) = std::env::current_dir() else { return false };
    let system_root = std::env::var("SystemRoot").unwrap_or_else(|_| "C:\\Windows".into());
    cwd.starts_with(&system_root)
}

pub fn default_config_path() -> PathBuf {
    if PathBuf::from("/var/lib/nami").is_dir() {
        return PathBuf::from("/var/lib/nami/config.toml");
    }
    std::env::current_exe()
        .ok()
        .and_then(|exe| exe.parent().map(|dir| dir.join("config.toml")))
        .unwrap_or_else(|| PathBuf::from("config.toml"))
}

/// Спасение данных, оставшихся в системной папке Windows.
///
/// Версии, где сервер работал службой, но ещё не выставлял себе рабочий каталог, писали
/// конфигурацию и базу в `C:\Windows\System32` - туда их ставил диспетчер служб. Со стороны
/// это выглядело как «переустановка стёрла библиотеку»: сервер не находил конфигурацию рядом с
/// собой, открывал мастер настройки и заводил пустую базу.
///
/// Переносим то, что невосстановимо: конфигурацию, базу и загруженные через приложение файлы.
/// Кеш транскодов, корзину и сертификат не трогаем - они создаются заново.
///
/// Работает один раз: если файл уже лежит на новом месте, ничего не делаем и чужое не трогаем.
#[cfg(windows)]
pub fn rescue_from_system_dir() {
    let target_config = default_config_path();
    if target_config.exists() {
        return;
    }
    let Some(target_dir) = target_config.parent().map(|p| p.to_path_buf()) else { return };
    let system_root = std::env::var("SystemRoot").unwrap_or_else(|_| "C:\\Windows".into());
    let stray_dir = PathBuf::from(system_root).join("System32");
    let stray_config = stray_dir.join("config.toml");
    if !stray_config.is_file() {
        return;
    }
    // Убеждаемся, что файл действительно наш, а не чужой с тем же именем: разбираем его как
    // свою конфигурацию. Не разобрался - не трогаем.
    let Ok(text) = std::fs::read_to_string(&stray_config) else { return };
    if toml::from_str::<Config>(&text).is_err() {
        return;
    }

    let _ = std::fs::create_dir_all(&target_dir);
    for name in ["config.toml", "nami.db", "nami.db-wal", "nami.db-shm"] {
        let from = stray_dir.join(name);
        if from.is_file() {
            let to = target_dir.join(name);
            if std::fs::rename(&from, &to).is_err() {
                // Перенос между томами невозможен - копируем и убираем оригинал.
                if std::fs::copy(&from, &to).is_ok() {
                    let _ = std::fs::remove_file(&from);
                }
            }
        }
    }
    let uploads_from = stray_dir.join("uploads");
    let uploads_to = target_dir.join("uploads");
    if uploads_from.is_dir() && !uploads_to.exists() {
        let _ = std::fs::rename(&uploads_from, &uploads_to);
    }
    tracing::warn!(
        "конфигурация и база перенесены из {} в {}: прежние версии службы писали их в системную папку",
        stray_dir.display(),
        target_dir.display()
    );
}

#[cfg(not(windows))]
pub fn rescue_from_system_dir() {}
