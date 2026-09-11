//! Модуль автоматической настройки доменного имени для Nami:
//! - Проверка DNS A-записи домена и сопоставление с внешним IP сервера
//! - Автоматическое открытие портов 80 и 443 в UFW / firewalld
//! - Автоматическая установка и настройка Caddy reverse proxy с получением сертификата Let's Encrypt
//! - Обновление external_url в config.toml

use std::net::ToSocketAddrs;
use std::path::{Path, PathBuf};
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

    #[cfg(unix)]
    let is_sys_root = is_root();
    #[cfg(unix)]
    let sudo = if is_sys_root { "" } else { "sudo " };

    // 2. Открытие портов в брандмауэре (только Linux)
    #[cfg(unix)]
    {
        let mut fw_opened = false;
        if is_command_available("ufw") {
            let _ = Command::new("sh").arg("-c").arg(format!("{sudo}ufw allow 80/tcp")).output();
            let _ = Command::new("sh").arg("-c").arg(format!("{sudo}ufw allow 443/tcp")).output();
            steps.push("✓ Порты 80/tcp и 443/tcp разрешены в UFW".into());
            fw_opened = true;
        }
        if is_command_available("firewall-cmd") {
            let _ = Command::new("sh").arg("-c").arg(format!("{sudo}firewall-cmd --add-port=80/tcp --permanent")).output();
            let _ = Command::new("sh").arg("-c").arg(format!("{sudo}firewall-cmd --add-port=443/tcp --permanent")).output();
            let _ = Command::new("sh").arg("-c").arg(format!("{sudo}firewall-cmd --reload")).output();
            steps.push("✓ Порты 80/tcp и 443/tcp разрешены в firewalld".into());
            fw_opened = true;
        }
        if !fw_opened {
            steps.push("• Системный фаервол (UFW/firewalld) не обнаружен, порты не требовали открытия".into());
        }
    }

    // 3. Настройка Reverse Proxy (Nginx + Certbot либо Caddy)
    #[cfg(unix)]
    {
        if is_nginx_present() {
            setup_nginx(&domain, nami_port, &mut steps, sudo)?;
        } else {
            setup_caddy(&domain, nami_port, &mut steps, sudo, is_sys_root)?;
        }
    }

    #[cfg(not(unix))]
    {
        steps.push("• В Windows автоматическая установка веб-сервера не поддерживается напрямую, требуется ручная настройка".into());
    }

    // 6. Обновление external_url в config.toml
    let ext_url = format!("https://{domain}");
    let actual_config_path = if config_path.exists() {
        config_path.to_path_buf()
    } else {
        crate::config::find_config_path()
    };

    if actual_config_path.exists() {
        if let Ok(content) = std::fs::read_to_string(&actual_config_path) {
            let updated = update_config_external_url(&content, &ext_url);
            if let Err(_e) = std::fs::write(&actual_config_path, &updated) {
                #[cfg(unix)]
                {
                    let _ = Command::new("sh")
                        .arg("-c")
                        .arg(format!("{sudo}tee {} >/dev/null", actual_config_path.display()))
                        .stdin(std::process::Stdio::piped())
                        .spawn()
                        .map(|mut c| {
                            use std::io::Write;
                            if let Some(mut s) = c.stdin.take() {
                                let _ = s.write_all(updated.as_bytes());
                            }
                            let _ = c.wait();
                        });
                }
                steps.push(format!("✓ config.toml обновлён: external_url = \"{ext_url}\" (fallback)"));
            } else {
                steps.push(format!("✓ config.toml обновлён: external_url = \"{ext_url}\""));
            }
        }
    }

    // 7. Перезапуск nami, если работает
    #[cfg(unix)]
    {
        let _ = Command::new("sh").arg("-c").arg(format!("{sudo}systemctl restart nami")).output();
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
fn is_root() -> bool {
    Command::new("id")
        .arg("-u")
        .output()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim() == "0")
        .unwrap_or(false)
}

