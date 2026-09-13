//! Служба Windows: сервер работает фоном, без консольного окна и до входа пользователя.
//!
//! Почему не задача планировщика, которая была раньше. У неё два неустранимых недостатка:
//! она стартует по ВХОДУ пользователя (перезагрузили машину и не залогинились - сервера нет)
//! и запускает обычное консольное приложение, то есть на экране висит чёрное окно, которое
//! пользователь рано или поздно закроет вместе с сервером.
//!
//! Почему не `sc.exe create` поверх обычного exe. Диспетчер служб Windows ждёт от процесса
//! ответа по своему протоколу в течение таймаута и, не дождавшись, объявляет запуск неудачным
//! и убивает процесс. Поэтому служба обязана иметь собственную точку входа и обработчик команд -
//! это и есть содержимое файла.
//!
//! Весь модуль собирается только под Windows: на Linux роль службы играет systemd, и там
//! ничего этого не нужно.

#![cfg(windows)]

use std::ffi::OsString;
use std::time::Duration;

use windows_service::service::{
    ServiceAccess, ServiceControl, ServiceControlAccept, ServiceErrorControl, ServiceExitCode,
    ServiceInfo, ServiceStartType, ServiceState, ServiceStatus, ServiceType,
};
use windows_service::service_control_handler::{self, ServiceControlHandlerResult};
use windows_service::service_manager::{ServiceManager, ServiceManagerAccess};

use crate::Res;

pub const SERVICE_NAME: &str = "NamiServer";
const DISPLAY_NAME: &str = "Nami Music Server";
const SERVICE_TYPE: ServiceType = ServiceType::OWN_PROCESS;

/// Регистрирует службу и запускает её.
///
/// Запускается от LocalSystem: сервер должен подниматься до входа пользователя, а значит не
/// может зависеть от его профиля. Обратная сторона - музыка на сетевом диске, подключённом в
/// профиле пользователя, службе не видна; для таких папок в конфигурации указывают путь UNC.
pub fn install(exe: &std::path::Path) -> Res<()> {
    let manager = ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CREATE_SERVICE)?;
    let info = ServiceInfo {
        name: OsString::from(SERVICE_NAME),
        display_name: OsString::from(DISPLAY_NAME),
        service_type: SERVICE_TYPE,
        start_type: ServiceStartType::AutoStart,
        error_control: ServiceErrorControl::Normal,
        executable_path: exe.to_path_buf(),
        // Аргумент отличает запуск службой от обычного запуска из консоли: по нему процесс
        // понимает, что должен отвечать диспетчеру, а не работать как обычная программа.
        launch_arguments: vec![OsString::from("service"), OsString::from("run")],
        dependencies: vec![],
        account_name: None, // LocalSystem
        account_password: None,
    };
    let service = manager.create_service(&info, ServiceAccess::CHANGE_CONFIG | ServiceAccess::START)?;
    service.set_description("Self-hosted музыкальный сервер Nami: библиотека, отдача аудио, синхронизация между устройствами")?;
    service.start(&[] as &[&std::ffi::OsStr])?;
    Ok(())
}

pub fn uninstall() -> Res<()> {
    let manager = ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CONNECT)?;
    let service = manager.open_service(
        SERVICE_NAME,
        ServiceAccess::STOP | ServiceAccess::DELETE | ServiceAccess::QUERY_STATUS,
    )?;
    if service.query_status()?.current_state != ServiceState::Stopped {
        let _ = service.stop();
        // Удаление не отменяется, если служба ещё останавливается - она просто исчезнет после
        // остановки. Ждать дольше нет смысла, но короткую паузу даём, чтобы в типичном случае
        // пользователь увидел уже завершённое удаление, а не "помечена к удалению".
        std::thread::sleep(Duration::from_secs(2));
    }
    service.delete()?;
    Ok(())
}

pub fn start() -> Res<()> {
    let manager = ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CONNECT)?;
    let service = manager.open_service(SERVICE_NAME, ServiceAccess::START)?;
    service.start(&[] as &[&std::ffi::OsStr])?;
    Ok(())
}

