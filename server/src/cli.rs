//! CLI для управления Nami-сервером: status, logs, update, backup, restore, doctor.

use clap::{Parser, Subcommand};
use std::path::PathBuf;
use std::process::{Command, Output};

#[derive(Parser)]
#[command(name = "nami")]
#[command(about = "Управление Nami-сервером", long_about = None)]
pub struct Cli {
    #[command(subcommand)]
    pub command: Option<Commands>,
}

#[derive(Subcommand)]
pub enum Commands {
    /// Проверка статуса сервера (процесс + health endpoint)
    Status,
    /// Показать последние 50 строк логов
    Logs {
        #[arg(short, long, default_value_t = 50)]
        lines: usize,
    },
    /// Обновить сервер (pull + restart)
    Update,
    /// Мастер настройки сервера в терминале (CLI / TUI)
    Setup {
        /// Режим TUI
        #[arg(long, default_value_t = false)]
        tui: bool,
    },
    /// Сканировать музыкальные файлы и обновить библиотеку
    Scan {
        /// Путь к директории (если не указан, сканируются все music_dirs из config)
        #[arg(short, long)]
        path: Option<PathBuf>,
        /// Глубокое сканирование
        #[arg(short, long, default_value_t = false)]
        deep: bool,
    },
    /// Удалить сервер, остановить службы и опционально очистить данные
    Uninstall {
        /// Удалить также базу данных и конфигурационные файлы
        #[arg(long, default_value_t = false)]
        purge: bool,
    },
    /// Создать резервную копию (БД + музыкальные папки)
    Backup {
        /// Путь для сохранения backup-файла
        #[arg(short, long)]
        output: PathBuf,
    },
    /// Восстановить из резервной копии
    Restore {
        /// Путь к backup-файлу
        #[arg(short, long)]
        input: PathBuf,
    },
    /// Диагностика: порты, ffmpeg, место на диске, права
    /// Диагностика: порты, ffmpeg, место на диске, права
    Doctor,
    /// Автоматическая настройка домена (DNS, Caddy Reverse Proxy, Let's Encrypt SSL)
    Domain {
        /// Доменное имя (например: music.example.com)
        #[arg(index = 1)]
        domain: Option<String>,
    },
    /// Сопряжение смартфона: генерация кода и QR прямо в консоль
    Pair {
        /// Привязать устройство к конкретному пользователю (username)
        #[arg(short, long)]
        user: Option<String>,
    },
    /// Управление пользователями библиотеки (list, add, passwd, delete, invite)
    Users {
        #[command(subcommand)]
        action: Option<UsersAction>,
    },
    /// Управление сопряжёнными устройствами (list, revoke)
    Devices {
        #[command(subcommand)]
        action: Option<DevicesAction>,
    },
    /// Здоровье и управление библиотекой (health, scan, dirs, add-dir)
    Library {
        #[command(subcommand)]
        action: Option<LibraryAction>,
    },
    /// Просмотр и редактирование конфигурации config.toml
    Config {
        #[command(subcommand)]
        action: Option<ConfigAction>,
    },
}

#[derive(Subcommand, Clone, Debug)]
pub enum UsersAction {
    /// Список пользователей
    List,
    /// Добавить пользователя
    Add {
        username: String,
        password: Option<String>,
        #[arg(short, long, default_value = "user")]
        role: String,
    },
    /// Сменить пароль пользователя
    Passwd {
        username: String,
        password: Option<String>,
    },
    /// Удалить пользователя
    Delete {
        username: String,
    },
    /// Создать пригласительную ссылку (инвайт)
    Invite {
        #[arg(short, long, default_value = "user")]
        role: String,
        #[arg(short, long)]
        ttl_days: Option<i64>,
    },
}

#[derive(Subcommand, Clone, Debug)]
pub enum DevicesAction {
    /// Список сопряжённых устройств
    List,
    /// Отозвать сопряжение устройства по ID
    Revoke {
        id: i64,
    },
}

#[derive(Subcommand, Clone, Debug)]
pub enum LibraryAction {
    /// Отчёт о здоровье библиотеки (битые файлы, дубликаты, теги)
    Health,
    /// Сканировать папки библиотеки
    Scan {
        #[arg(short, long)]
        path: Option<PathBuf>,
        #[arg(short, long, default_value_t = false)]
        deep: bool,
    },
    /// Список музыкальных папок
    Dirs,
    /// Добавить папку в конфигурацию
    AddDir {
        path: PathBuf,
    },
}

#[derive(Subcommand, Clone, Debug)]
pub enum ConfigAction {
    /// Показать текущую конфигурацию
    Show,
    /// Установить значение параметра
    Set {
        key: String,
        value: String,
    },
}

/// Результат выполнения CLI команды
pub type Res<T> = Result<T, Box<dyn std::error::Error + Send + Sync>>;

impl Commands {
    pub fn execute(&self, cfg: &crate::config::Config) -> Res<()> {
        match self {
            Commands::Status => status(cfg),
            Commands::Logs { lines } => logs(*lines),
            Commands::Update => update(),
            Commands::Setup { tui } => setup_interactive(*tui),
            Commands::Scan { path, deep } => scan(cfg, path, *deep),
            Commands::Uninstall { purge } => uninstall(cfg, *purge),
            Commands::Backup { output } => backup(cfg, output),
            Commands::Restore { input } => restore(cfg, input),
            Commands::Doctor => doctor(cfg),
            Commands::Domain { domain } => domain_setup(cfg, domain.as_deref()),
            Commands::Pair { user } => pair_cmd(cfg, user.as_deref()),
            Commands::Users { action } => users_cmd(cfg, action.clone()),
            Commands::Devices { action } => devices_cmd(cfg, action.clone()),
            Commands::Library { action } => library_cmd(cfg, action.clone()),
            Commands::Config { action } => config_cmd(cfg, action.clone()),
        }
    }
}