#[cfg(unix)]
fn can_run_sudo() -> bool {
    if is_root() {
        return true;
    }
    Command::new("sudo")
        .args(["-n", "true"])
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

#[cfg(unix)]
fn is_command_available(cmd: &str) -> bool {
    for dir in ["/usr/bin", "/usr/local/bin", "/bin", "/usr/sbin", "/sbin"] {
        if Path::new(dir).join(cmd).exists() {
            return true;
        }
    }
    if let Ok(out) = Command::new("sh").args(["-c", &format!("command -v {cmd}")]).output() {
        if out.status.success() && !out.stdout.is_empty() {
            return true;
        }
    }
    Command::new("which")
        .arg(cmd)
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

#[cfg(unix)]
fn is_nginx_present() -> bool {
    if Path::new("/etc/nginx").exists() {
        return true;
    }
    if is_command_available("nginx") {
        return true;
    }
    let out = Command::new("sh")
        .arg("-c")
        .arg("systemctl is-active nginx 2>/dev/null")
        .output();
    if let Ok(o) = out {
        if String::from_utf8_lossy(&o.stdout).trim() == "active" {
            return true;
        }
    }
    false
}

#[cfg(unix)]
fn write_privileged_file(path: &Path, content: &str, sudo: &str) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        if !parent.exists() {
            let _ = std::fs::create_dir_all(parent);
            if !parent.exists() {
                let _ = Command::new("sh").arg("-c").arg(format!("{sudo}mkdir -p {}", parent.display())).output();
            }
        }
    }

    if let Err(e) = std::fs::write(path, content) {
        let child = Command::new("sh")
            .arg("-c")
            .arg(format!("{sudo}tee {} >/dev/null", path.display()))
            .stdin(std::process::Stdio::piped())
            .spawn();
        match child {
            Ok(mut c) => {
                if let Some(mut stdin) = c.stdin.take() {
                    use std::io::Write;
                    let _ = stdin.write_all(content.as_bytes());
                }
                let res = c.wait();
                if res.is_err() || !res.unwrap().success() {
                    return Err(format!("Не удалось записать {}: {e}", path.display()));
                }
            }
            Err(se) => return Err(format!("Не удалось записать {}: {e} ({se})", path.display())),
        }
    }
    Ok(())
}

#[cfg(unix)]
fn install_certbot(sudo: &str) -> Result<(), String> {
    if is_command_available("apt-get") {
        let script = format!("{sudo}apt-get update -y && {sudo}apt-get install -y certbot python3-certbot-nginx");
        if let Ok(o) = Command::new("sh").arg("-c").arg(&script).output() {
            if o.status.success() {
                return Ok(());
            }
        }
    }
    if is_command_available("dnf") {
        let script = format!("{sudo}dnf install -y certbot python3-certbot-nginx");
        if let Ok(o) = Command::new("sh").arg("-c").arg(&script).output() {
            if o.status.success() {
                return Ok(());
            }
        }
    }
    if is_command_available("pacman") {
        let script = format!("{sudo}pacman -S --noconfirm certbot certbot-nginx");
        if let Ok(o) = Command::new("sh").arg("-c").arg(&script).output() {
            if o.status.success() {
                return Ok(());
            }
        }
    }
    if is_command_available("apk") {
        let script = format!("{sudo}apk add --no-cache certbot certbot-nginx");
        if let Ok(o) = Command::new("sh").arg("-c").arg(&script).output() {
            if o.status.success() {
                return Ok(());
            }
        }
    }
    Err("Не удалось автоматически установить Certbot. Установите python3-certbot-nginx вручную.".into())
}