pub fn stop() -> Res<()> {
    let manager = ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CONNECT)?;
    let service = manager.open_service(SERVICE_NAME, ServiceAccess::STOP)?;
    service.stop()?;
    Ok(())
}

/// Состояние службы. `None` - служба не зарегистрирована.
pub fn state() -> Option<ServiceState> {
    let manager = ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CONNECT).ok()?;
    let service = manager.open_service(SERVICE_NAME, ServiceAccess::QUERY_STATUS).ok()?;
    service.query_status().ok().map(|s| s.current_state)
}

/// Выполняет действие над службой от имени администратора, показав запрос UAC.
///
/// Управление службами требует прав администратора, а ярлык открывается обычным пользователем.
/// Без этого любое нажатие «включить» заканчивалось сырым «IO error in winapi call» - это отказ
/// в доступе, но понять по нему что-либо невозможно.
///
/// Через PowerShell, а не своим вызовом ShellExecute: нужен ровно один глагол `runas`, и тащить
/// ради него крейт с привязками к WinAPI незачем - powershell.exe есть в любой Windows.
/// `visible` = false прячет окно (короткая фоновая команда вроде переключения службы),
/// true оставляет его на экране (долгая операция с собственным выводом или вопросом
/// пользователю, например `uninstall` без `--purge`, который спрашивает y/N).
pub fn run_elevated(args: &[&str], visible: bool) -> Res<()> {
    let exe = std::env::current_exe()?;
    let command = elevate_command(&exe.to_string_lossy(), args, visible);
    let status = std::process::Command::new("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-Command", &command])
        .status()?;
    if status.success() {
        Ok(())
    } else {
        Err("запрос прав администратора отклонён".into())
    }
}