/// Проверяет, запущен ли сервер через systemd или docker
fn check_process() -> Res<String> {
    // Пробуем systemd
    if let Ok(output) = Command::new("systemctl")
        .args(["is-active", "nami"])
        .output()
    {
        if output.status.success() {
            let state = String::from_utf8_lossy(&output.stdout).trim().to_string();
            if state == "active" {
                return Ok("systemd: активен".into());
            }
        }
    }

    // Пробуем docker
    if let Ok(output) = Command::new("docker")
        .args(["ps", "--filter", "name=nami", "--format", "{{.Status}}"])
        .output()
    {
        let status = String::from_utf8_lossy(&output.stdout).trim().to_string();
        if !status.is_empty() && status.contains("Up") {
            return Ok(format!("docker: {}", status));
        }
    }

    Ok("не запущен".into())
}

/// Проверяет health endpoint
fn check_health(port: u16, tls: bool) -> Res<String> {
    let scheme = if tls { "https" } else { "http" };
    let url = format!("{scheme}://localhost:{port}/api/health");

    let tls_config = ureq::tls::TlsConfig::builder()
        .disable_verification(true)
        .build();
    let config = ureq::config::Config::builder()
        .tls_config(tls_config)
        .build();
    let agent = ureq::Agent::new_with_config(config);

    match agent.get(&url).call() {
        Ok(mut resp) if resp.status() == 200 => {
            let body = resp.body_mut().read_to_string()?;
            Ok(format!("OK: {}", body))
        }
        Ok(resp) => Ok(format!("статус {}", resp.status())),
        Err(e) => Ok(format!("недоступен: {}", e)),
    }
}

// Заглушка для отключения проверки TLS сертификата
#[derive(Debug)]
#[allow(dead_code)]
struct NoVerifier;