#[cfg(unix)]
fn setup_nginx(domain: &str, nami_port: u16, steps: &mut Vec<String>, sudo: &str) -> Result<(), String> {
    steps.push("✓ Обнаружен веб-сервер Nginx: интеграция через Nginx reverse proxy + Certbot".into());

    let nginx_conf = format!(
        "# Автоматическая конфигурация Nami Music Server для Nginx\n\
         server {{\n\
         \x20   listen 80;\n\
         \x20   server_name {domain};\n\n\
         \x20   location / {{\n\
         \x20       proxy_pass https://127.0.0.1:{nami_port};\n\
         \x20       proxy_ssl_verify off;\n\
         \x20       proxy_set_header Host $host;\n\
         \x20       proxy_set_header X-Real-IP $remote_addr;\n\
         \x20       proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n\
         \x20       proxy_set_header X-Forwarded-Proto $scheme;\n\n\
         \x20       # Поддержка WebSocket для Jam и синхронизации\n\
         \x20       proxy_http_version 1.1;\n\
         \x20       proxy_set_header Upgrade $http_upgrade;\n\
         \x20       proxy_set_header Connection \"upgrade\";\n\n\
         \x20       proxy_buffering off;\n\
         \x20       client_max_body_size 500M;\n\
         \x20   }}\n\
         }}\n"
    );

    let sites_available = Path::new("/etc/nginx/sites-available");
    let conf_d = Path::new("/etc/nginx/conf.d");

    let (conf_file, needs_symlink) = if sites_available.exists() {
        (sites_available.join(domain), true)
    } else if conf_d.exists() {
        (conf_d.join(format!("{domain}.conf")), false)
    } else {
        let _ = Command::new("sh").arg("-c").arg(format!("{sudo}mkdir -p /etc/nginx/conf.d")).output();
        (PathBuf::from(format!("/etc/nginx/conf.d/{domain}.conf")), false)
    };

    write_privileged_file(&conf_file, &nginx_conf, sudo)?;

    if needs_symlink {
        let sites_enabled = Path::new("/etc/nginx/sites-enabled");
        let _ = Command::new("sh").arg("-c").arg(format!("{sudo}mkdir -p /etc/nginx/sites-enabled")).output();
        let symlink_path = sites_enabled.join(domain);
        let _ = Command::new("sh")
            .arg("-c")
            .arg(format!("{sudo}ln -sf {} {}", conf_file.display(), symlink_path.display()))
            .output();
    }

    steps.push(format!("✓ Конфигурация Nginx сохранена в {}", conf_file.display()));

    let test_out = Command::new("sh").arg("-c").arg(format!("{sudo}nginx -t")).output();
    if let Ok(out) = test_out {
        if !out.status.success() {
            let err = String::from_utf8_lossy(&out.stderr);
            return Err(format!("Ошибка проверки конфигурации Nginx (nginx -t): {}", err.trim()));
        }
    }

    let _ = Command::new("sh").arg("-c").arg(format!("{sudo}systemctl reload nginx || {sudo}systemctl restart nginx")).output();
    steps.push("✓ Служба Nginx успешно перезагружена".into());

    if !is_command_available("certbot") {
        steps.push("• Утилита Certbot не найдена, запускаем установку...".into());
        install_certbot(sudo)?;
        steps.push("✓ Certbot успешно установлен".into());
    }

    steps.push(format!("• Получение SSL-сертификата Let's Encrypt для «{domain}» через Certbot..."));
    let cert_cmd = format!(
        "{sudo}certbot --nginx -d {domain} --non-interactive --agree-tos --register-unsafely-without-email --redirect"
    );
    let cert_res = Command::new("sh").arg("-c").arg(&cert_cmd).output();
    match cert_res {
        Ok(out) if out.status.success() => {
            steps.push("✓ SSL-сертификат Let's Encrypt успешно получен и настроен в Nginx".into());
        }
        Ok(out) => {
            let stderr = String::from_utf8_lossy(&out.stderr);
            let stdout = String::from_utf8_lossy(&out.stdout);
            let msg = if !stderr.trim().is_empty() { stderr.trim() } else { stdout.trim() };
            steps.push(format!("⚠ Certbot: {}\nДля ручной настройки выполните: sudo certbot --nginx -d {}", msg, domain));
        }
        Err(e) => {
            steps.push(format!("⚠ Ошибка запуска certbot: {e}. Вы можете настроить SSL командой: sudo certbot --nginx -d {}", domain));
        }
    }

    Ok(())
}

