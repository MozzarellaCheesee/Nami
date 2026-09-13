//! Открытие порта в брандмауэре при смене его в мастере настройки.
//!
//! Установщик открывает порт, заданный при установке. Человек потом меняет порт в мастере - и
//! правило остаётся на старом: с телефона не подключиться, хотя сервер работает. Открывать
//! порт умеет тот, кто его и выбрал, то есть сам сервер.
//!
//! Прав может не хватить: на Linux сервер под systemd работает от root и справится, на Windows
//! служба работает от LocalSystem и тоже, а вот запущенный вручную процесс обычного
//! пользователя - нет. Поэтому функция возвращает текст для показа человеку, а не ошибку:
//! «не смог, откройте сами вот так» полезнее молчаливого провала.

/// Возвращает короткое сообщение о том, что вышло. Пустая строка - правило уже было.
pub fn open_port(port: u16) -> String {
    match add_rule(port) {
        Ok(true) => format!("Порт {port} открыт в брандмауэре."),
        Ok(false) => String::new(),
        Err(e) => format!(
            "Порт {port} не удалось открыть автоматически ({e}). Откройте его вручную, \
             иначе с телефона подключиться не выйдет."
        ),
    }
}

#[cfg(windows)]
fn add_rule(port: u16) -> Result<bool, String> {
    // netsh, а не New-NetFirewallRule: netsh есть в любой Windows и не требует PowerShell.
    // Имя правила зависит от порта - иначе смена порта переписывала бы старое правило, и
    // вернуться на прежний порт было бы нельзя без ручной чистки.
    let name = format!("Nami Music Server (TCP {port})");
    let out = std::process::Command::new("netsh")
        .args([
            "advfirewall",
            "firewall",
            "add",
            "rule",
            &format!("name={name}"),
            "dir=in",
            "action=allow",
            "protocol=TCP",
            &format!("localport={port}"),
        ])
        .output()
        .map_err(|e| e.to_string())?;
    if out.status.success() {
        Ok(true)
    } else {
        Err(String::from_utf8_lossy(&out.stderr).trim().to_string())
    }
}

#[cfg(not(windows))]
fn add_rule(port: u16) -> Result<bool, String> {
    // Пробуем оба распространённых межсетевых экрана и считаем успехом любой сработавший.
    // Нет ни одного - значит фильтрации нет и открывать нечего.
    let ufw = std::process::Command::new("ufw")
        .args(["allow", &format!("{port}/tcp")])
        .output();
    if let Ok(out) = ufw {
        if out.status.success() {
            return Ok(true);
        }
    }
    let firewalld = std::process::Command::new("firewall-cmd")
        .args(["--permanent", &format!("--add-port={port}/tcp")])
        .output();
    if let Ok(out) = firewalld {
        if out.status.success() {
            let _ = std::process::Command::new("firewall-cmd").arg("--reload").output();
            return Ok(true);
        }
    }
    Ok(false)
}