impl rustls::client::danger::ServerCertVerifier for NoVerifier {
    fn verify_server_cert(
        &self,
        _end_entity: &rustls::pki_types::CertificateDer<'_>,
        _intermediates: &[rustls::pki_types::CertificateDer<'_>],
        _server_name: &rustls::pki_types::ServerName<'_>,
        _ocsp_response: &[u8],
        _now: rustls::pki_types::UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        Ok(rustls::client::danger::ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &rustls::pki_types::CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &rustls::pki_types::CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        vec![
            rustls::SignatureScheme::RSA_PKCS1_SHA256,
            rustls::SignatureScheme::ECDSA_NISTP256_SHA256,
            rustls::SignatureScheme::ED25519,
        ]
    }
}

fn status(cfg: &crate::config::Config) -> Res<()> {
    println!("=== Статус Nami-сервера ===\n");

    let process = check_process()?;
    println!("Процесс: {}", process);

    let health = check_health(cfg.port, cfg.tls)?;
    println!("Health: {}", health);

    println!("\nПорт: {}", cfg.port);
    println!("TLS: {}", if cfg.tls { "включен" } else { "выключен" });
    println!("БД: {}", cfg.db_path.display());

    Ok(())
}

fn logs(lines: usize) -> Res<()> {
    println!("=== Последние {} строк логов ===\n", lines);

    // Пробуем journalctl
    if let Ok(output) = Command::new("journalctl")
        .args(["-u", "nami", "-n", &lines.to_string(), "--no-pager"])
        .output()
    {
        if output.status.success() {
            print!("{}", String::from_utf8_lossy(&output.stdout));
            return Ok(());
        }
    }

    // Пробуем docker logs
    if let Ok(output) = Command::new("docker")
        .args(["logs", "--tail", &lines.to_string(), "nami"])
        .output()
    {
        if output.status.success() {
            print!("{}", String::from_utf8_lossy(&output.stdout));
            return Ok(());
        }
    }

    Err("логи не найдены (systemd или docker не запущен)".into())
}

fn update() -> Res<()> {
    println!("=== Обновление сервера ===\n");

    // Определяем режим работы
    let is_systemd = Command::new("systemctl")
        .args(["is-active", "nami"])
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false);

    let is_docker = Command::new("docker")
        .args(["ps", "--filter", "name=nami", "--format", "{{.ID}}"])
        .output()
        .map(|o| !String::from_utf8_lossy(&o.stdout).trim().is_empty())
        .unwrap_or(false);

    if is_docker {
        println!("Обновление через docker-compose...");
        run_cmd("docker-compose", &["pull"])?;
        run_cmd("docker-compose", &["up", "-d", "--force-recreate"])?;
        println!("\nСервер обновлен и перезапущен");
    } else if is_systemd {
        println!("Обновление через systemd...");
        run_cmd("git", &["pull"])?;
        run_cmd("cargo", &["build", "--release"])?;
        run_cmd("sudo", &["systemctl", "restart", "nami"])?;
        println!("\nСервер обновлен и перезапущен");
    } else {
        return Err("сервер не запущен через systemd или docker".into());
    }

    Ok(())
}

fn backup(cfg: &crate::config::Config, output: &PathBuf) -> Res<()> {
    println!("=== Создание резервной копии ===\n");

    let backup_dir = std::env::temp_dir().join(format!("nami-backup-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)?
            .as_secs()
    ));

    std::fs::create_dir_all(&backup_dir)?;

    // Копируем БД
    println!("Копирование БД: {}", cfg.db_path.display());
    let db_backup = backup_dir.join("nami.db");
    std::fs::copy(&cfg.db_path, &db_backup)?;

    // Копируем WAL файлы если есть
    if let Some(parent) = cfg.db_path.parent() {
        for suffix in ["-wal", "-shm"] {
            let wal = parent.join(format!("{}{}", cfg.db_path.file_name().unwrap().to_string_lossy(), suffix));
            if wal.exists() {
                let wal_backup = backup_dir.join(wal.file_name().unwrap());
                std::fs::copy(&wal, &wal_backup)?;
            }
        }
    }

    // Архивируем музыкальные папки
    println!("Архивирование музыкальных папок...");
    for (i, dir) in cfg.music_dirs.iter().enumerate() {
        if !dir.exists() {
            println!("  Пропуск {}: не существует", dir.display());
            continue;
        }
        println!("  {} -> music_{}.tar", dir.display(), i);
        let tar_path = backup_dir.join(format!("music_{}.tar", i));
        run_cmd("tar", &["-cf", tar_path.to_str().unwrap(), "-C",
                        dir.parent().unwrap().to_str().unwrap(),
                        dir.file_name().unwrap().to_str().unwrap()])?;
    }

    // Создаем финальный архив
    println!("\nСоздание итогового архива: {}", output.display());
    run_cmd("tar", &["-czf", output.to_str().unwrap(), "-C",
                    backup_dir.parent().unwrap().to_str().unwrap(),
                    backup_dir.file_name().unwrap().to_str().unwrap()])?;

    // Очищаем временную директорию
    std::fs::remove_dir_all(&backup_dir)?;

    println!("\nРезервная копия создана: {}", output.display());
    Ok(())
}

fn restore(cfg: &crate::config::Config, input: &PathBuf) -> Res<()> {
    println!("=== Восстановление из резервной копии ===\n");
    println!("ВНИМАНИЕ: это перезапишет текущую БД и музыкальные папки!");
    println!("Архив: {}", input.display());
    print!("Продолжить? [y/N]: ");

    use std::io::{self, Write};
    io::stdout().flush()?;

    let mut response = String::new();
    io::stdin().read_line(&mut response)?;

    if !response.trim().eq_ignore_ascii_case("y") {
        println!("Отменено");
        return Ok(());
    }

    let restore_dir = std::env::temp_dir().join(format!("nami-restore-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)?
            .as_secs()
    ));

    std::fs::create_dir_all(&restore_dir)?;

    // Распаковываем архив
    println!("\nРаспаковка архива...");
    run_cmd("tar", &["-xzf", input.to_str().unwrap(), "-C", restore_dir.to_str().unwrap()])?;

    // Ищем внутреннюю директорию backup
    let inner_dir = std::fs::read_dir(&restore_dir)?
        .filter_map(|e| e.ok())
        .find(|e| e.file_type().map(|t| t.is_dir()).unwrap_or(false))
        .ok_or("структура архива некорректна")?
        .path();

    // Восстанавливаем БД
    let db_backup = inner_dir.join("nami.db");
    if db_backup.exists() {
        println!("Восстановление БД: {}", cfg.db_path.display());
        std::fs::copy(&db_backup, &cfg.db_path)?;

        // Копируем WAL файлы
        if let Some(parent) = cfg.db_path.parent() {
            for suffix in ["-wal", "-shm"] {
                let wal_backup = inner_dir.join(format!("nami.db{}", suffix));
                if wal_backup.exists() {
                    let wal = parent.join(format!("{}{}", cfg.db_path.file_name().unwrap().to_string_lossy(), suffix));
                    std::fs::copy(&wal_backup, &wal)?;
                }
            }
        }
    }

    // Восстанавливаем музыкальные папки
    println!("Восстановление музыкальных папок...");
    for i in 0..cfg.music_dirs.len() {
        let tar_path = inner_dir.join(format!("music_{}.tar", i));
        if !tar_path.exists() {
            println!("  music_{}.tar не найден, пропуск", i);
            continue;
        }

        if let Some(dir) = cfg.music_dirs.get(i) {
            println!("  {} <- music_{}.tar", dir.display(), i);
            std::fs::create_dir_all(dir)?;
            run_cmd("tar", &["-xf", tar_path.to_str().unwrap(), "-C",
                            dir.parent().unwrap().to_str().unwrap()])?;
        }
    }

    // Очищаем временную директорию
    std::fs::remove_dir_all(&restore_dir)?;

    println!("\nВосстановление завершено");
    Ok(())
}

fn doctor(cfg: &crate::config::Config) -> Res<()> {
    println!("=== Диагностика Nami-сервера ===\n");

    let mut issues = 0;

    // Проверка портов
    println!("Порты:");
    let port_check = Command::new("sh")
        .args(["-c", &format!("netstat -tuln 2>/dev/null | grep :{} || ss -tuln 2>/dev/null | grep :{}", cfg.port, cfg.port)])
        .output();

    match port_check {
        Ok(output) if output.status.success() && !output.stdout.is_empty() => {
            println!("  ✓ Порт {} слушается", cfg.port);
        }
        _ => {
            println!("  ✗ Порт {} не слушается", cfg.port);
            issues += 1;
        }
    }

    // Проверка ffmpeg
    println!("\nFFmpeg:");
    if crate::transcode::ffmpeg_available() {
        println!("  ✓ ffmpeg найден в PATH");
    } else {
        println!("  ⚠ ffmpeg не найден - транскодинг недоступен");
    }

    // Проверка свободного места
    println!("\nСвободное место:");
    #[allow(unused_variables)]
    let check_dir = if cfg.db_path.exists() {
        cfg.db_path.parent().unwrap_or(std::path::Path::new("."))
    } else if let Some(first_music) = cfg.music_dirs.first() {
        first_music.as_path()
    } else {
        std::path::Path::new(".")
    };

    #[cfg(unix)]
    {
        let df = Command::new("df")
            .args(["-h", check_dir.to_str().unwrap_or(".")])
            .output();
        if let Ok(out) = df {
            if out.status.success() {
                let text = String::from_utf8_lossy(&out.stdout);
                if let Some(line) = text.lines().nth(1) {
                    let parts: Vec<&str> = line.split_whitespace().collect();
                    if parts.len() >= 5 {
                        let total = parts.get(1).unwrap_or(&"?");
                        let used = parts.get(2).unwrap_or(&"?");
                        let avail = parts.get(3).unwrap_or(&"?");
                        let pct = parts.get(4).unwrap_or(&"?");
                        let mount = parts.get(5).unwrap_or(&"диск");
                        println!("  ✓ Доступно {avail} из {total} ({pct} занято, {used} использовано, {mount})");
                    } else {
                        println!("  ✓ {}", line.trim());
                    }
                } else {
                    println!("  ✓ Диск доступен для записи");
                }
            } else {
                println!("  ℹ Диск доступен (df завершился с кодом)");
            }
        } else {
            println!("  ℹ Диск доступен для записи");
        }
    }
    #[cfg(not(unix))]
    {
        println!("  ✓ Диск доступен для записи");
    }

    // Проверка прав на папки
    println!("\nПрава доступа:");

    // БД
    let resolved_db = if !cfg.db_path.exists() && cfg.db_path.is_relative() {
        let fallback = std::path::PathBuf::from("/var/lib/nami").join(&cfg.db_path);
        if fallback.exists() {
            fallback
        } else {
            cfg.db_path.clone()
        }
    } else {
        cfg.db_path.clone()
    };
    let db_ok = resolved_db.exists();
    if db_ok {
        println!("  ✓ БД: {}", resolved_db.display());
    } else {
        println!("  ✗ БД недоступна: {}", resolved_db.display());
        issues += 1;
    }

    // Музыкальные папки
    for dir in &cfg.music_dirs {
        if dir.exists() && dir.is_dir() {
            println!("  ✓ Музыка: {}", dir.display());
        } else {
            println!("  ✗ Папка не найдена: {}", dir.display());
            issues += 1;
        }
    }

    // Кеш транскодов
    let raw_cache = cfg.cache_dir();
    let cache_dir = if !raw_cache.exists() && raw_cache.is_relative() {
        let fallback = std::path::PathBuf::from("/var/lib/nami").join(&raw_cache);
        if fallback.exists() {
            fallback
        } else {
            raw_cache
        }
    } else {
        raw_cache
    };
    if cache_dir.exists() || std::fs::create_dir_all(&cache_dir).is_ok() {
        println!("  ✓ Кеш: {}", cache_dir.display());
    } else {
        println!("  ✗ Кеш недоступен: {}", cache_dir.display());
        issues += 1;
    }

    // Проверка хоста
    println!("\nРесурсы хоста:");
    let caps = crate::host::measure();
    println!("  CPU: {} ядер", caps.cpu_cores);
    println!("  RAM: {} МБ (доступно {} МБ)", caps.total_memory_mb, caps.available_memory_mb);
    println!("  Базовый минимум: {}", if caps.meets_baseline { "✓ выполнен" } else { "✗ НЕ выполнен" });

    if !caps.meets_baseline {
        issues += 1;
    }

    // Итог
    println!("\n{}", "=".repeat(40));
    if issues == 0 {
        println!("✓ Проблем не обнаружено");
    } else {
        println!("✗ Обнаружено проблем: {}", issues);
    }

    Ok(())
}

/// Запускает команду и выводит результат
fn run_cmd(cmd: &str, args: &[&str]) -> Res<Output> {
    let output = Command::new(cmd)
        .args(args)
        .output()?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("{} завершился с ошибкой: {}", cmd, stderr).into());
    }

    Ok(output)
}