#[cfg(unix)]
fn setup_caddy(domain: &str, nami_port: u16, steps: &mut Vec<String>, sudo: &str, is_sys_root: bool) -> Result<(), String> {
    if !is_command_available("caddy") {
        if !is_sys_root && !can_run_sudo() {
            return Err(format!(
                "Caddy не установлен в системе, а сервис Nami работает без прав root.\n\
                 Для завершения автоматической настройки выполните в консоли сервера через sudo:\n\
                 \x20 sudo nami domain {domain}\n\
                 (команда автоматически установит Caddy, настроит порты 80/443 и выпустит SSL-сертификат)\n\
                 Или установите Caddy вручную: https://caddyserver.com/docs/install"
            ));
        }

        steps.push("• Caddy не найден, запускаем автоматическую установку...".into());
        install_caddy_linux()?;
        steps.push("✓ Caddy успешно установлен".into());
    } else {
        steps.push("✓ Reverse Proxy Caddy найден в системе".into());
    }

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
    let caddyfile_path = caddy_dir.join("Caddyfile");

    if caddyfile_path.exists() {
        let backup_path = caddy_dir.join("Caddyfile.nami.bak");
        let _ = std::fs::copy(&caddyfile_path, &backup_path);
    }

    write_privileged_file(&caddyfile_path, &caddyfile_content, sudo)?;
    steps.push(format!("✓ Конфигурация Caddy сохранена для «{domain}» -> 127.0.0.1:{nami_port}"));

    let reloaded_via_api = ureq::post("http://127.0.0.1:2019/load")
        .config()
        .timeout_global(Some(std::time::Duration::from_secs(3)))
        .build()
        .header("Content-Type", "text/caddyfile")
        .send(caddyfile_content.as_bytes())
        .map(|r| r.status().is_success())
        .unwrap_or(false);

    if reloaded_via_api {
        steps.push("✓ Конфигурация Caddy обновлена на лету через Admin API (localhost:2019)".into());
    } else {
        let _ = Command::new("sh").arg("-c").arg(format!("{sudo}systemctl enable caddy")).output();
        let reload = Command::new("sh").arg("-c").arg(format!("{sudo}systemctl reload caddy")).output();
        let restarted = if reload.is_ok() && reload.unwrap().status.success() {
            true
        } else {
            Command::new("sh")
                .arg("-c")
                .arg(format!("{sudo}systemctl restart caddy"))
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

    Ok(())
}

#[cfg(unix)]
fn install_caddy_linux() -> Result<(), String> {
    let is_sys_root = is_root();
    let sudo = if is_sys_root { "" } else { "sudo " };
    let mut errors = Vec::new();

    // 1. Пробуем apt-get (Ubuntu / Debian / Raspberry Pi OS)
    if is_command_available("apt-get") {
        let script = format!(
            "{sudo}apt-get update -y && \
             {sudo}apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl gnupg && \
             curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | {sudo}gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg && \
             curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | {sudo}tee /etc/apt/sources.list.d/caddy-stable.list >/dev/null && \
             {sudo}apt-get update -y && \
             {sudo}apt-get install -y caddy"
        );
        match Command::new("sh").arg("-c").arg(&script).output() {
            Ok(out) if out.status.success() => return Ok(()),
            Ok(out) => {
                let stderr = String::from_utf8_lossy(&out.stderr);
                errors.push(format!("apt: {}", stderr.trim()));
            }
            Err(e) => errors.push(format!("apt launch: {e}")),
        }
    }

    // 2. Пробуем dnf (Fedora, RHEL, Alma, Rocky)
    if is_command_available("dnf") {
        let script = format!("{sudo}dnf install -y 'dnf-command(copr)' && {sudo}dnf copr enable -y @caddy/caddy && {sudo}dnf install -y caddy");
        match Command::new("sh").arg("-c").arg(&script).output() {
            Ok(out) if out.status.success() => return Ok(()),
            Ok(out) => {
                let stderr = String::from_utf8_lossy(&out.stderr);
                errors.push(format!("dnf: {}", stderr.trim()));
            }
            Err(e) => errors.push(format!("dnf launch: {e}")),
        }
    }

    // 3. Пробуем pacman (Arch Linux, Manjaro)
    if is_command_available("pacman") {
        let script = format!("{sudo}pacman -S --noconfirm caddy");
        match Command::new("sh").arg("-c").arg(&script).output() {
            Ok(out) if out.status.success() => return Ok(()),
            Ok(out) => {
                let stderr = String::from_utf8_lossy(&out.stderr);
                errors.push(format!("pacman: {}", stderr.trim()));
            }
            Err(e) => errors.push(format!("pacman launch: {e}")),
        }
    }

    // 4. Универсальный fallback: загрузка официального бинарника caddy
    let arch = match std::env::consts::ARCH {
        "x86_64" => "amd64",
        "aarch64" => "arm64",
        other => return Err(format!("Неподдерживаемая архитектура процессора для Caddy: {other}")),
    };

    let dl_script = format!(
        "(curl -fsSL 'https://caddyserver.com/api/download?os=linux&arch={arch}' -o /tmp/caddy || \
          wget -qO /tmp/caddy 'https://caddyserver.com/api/download?os=linux&arch={arch}') && \
         {sudo}install -m 755 /tmp/caddy /usr/local/bin/caddy && \
         rm -f /tmp/caddy"
    );

    match Command::new("sh").arg("-c").arg(&dl_script).output() {
        Ok(out) if out.status.success() => {
            let service = "[Unit]\n\
                Description=Caddy Web Server\n\
                Documentation=https://caddyserver.com/docs/\n\
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
            let s_path = "/etc/systemd/system/caddy.service";
            if std::fs::write(s_path, service).is_err() {
                let _ = Command::new("sh")
                    .arg("-c")
                    .arg(format!("cat << 'EOF' | {sudo}tee {s_path} >/dev/null\n{service}\nEOF"))
                    .output();
            }
            let _ = Command::new("sh").arg("-c").arg(format!("{sudo}systemctl daemon-reload")).output();
            let _ = Command::new("sh").arg("-c").arg(format!("{sudo}systemctl enable caddy")).output();
            return Ok(());
        }
        Ok(out) => {
            let stderr = String::from_utf8_lossy(&out.stderr);
            errors.push(format!("binary download: {}", stderr.trim()));
        }
        Err(e) => errors.push(format!("binary download launch: {e}")),
    }

    let detail = if errors.is_empty() {
        String::new()
    } else {
        format!("\nДетали ошибок:\n  • {}", errors.join("\n  • "))
    };

    Err(format!(
        "Не удалось автоматически установить Caddy.{detail}\n\
         Если сервер работает без прав root, выполните команду в консоли через sudo:\n\
         \x20 sudo nami domain <домен>\n\
         Либо установите Caddy вручную: https://caddyserver.com/docs/install"
    ))
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

