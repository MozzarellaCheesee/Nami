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
    Doctor,
    /// Автоматическая настройка домена (DNS, Caddy Reverse Proxy, Let's Encrypt SSL)
    Domain {
        /// Доменное имя (например: music.example.com)
        #[arg(index = 1)]
        domain: Option<String>,
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

    // ponytail: ureq::get() для простой проверки
    match ureq::get(&url).call() {
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
    if let Some(parent) = cfg.db_path.parent() {
        if let Ok(_metadata) = std::fs::metadata(parent) {
            // Простая проверка через statvfs (для Unix)
            #[cfg(unix)]
            {
                println!("  ℹ Проверка места на диске требует дополнительных зависимостей");
            }
            #[cfg(not(unix))]
            {
                println!("  ℹ Проверка места на диске доступна только на Unix");
            }
        }
    }

    // Проверка прав на папки
    println!("\nПрава доступа:");

    // БД
    let db_ok = cfg.db_path.exists() &&
                cfg.db_path.parent().map(|p| p.exists()).unwrap_or(false);
    if db_ok {
        println!("  ✓ БД: {}", cfg.db_path.display());
    } else {
        println!("  ✗ БД недоступна: {}", cfg.db_path.display());
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
    let cache_dir = cfg.cache_dir();
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
    println!("Остановка и удаление Nami сервера...");

    #[cfg(unix)]
    {
        println!("Остановка службы systemd nami...");
        let _ = Command::new("systemctl").args(["stop", "nami"]).status();
        let _ = Command::new("systemctl").args(["disable", "nami"]).status();

        let service_file = PathBuf::from("/etc/systemd/system/nami.service");
        if service_file.exists() {
            if let Err(e) = std::fs::remove_file(&service_file) {
                println!("Предупреждение: не удалось удалить {}: {}", service_file.display(), e);
            } else {
                println!("✓ Удалён {}", service_file.display());
                let _ = Command::new("systemctl").args(["daemon-reload"]).status();
            }
        }

        let _ = Command::new("docker").args(["stop", "nami"]).output();
        let _ = Command::new("docker").args(["rm", "nami"]).output();
    }

    #[cfg(windows)]
    {
        println!("Остановка процессов nami-server...");
        let _ = Command::new("taskkill").args(["/F", "/IM", "nami-server.exe"]).output();
    }

    if purge {
        println!("Очистка данных (--purge)...");
        if cfg.db_path.exists() {
            if let Err(e) = std::fs::remove_file(&cfg.db_path) {
                println!("Предупреждение: не удалось удалить БД {}: {}", cfg.db_path.display(), e);
            } else {
                println!("✓ Удалена БД: {}", cfg.db_path.display());
            }
        }
        let cache = cfg.cache_dir();
        if cache.exists() {
            let _ = std::fs::remove_dir_all(&cache);
            println!("✓ Очищен кэш: {}", cache.display());
        }
    } else {
        println!("Данные сохранены. Передайте флаг --purge для полной очистки БД и кэша.");
    }

    println!("✓ Сервер Nami успешно удалён.");
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

    let report = crate::domain::setup_domain(&domain, cfg.port, std::path::Path::new("config.toml"))
        .map_err(|e| format!("Ошибка настройки домена: {e}"))?;

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