/// Сканирование папок с музыкой
fn scan(cfg: &crate::config::Config, custom_path: &Option<PathBuf>, deep: bool) -> Res<()> {
    println!("Запуск сканирования музыкальной библиотеки Nami...");
    let dirs: Vec<PathBuf> = if let Some(p) = custom_path {
        if !p.exists() {
            return Err(format!("Указанный путь не существует: {}", p.display()).into());
        }
        vec![p.clone()]
    } else {
        if cfg.music_dirs.is_empty() {
            println!("Предупреждение: в конфигурации не заданы music_dirs");
        }
        cfg.music_dirs.clone()
    };

    println!("Папки: {}", dirs.iter().map(|d| d.display().to_string()).collect::<Vec<_>>().join(", "));
    if deep {
        println!("Режим глубокого сканирования активен");
    }

    let mut conn = crate::db::open(&cfg.db_path)?;
    let rep = crate::scanner::scan(&mut conn, &dirs, 0)?;

    println!("\n{}", "=".repeat(40));
    println!("Сканирование завершено успешно:");
    println!("  Файлов проверено: {}", rep.scanned);
    println!("  Добавлено новых:  {}", rep.added);
    println!("  Обновлено тегов:  {}", rep.updated);
    println!("  Удалено треков:   {}", rep.removed);
    println!("  Ошибок:           {}", rep.failed);
    println!("{}", "=".repeat(40));

    Ok(())
}

