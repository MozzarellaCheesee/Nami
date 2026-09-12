//! Слежение за папками библиотеки: изменения подхватываются без ручного `POST /api/scan`.
//!
//! Под капотом `notify` (inotify на Linux, FSEvents на macOS, ReadDirectoryChangesW на Windows).

use std::path::PathBuf;
use std::sync::mpsc;
use std::time::Duration;

use notify::{RecursiveMode, Watcher};

use crate::api::Shared;

/// Окно дебаунса: копирование альбома - это сотни событий подряд, пересканировать
/// на каждое отдельное бессмысленно. Ждём, пока поток событий затихнет на это время.
pub const DEBOUNCE: Duration = Duration::from_secs(3);

/// Запускает слежение за `music_dirs` в отдельном потоке.
///
/// Поток живёт до конца процесса; watcher держится в нём же (при дропе слежение снимается).
/// Ошибки не фатальны: если inotify недоступен (лимиты ядра, экзотическая ФС) - сервер
/// продолжает работать, библиотека обновляется по `POST /api/scan`.
pub fn spawn(state: Shared) {
    let mut libraries = vec![(0, state.cfg.music_dirs.clone())];
    if let Ok(extra) = crate::scanner::library_dirs(&state.db.lock().unwrap()) {
        libraries.extend(extra);
    }
    libraries.retain(|(_, dirs)| !dirs.is_empty());
    if libraries.is_empty() {
        return;
    }
    std::thread::spawn(move || {
        if let Err(e) = run(state, libraries) {
            tracing::warn!("слежение за папками не запущено: {e} - остаётся ручной POST /api/scan");
        }
    });
}

fn run(state: Shared, libraries: Vec<(i64, Vec<PathBuf>)>) -> crate::Res<()> {
    let (tx, rx) = mpsc::channel();
    let mut watcher = notify::recommended_watcher(move |res| {
        let _ = tx.send(res);
    })?;
    for dir in libraries.iter().flat_map(|(_, dirs)| dirs) {
        watcher.watch(dir, RecursiveMode::Recursive)?;
        tracing::info!("слежу за {}", dir.display());
    }

    loop {
        // Блокируемся до первого события, дальше собираем хвост пачки.
        let first = rx.recv()?;
        if !interesting(&first) {
            continue;
        }
        // Дебаунс: пока события идут чаще, чем раз в DEBOUNCE - копирование ещё не закончилось.
        while rx.recv_timeout(DEBOUNCE).is_ok() {}

        let mut changed = false;
        let mut db = state.db.lock().unwrap();
        for (library_id, dirs) in &libraries {
            match crate::scanner::scan(&mut db, dirs, *library_id) {
                Ok(rep) if rep.added + rep.updated + rep.removed > 0 => {
                    changed = true;
                    tracing::info!("библиотека {library_id} обновлена по событию ФС: {rep:?}");
                }
                Ok(_) => {}
                Err(e) => tracing::warn!("пересканирование библиотеки {library_id} не удалось: {e}"),
            }
        }
        drop(db);
        if changed {
            state.notify(serde_json::json!({
                "type": "changed",
                "entities": ["tracks"],
                "at": crate::db::now(),
            }));
        }
    }
}

/// Отсеивает шум: интересуют только события, меняющие состав или содержимое файлов.
/// Открытия на чтение и изменения времени доступа сканировать не за чем.
fn interesting(res: &notify::Result<notify::Event>) -> bool {
    use notify::EventKind::*;
    match res {
        Ok(ev) => matches!(ev.kind, Create(_) | Remove(_) | Modify(_)),
        // Ошибку тоже считаем поводом: переполнение очереди inotify означает,
        // что события мы потеряли и состояние на диске неизвестно.
        Err(_) => true,
    }
}
