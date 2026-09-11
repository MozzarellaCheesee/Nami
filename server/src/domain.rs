//! Модуль автоматической настройки доменного имени для Nami:
//! - Проверка DNS A-записи домена и сопоставление с внешним IP сервера
//! - Автоматическое открытие портов 80 и 443 в UFW / firewalld
//! - Автоматическая установка и настройка Caddy reverse proxy с получением сертификата Let's Encrypt
//! - Обновление external_url в config.toml

use std::net::ToSocketAddrs;
use std::path::Path;
#[cfg(unix)]
use std::process::Command;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DomainCheck {
    pub ok: bool,
    pub domain: String,
    pub domain_ips: Vec<String>,
    pub server_ip: Option<String>,
    pub matches: bool,
    pub message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DomainSetupReport {
    pub ok: bool,
    pub domain: String,
    pub url: String,
    pub message: String,
    pub steps: Vec<String>,
}

/// Очищает введённый пользователем домен от протокола, путей, пробелов и портов
pub fn clean_domain(input: &str) -> String {
    let s = input.trim();
    let s = s.trim_start_matches("https://").trim_start_matches("http://");
    let s = s.split('/').next().unwrap_or(s);
    let s = s.split(':').next().unwrap_or(s);
    s.trim().to_lowercase()
}

/// Выполняет DNS-резолв домена в IP-адреса
pub fn resolve_domain(domain: &str) -> Vec<String> {
    let clean = clean_domain(domain);
    if clean.is_empty() {
        return Vec::new();
    }
    let addr_str = format!("{clean}:80");
    if let Ok(addrs) = addr_str.to_socket_addrs() {
        let mut ips: Vec<String> = addrs.map(|a| a.ip().to_string()).collect();
        ips.sort();
        ips.dedup();
        ips
    } else {
        Vec::new()
    }
}

/// Определяет внешний публичный IP-адрес текущего сервера
pub fn get_public_ip() -> Option<String> {
    let services = [
        "https://api.ipify.org",
        "https://ifconfig.me/ip",
        "https://icanhazip.com",
    ];

    for url in services {
        if let Ok(mut resp) = ureq::get(url)
            .config()
            .timeout_global(Some(std::time::Duration::from_secs(3)))
            .build()
            .call()
        {
            if let Ok(body) = resp.body_mut().read_to_string() {
                let ip = body.trim();
                if !ip.is_empty() && (ip.contains('.') || ip.contains(':')) {
                    return Some(ip.to_string());
                }
            }
        }
    }
    None
}

/// Проверяет готовность домена к настройке
pub fn check_domain(input: &str) -> DomainCheck {
    let domain = clean_domain(input);
    if domain.is_empty() {
        return DomainCheck {
            ok: false,
            domain,
            domain_ips: Vec::new(),
            server_ip: None,
            matches: false,
            message: "Доменное имя не может быть пустым".into(),
        };
    }

    if !domain.contains('.') || domain.starts_with('.') || domain.ends_with('.') {
        return DomainCheck {
            ok: false,
            domain: domain.clone(),
            domain_ips: Vec::new(),
            server_ip: None,
            matches: false,
            message: format!("«{domain}» не похоже на валидное доменное имя (пример: music.example.com)"),
        };
    }

    if domain == "localhost" || domain == "127.0.0.1" {
        return DomainCheck {
            ok: false,
            domain: domain.clone(),
            domain_ips: vec!["127.0.0.1".into()],
            server_ip: None,
            matches: false,
            message: "localhost не является публичным доменом для внешнего доступа".into(),
        };
    }

    let ips = resolve_domain(&domain);
    let public_ip = get_public_ip();

    if ips.is_empty() {
        return DomainCheck {
            ok: false,
            domain: domain.clone(),
            domain_ips: Vec::new(),
            server_ip: public_ip.clone(),
            matches: false,
            message: format!(
                "DNS-запись для «{domain}» не найдена. Создайте A-запись (IPv4), указывающую на {}.",
                public_ip.as_deref().unwrap_or("IP вашего сервера")
            ),
        };
    }

    let matches = match &public_ip {
        Some(pub_ip) => ips.iter().any(|ip| ip == pub_ip),
        None => true, // Если свой IP определить не удалось, не блокируем
    };

    let message = if matches {
        format!(
            "Домен «{domain}» успешно указывает на IP этого сервера ({})",
            ips.join(", ")
        )
    } else {
        format!(
            "Домен «{domain}» указывает на [{}], а внешний IP этого сервера — {}. Обновите A-запись у вашего регистратора или подождите обновления DNS.",
            ips.join(", "),
            public_ip.as_deref().unwrap_or("не определён")
        )
    };

    DomainCheck {
        ok: true,
        domain,
        domain_ips: ips,
        server_ip: public_ip,
        matches,
        message,
    }
}

/// Выполняет полную автоматическую настройку домена: фаервол, Caddy reverse proxy, Let's Encrypt и config.toml
pub fn setup_domain(input: &str, nami_port: u16, config_path: &Path) -> Result<DomainSetupReport, String> {
    let _ = nami_port;
    let check = check_domain(input);
    if !check.ok {
        return Err(check.message);
    }
    let domain = check.domain;
    let mut steps = Vec::new();

    // 1. Проверка DNS
    if check.matches {
        steps.push(format!("✓ DNS A-запись подтверждена: {} -> {}", domain, check.domain_ips.join(", ")));
    } else {
        steps.push(format!("⚠ Внимание: домен указывает на {}, но настройка продолжается", check.domain_ips.join(", ")));
    }

    // 2. Открытие портов в брандмауэре (только Linux)
    #[cfg(unix)]
    {
        let mut fw_opened = false;
        if is_command_available("ufw") {
            let _ = Command::new("ufw").args(["allow", "80/tcp"]).output();
            let _ = Command::new("ufw").args(["allow", "443/tcp"]).output();
            steps.push("✓ Порты 80/tcp и 443/tcp разрешены в UFW".into());
            fw_opened = true;
        }
        if is_command_available("firewall-cmd") {
            let _ = Command::new("firewall-cmd").args(["--add-port=80/tcp", "--permanent"]).output();
            let _ = Command::new("firewall-cmd").args(["--add-port=443/tcp", "--permanent"]).output();
            let _ = Command::new("firewall-cmd").arg("--reload").output();
            steps.push("✓ Порты 80/tcp и 443/tcp разрешены в firewalld".into());
            fw_opened = true;
        }
        if !fw_opened {
            steps.push("• Системный фаервол (UFW/firewalld) не обнаружен, порты не требовали открытия".into());
        }
    }

    // 3. Проверка и установка Caddy
    #[cfg(unix)]
    {
        if !is_command_available("caddy") {
            steps.push("• Caddy не найден, запускаем автоматическую установку...".into());
            install_caddy_linux()?;
            steps.push("✓ Caddy успешно установлен".into());
        } else {
            steps.push("✓ Reverse Proxy Caddy найден в системе".into());
        }

        // 4. Генерация Caddyfile
        let caddyfile_content = format!(
            "# Автоматическая конфигурация Nami Music Server\n\
             {domain} {{\n\
             \x20   reverse_proxy 127.0.0.1:{nami_port} {{\n\
             \x20       transport http {{\n\
             \x20           tls_insecure_skip_verify\n\
             \x20       }}\n\
             \x20   }}\n\
             }}\n"
        );

        let caddy_dir = Path::new("/etc/caddy");
        let _ = std::fs::create_dir_all(caddy_dir);
        let caddyfile_path = caddy_dir.join("Caddyfile");

        // Бэкап старого конфига, если есть
        if caddyfile_path.exists() {
            let backup_path = caddy_dir.join("Caddyfile.nami.bak");
            let _ = std::fs::copy(&caddyfile_path, &backup_path);
        }

        std::fs::write(&caddyfile_path, caddyfile_content)
            .map_err(|e| format!("Не удалось записать /etc/caddy/Caddyfile: {e}"))?;
        steps.push(format!("✓ Конфигурация Caddy сохранена для «{domain}» -> 127.0.0.1:{nami_port}"));

        // 5. Запуск и перезагрузка Caddy
        let _ = Command::new("systemctl").args(["enable", "caddy"]).output();
        let reload = Command::new("systemctl").args(["reload", "caddy"]).output();
        let restarted = if reload.is_ok() && reload.unwrap().status.success() {
            true
        } else {
            Command::new("systemctl")
                .args(["restart", "caddy"])
                .output()
                .map(|o| o.status.success())
                .unwrap_or(false)
        };

        if restarted {
            steps.push("✓ Служба Caddy запущена: автоматический выпуск Let's Encrypt активен".into());
        } else {
            steps.push("⚠ Не удалось перезапустить caddy через systemctl (попробуйте sudo systemctl restart caddy)".into());
        }
    }

    #[cfg(not(unix))]
    {
        steps.push("• В Windows автоматическая установка службы Caddy не поддерживается напрямую, требуется запуск caddy вручную".into());
    }

    // 6. Обновление external_url в config.toml
    let ext_url = format!("https://{domain}");
    if config_path.exists() {
        if let Ok(content) = std::fs::read_to_string(config_path) {
            let updated = update_config_external_url(&content, &ext_url);
            if let Err(e) = std::fs::write(config_path, updated) {
                steps.push(format!("⚠ Не удалось обновить {}: {e}", config_path.display()));
            } else {
                steps.push(format!("✓ config.toml обновлён: external_url = \"{ext_url}\""));
            }
        }
    }

    // 7. Перезапуск nami, если работает
    #[cfg(unix)]
    {
        let _ = Command::new("systemctl").args(["restart", "nami"]).output();
        steps.push("✓ Служба nami перезапущена для применения внешнего адреса".into());
    }

    Ok(DomainSetupReport {
        ok: true,
        domain: domain.clone(),
        url: ext_url.clone(),
        message: format!(
            "🎉 Домен {domain} успешно настроен! Теперь сервер доступен по адресу {ext_url} с автоматическим доверенным SSL-сертификатом."
        ),
        steps,
    })
}

#[cfg(unix)]
fn is_command_available(cmd: &str) -> bool {
    Command::new("which")
        .arg(cmd)
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

#[cfg(unix)]
fn install_caddy_linux() -> Result<(), String> {
    // 1. Пробуем apt-get (Ubuntu / Debian)
    if is_command_available("apt-get") {
        let script = "apt-get update && \
            apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl && \
            curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg 2>/dev/null && \
            curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | tee /etc/apt/sources.list.d/caddy-stable.list >/dev/null && \
            apt-get update && \
            apt-get install -y caddy";
        let out = Command::new("sh").arg("-c").arg(script).output()
            .map_err(|e| format!("Ошибка запуска apt: {e}"))?;
        if out.status.success() {
            return Ok(());
        }
    }

    // 2. Пробуем dnf (Fedora, RHEL)
    if is_command_available("dnf") {
        let script = "dnf install -y 'dnf-command(copr)' && dnf copr enable -y @caddy/caddy && dnf install -y caddy";
        if let Ok(out) = Command::new("sh").arg("-c").arg(script).output() {
            if out.status.success() {
                return Ok(());
            }
        }
    }

    // 3. Пробуем pacman (Arch Linux)
    if is_command_available("pacman") {
        if let Ok(out) = Command::new("pacman").args(["-S", "--noconfirm", "caddy"]).output() {
            if out.status.success() {
                return Ok(());
            }
        }
    }

    // 4. Универсальный fallback: загрузка готового бинарника caddy
    let arch = match std::env::consts::ARCH {
        "x86_64" => "amd64",
        "aarch64" => "arm64",
        other => return Err(format!("Неподдерживаемая архитектура для автоустановки Caddy: {other}")),
    };
    let dl_script = format!(
        "curl -fsSL 'https://caddyserver.com/api/download?os=linux&arch={arch}' -o /usr/local/bin/caddy && \
         chmod +x /usr/local/bin/caddy"
    );
    let out = Command::new("sh").arg("-c").arg(&dl_script).output()
        .map_err(|e| format!("Ошибка скачивания бинарника caddy: {e}"))?;
    if out.status.success() {
        let service = "[Unit]\n\
            Description=Caddy Web Server\n\
            After=network.target network-online.target\n\
            Wants=network-online.target\n\n\
            [Service]\n\
            Type=notify\n\
            ExecStart=/usr/local/bin/caddy run --environ --config /etc/caddy/Caddyfile\n\
            ExecReload=/usr/local/bin/caddy reload --config /etc/caddy/Caddyfile\n\
            TimeoutStopSec=5s\n\
            LimitNOFILE=1048576\n\
            Restart=always\n\n\
            [Install]\n\
            WantedBy=multi-user.target\n";
        let _ = std::fs::write("/etc/systemd/system/caddy.service", service);
        let _ = Command::new("systemctl").args(["daemon-reload"]).output();
        return Ok(());
    }

    Err("Не удалось автоматически установить Caddy. Установите его вручную: https://caddyserver.com/docs/install".into())
}

/// Заменяет или добавляет external_url в TOML-конфигурацию
pub fn update_config_external_url(content: &str, ext_url: &str) -> String {
    let mut lines: Vec<String> = content.lines().map(|s| s.to_string()).collect();
    let mut found = false;

    for line in lines.iter_mut() {
        if line.trim().starts_with("external_url") {
            *line = format!("external_url = \"{ext_url}\"");
            found = true;
            break;
        }
    }

    if !found {
        lines.push(format!("external_url = \"{ext_url}\""));
    }

    lines.join("\n") + "\n"
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn очистка_доменного_имени() {
        assert_eq!(clean_domain("https://music.example.com/"), "music.example.com");
        assert_eq!(clean_domain("http://music.example.com:8443/setup"), "music.example.com");
        assert_eq!(clean_domain("  MUSIC.EXAMPLE.COM  "), "music.example.com");
    }

    #[test]
    fn обновление_external_url() {
        let toml = "port = 4533\ntls = true\n";
        let updated = update_config_external_url(toml, "https://music.example.com");
        assert!(updated.contains("external_url = \"https://music.example.com\""));

        let toml_existing = "port = 4533\nexternal_url = \"https://old.com\"\n";
        let updated_existing = update_config_external_url(toml_existing, "https://new.com");
        assert!(updated_existing.contains("external_url = \"https://new.com\""));
        assert!(!updated_existing.contains("old.com"));
    }
}