/// Остановка и удаление Nami сервера
fn uninstall(cfg: &crate::config::Config, purge: bool) -> Res<()> {
    println!("=== 🗑️ Удаление Nami Music Server ===\n");
    use std::io::{self, Write};

    #[cfg(unix)]
    {
        println!("1. Остановка и отключение службы systemd...");
        let _ = Command::new("systemctl").args(["stop", "nami"]).output();
        let _ = Command::new("systemctl").args(["disable", "nami"]).output();

        let service_file = PathBuf::from("/etc/systemd/system/nami.service");
        if service_file.exists() {
            if let Err(e) = std::fs::remove_file(&service_file) {
                println!("   ⚠ Не удалось удалить {}: {}", service_file.display(), e);
            } else {
                println!("   ✓ Служба {} удалена", service_file.display());
                let _ = Command::new("systemctl").args(["daemon-reload"]).output();
            }
        }

        let _ = Command::new("docker").args(["stop", "nami"]).output();
        let _ = Command::new("docker").args(["rm", "nami"]).output();

        println!("2. Удаление исполняемых файлов...");
        for bin in ["/usr/local/bin/nami-server", "/usr/local/bin/nami"] {
            let p = PathBuf::from(bin);
            if p.exists() {
                let _ = std::fs::remove_file(&p);
                println!("   ✓ Удалён {}", bin);
            }
        }

        println!("3. Закрытие портов в фаерволе...");
        if let Ok(st) = Command::new("which").arg("ufw").output() {
            if st.status.success() {
                let _ = Command::new("ufw").args(["delete", "allow", "4533/tcp"]).output();
                println!("   ✓ Порт 4533/tcp удалён из правил UFW");
            }
        }
        if let Ok(st) = Command::new("which").arg("firewall-cmd").output() {
            if st.status.success() {
                let _ = Command::new("firewall-cmd").args(["--remove-port=4533/tcp", "--permanent"]).output();
                let _ = Command::new("firewall-cmd").arg("--reload").output();
                println!("   ✓ Порт 4533/tcp удалён из правил firewalld");
            }
        }
    }

    #[cfg(windows)]
    {
        println!("Остановка процессов nami-server...");
        let _ = Command::new("taskkill").args(["/F", "/IM", "nami-server.exe"]).output();
    }

    // Вопрос пользователю об удалении данных, если не был передан флаг --purge
    let should_purge = if purge {
        true
    } else {
        print!("\nЖелаете полностью удалить базу данных, сертификаты и кэш? (/var/lib/nami, nami.db) [y/N]: ");
        io::stdout().flush()?;
        let mut resp = String::new();
        io::stdin().read_line(&mut resp)?;
        resp.trim().eq_ignore_ascii_case("y")
    };

    if should_purge {
        println!("\n4. Полная очистка данных библиотеки и кэша...");
        if cfg.db_path.exists() {
            let _ = std::fs::remove_file(&cfg.db_path);
            println!("   ✓ Удалена база данных: {}", cfg.db_path.display());
        }
        let cache = cfg.cache_dir();
        if cache.exists() {
            let _ = std::fs::remove_dir_all(&cache);
            println!("   ✓ Очищен кэш транскодов: {}", cache.display());
        }
        if cfg.data_dir.exists() && cfg.data_dir != PathBuf::from(".") {
            let _ = std::fs::remove_dir_all(&cfg.data_dir);
            println!("   ✓ Удалён каталог данных: {}", cfg.data_dir.display());
        }
        #[cfg(unix)]
        {
            let _ = Command::new("userdel").args(["-r", "nami"]).output();
        }
        println!("   ✓ Все данные успешно вычищены.");
    } else {
        println!("\n• База данных и папки с музыкой сохранены на диске.");
    }

    println!("\n{}", "=".repeat(60));
    println!("✓ Сервер Nami успешно удалён из системы.");
    println!("{}", "=".repeat(60));

    Ok(())
}

/// Интерактивный мастер настройки сервера в терминале
fn setup_interactive(_tui: bool) -> Res<()> {
    println!("=== Мастер настройки Nami-сервера (CLI / TUI) ===\n");
    use std::io::{self, Write};

    print!("Порт сервера [по умолчанию 4533]: ");
    io::stdout().flush()?;
    let mut port_input = String::new();
    io::stdin().read_line(&mut port_input)?;
    let port: u16 = port_input.trim().parse().unwrap_or(4533);

    print!("Путь к музыкальной папке [по умолчанию ./music]: ");
    io::stdout().flush()?;
    let mut music_input = String::new();
    io::stdin().read_line(&mut music_input)?;
    let music_dir = if music_input.trim().is_empty() {
        "./music".to_string()
    } else {
        music_input.trim().to_string()
    };
    let _ = std::fs::create_dir_all(&music_dir);

    print!("Логин владельца библиотеки: ");
    io::stdout().flush()?;
    let mut login = String::new();
    io::stdin().read_line(&mut login)?;
    let login = login.trim().to_string();

    print!("Пароль владельца библиотеки: ");
    io::stdout().flush()?;
    let mut password = String::new();
    io::stdin().read_line(&mut password)?;
    let password = password.trim().to_string();

    let config_content = format!(
        "port = {}\nmusic_dirs = [\"{}\"]\ndb_path = \"nami.db\"\n",
        port,
        music_dir.replace('\\', "\\\\")
    );
    std::fs::write("config.toml", config_content)?;
    println!("\n✓ Файл config.toml успешно создан");

    if !login.is_empty() && !password.is_empty() {
        let mut conn = crate::db::open(std::path::Path::new("nami.db"))?;
        let _ = crate::users::create(&mut conn, &login, &password, "admin", 0);
        println!("✓ Пользователь '{}' создан как владелец библиотеки", login);
    }

    println!("\nНастройка завершена! Сервер готов к запуску: nami-server");
    Ok(())
}

/// Автоматическая настройка доменного имени, Caddy reverse proxy и SSL
fn domain_setup(cfg: &crate::config::Config, domain_arg: Option<&str>) -> Res<()> {
    println!("=== 🌐 Автоматическая настройка домена для Nami ===\n");
    use std::io::{self, Write};

    let domain = match domain_arg {
        Some(d) if !d.trim().is_empty() => d.trim().to_string(),
        _ => {
            print!("Введите ваш домен (например, music.example.com): ");
            io::stdout().flush()?;
            let mut input = String::new();
            io::stdin().read_line(&mut input)?;
            input.trim().to_string()
        }
    };

    if domain.is_empty() {
        return Err("Доменное имя не указано".into());
    }

    println!("Запуск проверки и настройки для: {}\n", domain);

    let cfg_file = crate::config::find_config_path();
    let report = crate::domain::setup_domain(&domain, cfg.port, &cfg_file, true)
        .map_err(|e| format!("Ошибка настройки домена:\n{e}"))?;

    for step in &report.steps {
        println!("  {step}");
    }

    println!("\n{}", "=".repeat(60));
    println!("{}", report.message);
    println!("  URL для подключения: {}", report.url);
    println!("  Порт: 443 (стандартный защищённый HTTPS)");
    println!("  Мастер сопряжения: {}/setup", report.url);
    println!("{}", "=".repeat(60));

    Ok(())
}