/// Проверка прав администратора без лишних зависимостей: `net session` отвечает "Отказано в
/// доступе" без прав и молча завершается успехом с ними - тот же приём, что уже применяют
/// многие консольные утилиты, чтобы не тащить крейт ради одного WinAPI-вызова.
pub fn is_elevated() -> bool {
    std::process::Command::new("net")
        .args(["session"])
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

/// Команда PowerShell для запуска действия с правами администратора.
///
/// Вынесена отдельно и покрыта тестом, потому что ломается молча: путь с пробелом или
/// апострофом (папка пользователя вида C:\Users\O'Brien) разъедет команду, и вместо
/// запроса прав пользователь получит невнятную ошибку разбора.
fn elevate_command(exe: &str, args: &[&str], visible: bool) -> String {
    // Одинарные кавычки PowerShell экранируются удвоением.
    let quoted = exe.replace('\'', "''");
    let arg_list = args.iter().map(|a| format!("'{}'", a.replace('\'', "''"))).collect::<Vec<_>>().join(",");
    let window = if visible { "" } else { " -WindowStyle Hidden" };
    format!("Start-Process -FilePath '{quoted}' -ArgumentList {arg_list} -Verb RunAs -Wait{window}")
}

pub fn is_running() -> bool {
    matches!(state(), Some(ServiceState::Running))
}

pub fn is_installed() -> bool {
    state().is_some()
}

windows_service::define_windows_service!(ffi_service_main, service_main);

/// Точка входа службы: сюда управление передаёт диспетчер, а не пользователь.
fn service_main(_arguments: Vec<OsString>) {
    if let Err(e) = run_service() {
        tracing::error!("служба завершилась с ошибкой: {e}");
    }
}

fn run_service() -> Res<()> {
    // Служба стартует с рабочим каталогом C:\Windows\System32 - его назначает диспетчер, и
    // задать свой в описании службы нельзя. А относительные пути в конфигурации (config.toml,
    // nami.db, папка данных) считаются именно от рабочего каталога: сервер искал конфигурацию
    // в системной папке, не находил, запускал мастер настройки и заводил пустую базу рядом.
    // Со стороны это выглядело как «переустановка стёрла библиотеку».
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            let _ = std::env::set_current_dir(dir);
        }
    }

    let (shutdown_tx, shutdown_rx) = std::sync::mpsc::channel();

    let handler = move |control| -> ServiceControlHandlerResult {
        match control {
            // Interrogate обязателен: диспетчер периодически спрашивает состояние, и служба,
            // не отвечающая на этот запрос, считается зависшей.
            ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
            ServiceControl::Stop | ServiceControl::Shutdown => {
                let _ = shutdown_tx.send(());
                ServiceControlHandlerResult::NoError
            }
            _ => ServiceControlHandlerResult::NotImplemented,
        }
    };
    let status_handle = service_control_handler::register(SERVICE_NAME, handler)?;

    status_handle.set_service_status(ServiceStatus {
        service_type: SERVICE_TYPE,
        current_state: ServiceState::Running,
        controls_accepted: ServiceControlAccept::STOP | ServiceControlAccept::SHUTDOWN,
        exit_code: ServiceExitCode::Win32(0),
        checkpoint: 0,
        wait_hint: Duration::default(),
        process_id: None,
    })?;

    // Сервер живёт в своём рантайме в отдельном потоке, а этот поток ждёт команды остановки.
    // Сливать их в один нельзя: обработчик команд диспетчера обязан отвечать немедленно, а
    // рантайм занят обслуживанием запросов.
    let server = std::thread::spawn(|| {
        let rt = match tokio::runtime::Runtime::new() {
            Ok(rt) => rt,
            Err(e) => {
                tracing::error!("не удалось создать рантайм: {e}");
                return;
            }
        };
        if let Err(e) = rt.block_on(crate::serve_from_config()) {
            tracing::error!("сервер остановился: {e}");
        }
    });

    let _ = shutdown_rx.recv();
    status_handle.set_service_status(ServiceStatus {
        service_type: SERVICE_TYPE,
        current_state: ServiceState::Stopped,
        controls_accepted: ServiceControlAccept::empty(),
        exit_code: ServiceExitCode::Win32(0),
        checkpoint: 0,
        wait_hint: Duration::default(),
        process_id: None,
    })?;
    // Процесс завершится целиком, поэтому поток сервера не дожидаемся: он держит слушающий
    // сокет, и корректное его закрытие произойдёт при выходе процесса.
    drop(server);
    Ok(())
}

/// Запуск в режиме службы: отдаёт управление диспетчеру Windows.
pub fn run_dispatcher() -> Res<()> {
    windows_service::service_dispatcher::start(SERVICE_NAME, ffi_service_main)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn путь_с_пробелом_и_апострофом_не_ломает_команду() {
        let cmd = elevate_command("C:\\Users\\O\'Brien\\Nami Server\\nami-server.exe", &["service", "start"], false);
        assert!(cmd.contains("'C:\\Users\\O''Brien\\Nami Server\\nami-server.exe'"), "{cmd}");
        assert!(cmd.contains("-ArgumentList 'service','start'"), "{cmd}");
        assert!(cmd.contains("-Verb RunAs"), "{cmd}");
        assert!(cmd.contains("-WindowStyle Hidden"), "{cmd}");
    }

    #[test]
    fn апостроф_внутри_аргумента_тоже_экранируется() {
        // uninstall передаёт свои аргументы как есть, и хотя "--purge" не содержит кавычек,
        // проверка не должна зависеть от конкретного набора флагов - экранирование обязано
        // работать для любого аргумента, а не только для пути к exe.
        let cmd = elevate_command("C:\\Nami\\nami-server.exe", &["uninstall", "--purge"], false);
        assert!(cmd.contains("-ArgumentList 'uninstall','--purge'"), "{cmd}");
    }

    #[test]
    fn видимое_окно_не_добавляет_windowstyle() {
        let cmd = elevate_command("C:\\Nami\\nami-server.exe", &["uninstall"], true);
        assert!(!cmd.contains("WindowStyle"), "{cmd}");
        assert!(cmd.contains("-Verb RunAs"), "{cmd}");
    }
}