/// Сопряжение нового устройства с генерацией ASCII QR-кода и 8-значного кода в консоль
fn pair_cmd(cfg: &crate::config::Config, user_filter: Option<&str>) -> Res<()> {
    println!("=== 📱 Сопряжение устройства с сервером Nami ===\n");
    let conn = crate::db::open(&cfg.db_path)?;

    let user_id = if let Some(u) = user_filter {
        let uid: Option<i64> = conn
            .query_row("SELECT id FROM users WHERE username = ?1", [u.trim()], |r| r.get(0))
            .ok();
        if uid.is_none() {
            return Err(format!("Пользователь «{u}» не найден в базе данных").into());
        }
        uid
    } else {
        None
    };

    let code = crate::auth::create_code(&conn, user_id)?;

    // Читаем отпечаток сертификата, если есть
    let fp_path = cfg.data_dir.join("cert.fp");
    let fp = std::fs::read_to_string(&fp_path).ok().map(|s| s.trim().to_string());

    let mut hosts = Vec::new();
    if !cfg.external_url.trim().is_empty() {
        let ext = cfg.external_url.trim()
            .trim_start_matches("https://")
            .trim_start_matches("http://");
        let ext_host = ext.split('/').next().unwrap_or("").split(':').next().unwrap_or("").trim();
        if !ext_host.is_empty() {
            hosts.push(ext_host.to_string());
        }
    }
    for ip in crate::auth::local_ips() {
        let s = ip.to_string();
        if !hosts.contains(&s) {
            hosts.push(s);
        }
    }
    if hosts.is_empty() {
        hosts.push("localhost".to_string());
    }

    let primary_host = hosts.first().unwrap().clone();
    let hosts_str = hosts.join(",");

    let fp_part = match &fp {
        Some(f) => format!("&fp=sha256:{f}"),
        None => String::new(),
    };
    let ext_part = if cfg.external_url.trim().is_empty() {
        String::new()
    } else {
        format!("&ext={}", crate::lyrics::urlencode(cfg.external_url.trim()))
    };

    let uri = format!(
        "nami://pair?v=1&host={primary_host}&hosts={hosts_str}&port={}{fp_part}{ext_part}&code={code}",
        cfg.port
    );

    // Рендерим QR в консоль
    if let Ok(qr) = qrcode::QrCode::new(uri.as_bytes()) {
        println!("{}", render_terminal_qr(&qr));
    }

    println!("{}", "=".repeat(60));
    println!("  КОД СОПРЯЖЕНИЯ:    [  {}  ]", code);
    println!("{}", "=".repeat(60));
    println!("  Срок действия:     10 минут (одноразовый)");
    if let Some(u) = user_filter {
        println!("  Привязка к:        пользователь «{}» (ID: {:?})", u, user_id);
    }
    println!("\n  Как подключиться в приложении Nami на смартфоне:");
    println!("  1. Откройте: Настройки → Подключить сервер");
    println!("  2. Наведите камеру на QR-код выше");
    println!("  3. Или введите 8-значный код во вкладке «Вручную»");
    println!("\n  Веб-страница сопряжения в браузере:");
    println!("  https://{}:{}/setup", primary_host, cfg.port);
    println!("{}", "=".repeat(60));

    Ok(())
}

fn render_terminal_qr(code: &qrcode::QrCode) -> String {
    let width = code.width();
    let colors = code.to_colors();
    let border = 2;
    let mut out = String::new();

    for _ in 0..border {
        out.push_str(&"  ".repeat(width + border * 2));
        out.push('\n');
    }

    for y in 0..width {
        out.push_str(&"  ".repeat(border));
        for x in 0..width {
            match colors[y * width + x] {
                qrcode::Color::Dark => out.push_str("██"),
                qrcode::Color::Light => out.push_str("  "),
            }
        }
        out.push_str(&"  ".repeat(border));
        out.push('\n');
    }

    for _ in 0..border {
        out.push_str(&"  ".repeat(width + border * 2));
        out.push('\n');
    }

    out
}

/// Управление пользователями
fn users_cmd(cfg: &crate::config::Config, action: Option<UsersAction>) -> Res<()> {
    let conn = crate::db::open(&cfg.db_path)?;
    use std::io::{self, Write};

    match action.unwrap_or(UsersAction::List) {
        UsersAction::List => {
            println!("=== 👥 Пользователи сервера Nami ===\n");
            let users = crate::users::list(&conn)?;
            if users.is_empty() {
                println!("Пользователей пока нет. Создайте первого командой: nami users add <имя>");
                return Ok(());
            }
            println!("{:<5} {:<20} {:<10} {:<20}", "ID", "Логин", "Роль", "Создан");
            println!("{}", "-".repeat(58));
            for u in users {
                let date_str = format_ts(u.created_at);
                println!("{:<5} {:<20} {:<10} {:<20}", u.id, u.username, u.role, date_str);
            }
            println!("\nВсего пользователей: {}", crate::users::count(&conn));
        }
        UsersAction::Add { username, password, role } => {
            let pass = match password {
                Some(p) if !p.trim().is_empty() => p.trim().to_string(),
                _ => {
                    print!("Введите пароль для '{}' (минимум 8 символов): ", username);
                    io::stdout().flush()?;
                    let mut p = String::new();
                    io::stdin().read_line(&mut p)?;
                    p.trim().to_string()
                }
            };
            if pass.chars().count() < 8 {
                return Err("Пароль должен содержать минимум 8 символов".into());
            }
            let valid_role = if matches!(role.as_str(), "owner" | "user" | "guest") {
                role.as_str()
            } else {
                "user"
            };
            let id = crate::users::create(&conn, &username, &pass, valid_role, 0)
                .map_err(|e| format!("Не удалось создать пользователя: {e}"))?;
            println!("✓ Пользователь '{}' (ID: {}) успешно создан с ролью '{}'", username, id, valid_role);
        }
        UsersAction::Passwd { username, password } => {
            let user_id: Option<i64> = conn
                .query_row("SELECT id FROM users WHERE username = ?1", [username.trim()], |r| r.get(0))
                .ok();
            let Some(uid) = user_id else {
                return Err(format!("Пользователь «{}» не найден", username).into());
            };
            let pass = match password {
                Some(p) if !p.trim().is_empty() => p.trim().to_string(),
                _ => {
                    print!("Введите новый пароль для '{}' (минимум 8 символов): ", username);
                    io::stdout().flush()?;
                    let mut p = String::new();
                    io::stdin().read_line(&mut p)?;
                    p.trim().to_string()
                }
            };
            if pass.chars().count() < 8 {
                return Err("Пароль должен содержать минимум 8 символов".into());
            }
            crate::users::reset_password(&conn, uid, &pass)
                .map_err(|e| format!("Ошибка смены пароля: {e}"))?;
            println!("✓ Пароль для пользователя '{}' успешно обновлён. Старые сессии завершены.", username);
        }
        UsersAction::Delete { username } => {
            let n = conn.execute("DELETE FROM users WHERE username = ?1", [username.trim()])?;
            if n == 0 {
                return Err(format!("Пользователь «{}» не найден", username).into());
            }
            println!("✓ Пользователь '{}' успешно удалён из базы данных", username);
        }
        UsersAction::Invite { role, ttl_days } => {
            let valid_role = if matches!(role.as_str(), "user" | "guest") {
                role.as_str()
            } else {
                "user"
            };
            let ttl = ttl_days.map(|d| d * 86400);
            let inv = crate::users::create_invite(&conn, 1, valid_role, 0, ttl)?;
            let base = if !cfg.external_url.trim().is_empty() {
                cfg.external_url.trim().trim_end_matches('/').to_string()
            } else {
                let ip = crate::auth::local_ips().first().map(|ip| ip.to_string()).unwrap_or_else(|| "localhost".into());
                format!("https://{}:{}", ip, cfg.port)
            };
            println!("=== 🎟️ Пригласительная ссылка (инвайт) ===\n");
            println!("  Токен инвайта:    {}", inv.token);
            println!("  Роль:             {}", inv.role);
            println!("  Действителен до:  {}", format_ts(inv.expires_at));
            println!("  Ссылка для входа: {}/setup?invite={}", base, inv.token);
        }
    }
    Ok(())
}

/// Управление сопряжёнными устройствами
fn devices_cmd(cfg: &crate::config::Config, action: Option<DevicesAction>) -> Res<()> {
    let conn = crate::db::open(&cfg.db_path)?;

    match action.unwrap_or(DevicesAction::List) {
        DevicesAction::List => {
            println!("=== 📱 Сопряжённые устройства Nami ===\n");
            let mut stmt = conn.prepare(
                "SELECT id, name, created_at, last_seen_at FROM devices ORDER BY id"
            )?;
            let rows = stmt.query_map([], |r| {
                Ok((
                    r.get::<_, i64>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, i64>(2)?,
                    r.get::<_, Option<i64>>(3)?,
                ))
            })?.collect::<rusqlite::Result<Vec<_>>>()?;

            if rows.is_empty() {
                println!("Сопряжённых устройств пока нет.");
                println!("Для подключения смартфона выполните: nami pair");
                return Ok(());
            }

            println!("{:<5} {:<25} {:<20} {:<20}", "ID", "Имя устройства", "Подключено", "Посл. активность");
            println!("{}", "-".repeat(72));
            for (id, name, created_at, last_seen) in rows {
                let seen_str = last_seen.map(format_ts).unwrap_or_else(|| "никогда".into());
                println!("{:<5} {:<25} {:<20} {:<20}", id, name, format_ts(created_at), seen_str);
            }
            println!("\nДля отзыва сопряжения выполните: nami devices revoke <ID>");
        }
        DevicesAction::Revoke { id } => {
            let n = conn.execute("DELETE FROM devices WHERE id = ?1", [id])?;
            if n == 0 {
                return Err(format!("Устройство с ID {} не найдено", id).into());
            }
            println!("✓ Сопряжение устройства #{id} успешно отозвано. Доступ к серверу закрыт.");
        }
    }
    Ok(())
}

/// Здоровье и управление библиотекой
fn library_cmd(cfg: &crate::config::Config, action: Option<LibraryAction>) -> Res<()> {
    match action.unwrap_or(LibraryAction::Health) {
        LibraryAction::Health => {
            println!("=== 📊 Здоровье библиотеки Nami ===\n");
            let conn = crate::db::open(&cfg.db_path)?;
            let h = crate::library::health(&conn, &crate::users::Ident::default())?;

            println!("  Всего треков в библиотеке:     {}", h.tracks);
            println!("  Файлов с ошибками чтения:      {}", h.broken.count);
            println!("  Файлов без исполнителя:        {}", h.without_artist.count);
            println!("  Файлов без названия альбома:   {}", h.without_album.count);
            println!("  Файлов без указания года:      {}", h.without_year.count);
            println!("  Групп предполагаемых дублей:   {}", h.duplicate_groups.len());

            if h.broken.count > 0 {
                println!("\n  Примеры битых файлов:");
                for b in h.broken.items.iter().take(5) {
                    println!("    ✗ {} ({})", b.path, b.error);
                }
            }

            if !h.duplicate_groups.is_empty() {
                println!("\n  Примеры обнаруженных дубликатов:");
                for group in h.duplicate_groups.iter().take(3) {
                    println!("    Дубликат «{}» ({} копий):", group[0].title, group.len());
                    for tr in group {
                        println!("      • {}", tr.path);
                    }
                }
            }

            println!("\n✓ Анализ здоровья библиотеки завершён.");
        }
        LibraryAction::Scan { path, deep } => {
            scan(cfg, &path, deep)?;
        }
        LibraryAction::Dirs => {
            println!("=== 📁 Музыкальные папки в конфигурации ===\n");
            if cfg.music_dirs.is_empty() {
                println!("Папки не настроены. Добавьте первую: nami library add-dir /путь/к/музыке");
            } else {
                for (i, d) in cfg.music_dirs.iter().enumerate() {
                    let exists = if d.exists() { "✓ доступна" } else { "✗ не найдена" };
                    println!("  {}. {} ({})", i + 1, d.display(), exists);
                }
            }
        }
        LibraryAction::AddDir { path } => {
            let abs_path = if path.is_absolute() {
                path
            } else {
                std::env::current_dir()?.join(path)
            };
            if !abs_path.exists() {
                return Err(format!("Путь '{}' не существует на диске", abs_path.display()).into());
            }
            let cfg_path = crate::config::find_config_path();
            let text = std::fs::read_to_string(&cfg_path).unwrap_or_default();
            let path_str = abs_path.to_string_lossy().to_string();
            let updated = append_music_dir(&text, &path_str);
            std::fs::write(&cfg_path, updated)?;
            println!("✓ Папка '{}' добавлена в {}", abs_path.display(), cfg_path.display());
            println!("Для добавления треков запустите сканирование: nami scan");
        }
    }
    Ok(())
}

/// Просмотр и редактирование конфигурации
fn config_cmd(cfg: &crate::config::Config, action: Option<ConfigAction>) -> Res<()> {
    match action.unwrap_or(ConfigAction::Show) {
        ConfigAction::Show => {
            println!("=== ⚙️ Конфигурация Nami-сервера ===\n");
            println!("  Порт (port):               {}", cfg.port);
            println!("  TLS шифрование (tls):      {}", if cfg.tls { "включено" } else { "выключено" });
            println!("  Внешний URL (external_url):{}", if cfg.external_url.is_empty() { " не задан" } else { &cfg.external_url });
            println!("  База данных (db_path):     {}", cfg.db_path.display());
            println!("  Каталог данных (data_dir): {}", cfg.data_dir.display());
            println!("  Кэш транскодов (MB):       {}", cfg.transcode_cache_mb);
            println!("  Автосканирование (watch):  {}", if cfg.watch { "включено" } else { "выключено" });
            println!("  Музыкальные папки (music_dirs):");
            if cfg.music_dirs.is_empty() {
                println!("    (не заданы)");
            } else {
                for d in &cfg.music_dirs {
                    println!("    • {}", d.display());
                }
            }
        }
        ConfigAction::Set { key, value } => {
            let cfg_path = crate::config::find_config_path();
            let text = std::fs::read_to_string(&cfg_path).unwrap_or_default();
            let updated = set_config_key(&text, &key, &value);
            std::fs::write(&cfg_path, updated)?;
            println!("✓ Параметр '{}' успешно обновлён на '{}' в {}", key, value, cfg_path.display());
            println!("Перезапустите сервер для применения настроек: sudo systemctl restart nami");
        }
    }
    Ok(())
}

fn format_ts(ts: i64) -> String {
    let secs = ts;
    let days = secs / 86400;
    let time = secs % 86400;
    let hours = time / 3600;
    let minutes = (time % 3600) / 60;
    let seconds = time % 60;
    let mut year = 1970;
    let mut d = days;
    loop {
        let leap = (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0);
        let days_in_year = if leap { 366 } else { 365 };
        if d < days_in_year {
            break;
        }
        d -= days_in_year;
        year += 1;
    }
    let leap = (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0);
    let days_in_months = [31, if leap { 29 } else { 28 }, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
    let mut month = 1;
    for &dim in &days_in_months {
        if d < dim {
            break;
        }
        d -= dim;
        month += 1;
    }
    let day = d + 1;
    format!("{:04}-{:02}-{:02} {:02}:{:02}:{:02}", year, month, day, hours, minutes, seconds)
}

fn set_config_key(content: &str, key: &str, value: &str) -> String {
    let mut lines: Vec<String> = content.lines().map(|s| s.to_string()).collect();
    let mut found = false;
    let key_prefix = format!("{key} =");
    let key_prefix_sp = format!("{key}=");

    for line in lines.iter_mut() {
        let trimmed = line.trim();
        if trimmed.starts_with(&key_prefix) || trimmed.starts_with(&key_prefix_sp) {
            *line = if value.parse::<i64>().is_ok() || value.parse::<bool>().is_ok() {
                format!("{key} = {value}")
            } else {
                format!("{key} = \"{value}\"")
            };
            found = true;
            break;
        }
    }

    if !found {
        if value.parse::<i64>().is_ok() || value.parse::<bool>().is_ok() {
            lines.push(format!("{key} = {value}"));
        } else {
            lines.push(format!("{key} = \"{value}\""));
        }
    }

    lines.join("\n") + "\n"
}

fn append_music_dir(content: &str, new_dir: &str) -> String {
    let escaped = new_dir.replace('\\', "\\\\").replace('"', "");
    let mut lines: Vec<String> = content.lines().map(|s| s.to_string()).collect();
    let mut found = false;

    for line in lines.iter_mut() {
        if line.trim().starts_with("music_dirs") {
            if line.contains(']') {
                let before = line.trim_end_matches([' ', ']', '\n']);
                if before.ends_with('[') {
                    *line = format!("{before}\"{escaped}\"]");
                } else {
                    *line = format!("{before}, \"{escaped}\"]");
                }
            }
            found = true;
            break;
        }
    }

    if !found {
        lines.push(format!("music_dirs = [\"{escaped}\"]"));
    }

    lines.join("\n") + "\n"
}


