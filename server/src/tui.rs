//! Полноэкранный TUI управления сервером: `nami-server tui`.
//!
//! Цель - дать оператору за терминалом всё то же, что владелец делает в веб-панели
//! (`web/app/index.html`): пользователи, папки библиотеки, сканирование, здоровье,
//! устройства, гостевые ссылки, режим библиотеки. Управление стрелками и Enter, а не
//! занумерованное меню с вводом цифры - так просил пользователь, да и обычный CLI
//! (`nami users …`, `nami library …` в cli.rs) уже покрывает сценарии со скриптами.
//!
//! # Почему нет отдельного query-слоя
//!
//! TUI намеренно не пишет свой SQL - он вызывает те же функции, что использует
//! HTTP API (`users.rs`, `library.rs`, `share.rs`, `db.rs`). Где функции не хватало
//! (список гостевых ссылок, отзыв по хешу) - она добавлена рядом с остальными в
//! `share.rs`, а не продублирована здесь. Так веб и TUI не могут разъехаться в
//! бизнес-логике.
//!
//! # Одновременная работа с запущенным сервером
//!
//! БД открывается в режиме WAL (см. `db.rs`: `PRAGMA journal_mode = WAL`), а он
//! осмысленно сделан именно для этого случая - один писатель и сколько угодно
//! читателей работают с одним файлом без обоюдной блокировки на каждый чих. Короткие
//! операции TUI (создать/удалить пользователя, отозвать ссылку, дописать конфиг) -
//! это одиночные insert/delete/update и укладываются в стандартный `busy_timeout`
//! rusqlite: если сервер в этот момент сам пишет, TUI-запрос коротко подождёт и
//! пройдёт, а не упадёт с "database is locked". Единственная операция, которую лучше
//! не гонять параллельно с работающим сервером - полное сканирование библиотеки
//! (`Library::Scan`): оно может конкурировать с автосканированием (`watcher.rs`) за
//! одни и те же строки `tracks`. Это не отличается от того же риска в обычном CLI
//! (`nami scan` уже сегодня можно запустить параллельно с сервером), поэтому TUI
//! просто предупреждает перед стартом длинных операций текстом на экране, а не
//! пытается изобретать межпроцессный лок поверх SQLite.

use std::io;
use std::time::Duration;

use crossterm::event::{self, Event, KeyCode, KeyEventKind};
use crossterm::execute;
use crossterm::terminal::{disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen};
use ratatui::backend::CrosstermBackend;
use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::widgets::{Block, Borders, List, ListItem, ListState, Paragraph, Wrap};
use ratatui::Terminal;
use rusqlite::Connection;

use crate::cli::format_ts;
use crate::config::Config;
use crate::users::{self, Ident, LibraryMode};

type Res<T> = crate::Res<T>;

/// Точка входа подкоманды `nami-server tui`.
pub fn run(cfg: &Config) -> Res<()> {
    let conn = crate::db::open(&cfg.db_path)?;

    enable_raw_mode()?;
    let mut stdout = io::stdout();
    execute!(stdout, EnterAlternateScreen)?;
    let backend = CrosstermBackend::new(stdout);
    let mut term = Terminal::new(backend)?;

    let result = main_menu(&mut term, &conn, cfg);

    // Восстанавливаем терминал в любом случае, даже если внутри была ошибка -
    // иначе пользователь останется в альтернативном экране без эха ввода.
    disable_raw_mode()?;
    execute!(term.backend_mut(), LeaveAlternateScreen)?;
    term.show_cursor()?;

    result
}

type Term = Terminal<CrosstermBackend<io::Stdout>>;

/// Один пункт корневого меню.
const MAIN_ITEMS: &[&str] = &[
    "Пользователи",
    "Библиотека (папки, скан, здоровье)",
    "Режим библиотеки",
    "Устройства",
    "Общие ссылки",
    "Треки (поиск и удаление)",
    "Обновление сервера",
    "Выход",
];

/// Пункты меню вместе с управлением сервером. Первая строка меняется по состоянию службы:
/// один и тот же пункт то запускает, то останавливает - отдельные «включить» и «выключить»,
/// из которых один всегда бесполезен, читаются хуже.
fn main_items() -> Vec<String> {
    let mut items: Vec<String> = Vec::new();
    #[cfg(windows)]
    items.push(server_control_label());
    items.extend(MAIN_ITEMS.iter().map(|s| s.to_string()));
    items
}

#[cfg(windows)]
fn server_control_label() -> String {
    match crate::service::state() {
        None => "Сервер: не установлен как служба (Enter - установить)".to_string(),
        Some(st) if format!("{st:?}") == "Running" => "Сервер работает (Enter - выключить)".to_string(),
        Some(_) => "Сервер выключен (Enter - включить)".to_string(),
    }
}

/// Выполняет действие над службой, при отказе в доступе повторяя его с запросом прав.
///
/// Прямая попытка идёт первой не из экономии: если TUI и так запущен администратором, лишний
/// запрос UAC был бы навязчивым. А обычному пользователю управление службами запрещено, и
/// системная ошибка выглядит как «IO error in winapi call» - по ней понять ничего нельзя,
/// поэтому вместо показа этого текста просто просим права.
#[cfg(windows)]
fn service_action(
    direct: impl FnOnce() -> Res<()>,
    verb: &str,
    done: &str,
    expect_running: bool,
) -> String {
    if direct().is_err() {
        let _ = crate::service::run_elevated(verb);
    }
    // Отчитываемся по фактическому состоянию, а не по коду возврата. Отменённый запрос UAC
    // виден PowerShell как обычное завершение, и доверие коду возврата означало бы бодрое
    // «Сервер включён» при выключенном сервере.
    let running = crate::service::is_running();
    if running == expect_running {
        done.to_string()
    } else if expect_running {
        "Сервер не включился.

Управление службой требует прав администратора: подтвердите          запрос Windows либо откройте этот экран от имени администратора."
            .to_string()
    } else {
        "Сервер не выключился.

Управление службой требует прав администратора: подтвердите          запрос Windows либо откройте этот экран от имени администратора."
            .to_string()
    }
}

/// Возвращает сообщение для показа пользователю. Ошибка здесь не должна валить весь TUI.
#[cfg(windows)]
fn toggle_server() -> String {
    use crate::service;
    match service::state() {
        None => service_action(
            || std::env::current_exe().map_err(crate::Err::from).and_then(|exe| service::install(&exe)),
            "install",
            "Служба зарегистрирована и запущена. Дальше сервер будет подниматься вместе с Windows.",
            true,
        ),
        Some(st) if format!("{st:?}") == "Running" => {
            service_action(service::stop, "stop", "Сервер выключен.", false)
        }
        Some(_) => service_action(service::start, "start", "Сервер включён.", true),
    }
}

/// Сводка для главного экрана: человек должен с первого взгляда понять, работает ли сервер и
/// куда подключаться, не зная ни одной команды.
fn summary(conn: &Connection, cfg: &Config) -> Vec<String> {
    let mut lines = Vec::new();

    lines.push(format!("Версия: {}", crate::update::current_version()));

    #[cfg(windows)]
    {
        lines.push(match crate::service::state() {
            None => "Состояние: сервер не установлен как служба".to_string(),
            Some(st) if format!("{st:?}") == "Running" => "Состояние: работает".to_string(),
            Some(_) => "Состояние: выключен".to_string(),
        });
    }
    #[cfg(not(windows))]
    lines.push("Состояние: см. systemctl status nami".to_string());

    let scheme = if cfg.tls { "https" } else { "http" };
    let ip = crate::local_ip();
    lines.push(format!("Адрес для телефона: {scheme}://{ip}:{}", cfg.port));
    lines.push(format!("Здесь, на этом компьютере: {scheme}://localhost:{}", cfg.port));
    lines.push(format!(
        "В библиотеке: {} треков, пользователей: {}",
        crate::api::track_count(conn),
        users::count(conn),
    ));
    lines
}

fn main_menu(term: &mut Term, conn: &Connection, cfg: &Config) -> Res<()> {
    let mut state = ListState::default();
    state.select(Some(0));
    loop {
        // Состав пересобирается на каждом кадре: подпись первой строки зависит от того,
        // работает ли сервер прямо сейчас, а он мог быть остановлен и снаружи.
        let items = main_items();
        let labels: Vec<&str> = items.iter().map(|s| s.as_str()).collect();
        let info = summary(conn, cfg);
        term.draw(|f| draw_home(f, &info, &labels, &mut state))?;
        match read_key()? {
            Key::Up => move_sel(&mut state, items.len(), -1),
            Key::Down => move_sel(&mut state, items.len(), 1),
            Key::Enter => {
                // Со сдвигом: под Windows первый пункт - управление сервером, дальше общий
                // список; на других системах его нет, и нумерация прежняя.
                let raw = state.selected().unwrap_or(0);
                #[cfg(windows)]
                let index = {
                    if raw == 0 {
                        let msg = toggle_server();
                        message(term, "Сервер", &msg)?;
                        continue;
                    }
                    raw - 1
                };
                #[cfg(not(windows))]
                let index = raw;

                match index {
                    0 => users_screen(term, conn)?,
                    1 => library_screen(term, conn, cfg)?,
                    2 => library_mode_screen(term, conn)?,
                    3 => devices_screen(term, conn)?,
                    4 => shares_screen(term, conn)?,
                    5 => tracks_screen(term, conn, cfg)?,
                    6 => update_screen(term)?,
                    _ => return Ok(()),
                }
            }
            Key::Quit => return Ok(()),
            Key::Other => {}
        }
    }
}

/// Один трек в списке удаления.
struct TrackRow {
    id: i64,
    title: String,
    artist: Option<String>,
    album: Option<String>,
    path: String,
}

/// Поиск и удаление треков.
///
/// Показывается страница, а не вся библиотека: на десятках тысяч строк список пришлось бы
/// держать в памяти целиком и рисовать каждый кадр. Поиск сужает выборку до нужного - им же
/// и пользуются вместо листания.
fn tracks_screen(term: &mut Term, conn: &Connection, cfg: &Config) -> Res<()> {
    let mut query = String::new();
    loop {
        let rows = find_tracks(conn, &query, TRACKS_PAGE)?;
        let mut items = track_menu_items(&rows);
        items.push(if query.is_empty() {
            "🔍 Найти трек по названию или артисту".to_string()
        } else {
            format!("🔍 Поиск: «{query}» (Enter - изменить, пусто - сбросить)")
        });

        let mut state = ListState::default();
        state.select(Some(0));
        loop {
            let hint = if rows.len() == TRACKS_PAGE {
                "Показаны первые записи - сузьте поиск. ↑↓ выбор, Enter - действие, Esc/q - назад"
            } else {
                "↑↓ выбор, Enter - действие, Esc/q - назад"
            };
            term.draw(|f| draw_menu_owned(f, "Треки", &items, &mut state, Some(hint)))?;
            match read_key()? {
                Key::Up => move_sel(&mut state, items.len(), -1),
                Key::Down => move_sel(&mut state, items.len(), 1),
                Key::Quit => return Ok(()),
                Key::Enter => {
                    let i = state.selected().unwrap_or(0);
                    if i == rows.len() {
                        query = text_input(term, "Поиск по названию или артисту", false)?
                            .unwrap_or_default()
                            .trim()
                            .to_string();
                    } else {
                        delete_track_row(term, conn, cfg, &rows[i])?;
                    }
                    break; // список мог измениться - перечитываем
                }
                Key::Other => {}
            }
        }
    }
}

const TRACKS_PAGE: usize = 50;

fn find_tracks(conn: &Connection, query: &str, limit: usize) -> Res<Vec<TrackRow>> {
    let like = format!("%{}%", query.trim().to_lowercase());
    let mut stmt = conn.prepare(
        "SELECT id, title, artist, album, path FROM tracks
         WHERE ?1 = '%%' OR LOWER(title) LIKE ?1 OR LOWER(COALESCE(artist,'')) LIKE ?1
         ORDER BY artist, album, title LIMIT ?2",
    )?;
    let rows = stmt
        .query_map(rusqlite::params![like, limit as i64], |r| {
            Ok(TrackRow {
                id: r.get(0)?,
                title: r.get(1)?,
                artist: r.get(2)?,
                album: r.get(3)?,
                path: r.get(4)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

/// Чистая функция форматирования - проверяется тестом без терминала.
fn track_menu_items(rows: &[TrackRow]) -> Vec<String> {
    rows.iter()
        .map(|t| {
            let artist = t.artist.as_deref().unwrap_or("без исполнителя");
            let album = t.album.as_deref().unwrap_or("без альбома");
            format!("#{:<6} {} - {} ({})", t.id, artist, t.title, album)
        })
        .collect()
}

fn delete_track_row(term: &mut Term, conn: &Connection, cfg: &Config, row: &TrackRow) -> Res<()> {
    let artist = row.artist.as_deref().unwrap_or("без исполнителя");
    let question = format!(
        "Удалить «{} - {}»?

Файл переедет в корзину сервера, а не пропадёт:
{}",
        artist, row.title, row.path
    );
    if !confirm(term, &question)? {
        return Ok(());
    }
    // Удаляем тем же кодом, что и HTTP API: перенос файла в корзину, чистка позиций
    // воспроизведения и надгробие для дельта-синхронизации живут там, а не здесь.
    if crate::api::remove_track(conn, &cfg.data_dir, row.id) {
        message(term, "Треки", "Трек удалён. Файл лежит в корзине сервера.")
    } else {
        message(term, "Треки", "Трек не найден - возможно, его уже удалили.")
    }
}

/// Проверка и установка обновления сервера.
fn update_screen(term: &mut Term) -> Res<()> {
    status(term, "Обновление", "Проверяю наличие новой версии...")?;
    let info = match crate::update::check() {
        Ok(i) => i,
        Err(e) => return message(term, "Обновление", &format!("Не удалось проверить: {e}")),
    };

    let latest = info.latest.clone().unwrap_or_else(|| "неизвестно".into());
    if !info.available {
        return message(
            term,
            "Обновление",
            &format!("Установлена версия {}. Это последняя.", info.current),
        );
    }
    if info.asset_url.is_none() {
        return message(
            term,
            "Обновление",
            &format!("Доступна версия {latest}, но готовой сборки для этой платформы в релизе нет."),
        );
    }

    let question = format!(
        "Установлена {}, доступна {latest}. Обновить сейчас? Сервер будет перезапущен.",
        info.current
    );
    if !confirm(term, &question)? {
        return Ok(());
    }

    status(term, "Обновление", "Скачиваю и устанавливаю...")?;
    match crate::update::install(&info) {
        Ok(v) => {
            message(term, "Обновление", &format!("Обновлено до {v}. Перезапускаю сервер."))?;
            // Перезапуск через ту же службу, что и везде: TUI сам сервером не управляет.
            #[cfg(windows)]
            {
                let _ = crate::service::stop();
                let _ = crate::service::start();
            }
            #[cfg(not(windows))]
            {
                let _ = std::process::Command::new("systemctl").args(["restart", "nami"]).output();
            }
            Ok(())
        }
        Err(e) => message(term, "Обновление", &format!("Не удалось обновить: {e}")),
    }
}

// ---------------------------------------------------------------- ввод

enum Key {
    Up,
    Down,
    Enter,
    Quit,
    Other,
}

/// Блокирующее чтение одной "осмысленной" клавиши. Esc и q равнозначны выходу/назад -
/// так требовалось в задаче явно.
fn read_key() -> Res<Key> {
    loop {
        if event::poll(Duration::from_millis(200))? {
            if let Event::Key(k) = event::read()? {
                if k.kind != KeyEventKind::Press {
                    continue;
                }
                return Ok(match k.code {
                    KeyCode::Up | KeyCode::Char('k') => Key::Up,
                    KeyCode::Down | KeyCode::Char('j') => Key::Down,
                    KeyCode::Enter => Key::Enter,
                    KeyCode::Esc | KeyCode::Char('q') => Key::Quit,
                    _ => Key::Other,
                });
            }
        }
    }
}

fn move_sel(state: &mut ListState, len: usize, delta: isize) {
    if len == 0 {
        return;
    }
    let cur = state.selected().unwrap_or(0) as isize;
    let next = (cur + delta).rem_euclid(len as isize);
    state.select(Some(next as usize));
}

// ---------------------------------------------------------------- рисование

/// Главный экран: сверху сводка, снизу действия. Сводка не прокручивается и не прячется -
/// ради неё ярлык и открывают, а меню без ответа на вопрос «а сервер-то работает?» заставляло бы
/// лезть в диспетчер задач.
fn draw_home(f: &mut ratatui::Frame, info: &[String], items: &[&str], state: &mut ListState) {
    let area = f.area();
    let info_height = info.len() as u16 + 2;
    let chunks = Layout::default()
        .direction(Direction::Vertical)
        .constraints([Constraint::Length(info_height), Constraint::Min(3), Constraint::Length(1)])
        .split(area);

    let width = chunks[0].width.saturating_sub(4) as usize;
    let text: Vec<String> = info.iter().map(|l| clip_to_width(l, width)).collect();
    f.render_widget(
        Paragraph::new(text.join("
"))
            .block(Block::default().borders(Borders::ALL).title("NAMI - сервер"))
            .wrap(Wrap { trim: false }),
        chunks[0],
    );

    let list_items: Vec<ListItem> = items
        .iter()
        .map(|s| ListItem::new(clip_to_width(s, chunks[1].width.saturating_sub(4) as usize)))
        .collect();
    let list = List::new(list_items)
        .block(Block::default().borders(Borders::ALL).title("Что сделать"))
        .highlight_style(Style::default().add_modifier(Modifier::REVERSED))
        .highlight_symbol("> ");
    f.render_stateful_widget(list, chunks[1], state);

    f.render_widget(
        Paragraph::new("↑↓ выбор, Enter - открыть, Esc/q - выход")
            .style(Style::default().fg(Color::DarkGray)),
        chunks[2],
    );
}

fn draw_menu(f: &mut ratatui::Frame, title: &str, items: &[&str], state: &mut ListState, hint: Option<&str>) {
    let owned: Vec<String> = items.iter().map(|s| s.to_string()).collect();
    draw_menu_owned(f, title, &owned, state, hint);
}

fn draw_menu_owned(f: &mut ratatui::Frame, title: &str, items: &[String], state: &mut ListState, hint: Option<&str>) {
    let area = f.area();
    let chunks = Layout::default()
        .direction(Direction::Vertical)
        .constraints([Constraint::Min(3), Constraint::Length(1)])
        .split(area);

    let list_items: Vec<ListItem> = items
        .iter()
        .map(|s| ListItem::new(clip_to_width(s, chunks[0].width.saturating_sub(4) as usize)))
        .collect();
    let list = List::new(list_items)
        .block(Block::default().borders(Borders::ALL).title(title))
        .highlight_style(Style::default().add_modifier(Modifier::REVERSED))
        .highlight_symbol("> ");
    f.render_stateful_widget(list, chunks[0], state);

    let hint_text = hint.unwrap_or("↑↓ выбор, Enter - открыть, Esc/q - назад");
    f.render_widget(Paragraph::new(hint_text).style(Style::default().fg(Color::DarkGray)), chunks[1]);
}

/// Обрезка строки ПО СИМВОЛАМ, а не по байтам: в кириллице каждый символ занимает
/// 2 байта в UTF-8, и обрезка по `s[..n]` может как запаниковать на границе символа,
/// так и (при "удачном" совпадении границ) молча испортить текст. `deploy/install.sh`
/// уже наступал на это на голом bash; здесь считаем ширину через `chars()`.
fn clip_to_width(s: &str, max_chars: usize) -> String {
    if max_chars == 0 {
        return String::new();
    }
    let count = s.chars().count();
    if count <= max_chars {
        return s.to_string();
    }
    if max_chars <= 1 {
        return "…".to_string();
    }
    let mut out: String = s.chars().take(max_chars - 1).collect();
    out.push('…');
    out
}

/// Диалог подтверждения опасного действия. По умолчанию выбрано безопасное "Нет" -
/// случайный Enter не должен удалить пользователя или библиотеку.
fn confirm(term: &mut Term, question: &str) -> Res<bool> {
    let options = ["Нет", "Да, подтверждаю"];
    let mut state = ListState::default();
    state.select(Some(0));
    loop {
        term.draw(|f| {
            let area = centered_rect(f.area(), 60, 7);
            let block = Block::default().borders(Borders::ALL).title("Подтверждение");
            let inner = block.inner(area);
            f.render_widget(ratatui::widgets::Clear, area);
            f.render_widget(block, area);
            let chunks = Layout::default()
                .direction(Direction::Vertical)
                .constraints([Constraint::Min(2), Constraint::Length(2)])
                .split(inner);
            f.render_widget(
                Paragraph::new(question).wrap(Wrap { trim: true }).style(Style::default().fg(Color::Yellow)),
                chunks[0],
            );
            let items: Vec<ListItem> = options.iter().map(|s| ListItem::new(*s)).collect();
            let list = List::new(items).highlight_style(Style::default().add_modifier(Modifier::REVERSED)).highlight_symbol("> ");
            f.render_stateful_widget(list, chunks[1], &mut state);
        })?;
        match read_key()? {
            Key::Up | Key::Down => move_sel(&mut state, options.len(), 1),
            Key::Enter => return Ok(state.selected() == Some(1)),
            Key::Quit => return Ok(false),
            Key::Other => {}
        }
    }
}

/// Отображает сообщение (результат операции, ошибку) и ждёт любую клавишу.
fn message(term: &mut Term, title: &str, text: &str) -> Res<()> {
    status(term, title, text)?;
    loop {
        if event::poll(Duration::from_millis(200))? {
            if let Event::Key(k) = event::read()? {
                if k.kind == KeyEventKind::Press {
                    return Ok(());
                }
            }
        }
    }
}

/// Рисует то же окно, что [`message`], но не ждёт нажатия клавиши.
///
/// Нужен для "Проверяю..."/"Скачиваю..." перед долгой блокирующей операцией: message() ждёт
/// Enter ДО того, как что-либо начнёт происходить, поэтому статус проверки обновлений
/// оставался на экране до нажатия клавиши, и сама проверка не запускалась, пока пользователь
/// не нажимал Enter вслепую - выглядело так, будто экран обновляется только по Enter.
fn status(term: &mut Term, title: &str, text: &str) -> Res<()> {
    term.draw(|f| {
        let area = centered_rect(f.area(), 70, 40.min(f.area().height.saturating_sub(2)));
        let block = Block::default().borders(Borders::ALL).title(title);
        f.render_widget(ratatui::widgets::Clear, area);
        let inner = block.inner(area);
        f.render_widget(block, area);
        f.render_widget(Paragraph::new(text).wrap(Wrap { trim: false }), inner);
    })?;
    Ok(())
}

/// Текстовое поле ввода. `mask` включает отображение звёздочек вместо символов - для
/// паролей (эхо на экране не показываем, как требовалось в задаче).
fn text_input(term: &mut Term, title: &str, mask: bool) -> Res<Option<String>> {
    let mut value = String::new();
    loop {
        term.draw(|f| {
            let area = centered_rect(f.area(), 60, 3);
            let shown: String = if mask { "*".repeat(value.chars().count()) } else { value.clone() };
            let block = Block::default().borders(Borders::ALL).title(format!("{title}  (Enter - ок, Esc - отмена)"));
            f.render_widget(ratatui::widgets::Clear, area);
            f.render_widget(Paragraph::new(shown).block(block), area);
        })?;
        if event::poll(Duration::from_millis(200))? {
            if let Event::Key(k) = event::read()? {
                if k.kind != KeyEventKind::Press {
                    continue;
                }
                match k.code {
                    KeyCode::Enter => return Ok(Some(value)),
                    KeyCode::Esc => return Ok(None),
                    KeyCode::Backspace => {
                        value.pop();
                    }
                    KeyCode::Char(c) => value.push(c),
                    _ => {}
                }
            }
        }
    }
}

fn centered_rect(r: Rect, width: u16, height: u16) -> Rect {
    let width = width.min(r.width.saturating_sub(2)).max(10);
    let height = height.min(r.height.saturating_sub(2)).max(3);
    let x = r.x + (r.width.saturating_sub(width)) / 2;
    let y = r.y + (r.height.saturating_sub(height)) / 2;
    Rect { x, y, width, height }
}

// ---------------------------------------------------------------- пользователи

fn users_screen(term: &mut Term, conn: &Connection) -> Res<()> {
    loop {
        let list = users::list(conn)?;
        let mut items = user_menu_items(&list);
        items.push("+ Добавить пользователя".to_string());
        items.push("+ Создать пригласительную ссылку".to_string());

        let mut state = ListState::default();
        state.select(Some(0));
        loop {
            term.draw(|f| draw_menu_owned(f, "Пользователи", &items, &mut state, None))?;
            match read_key()? {
                Key::Up => move_sel(&mut state, items.len(), -1),
                Key::Down => move_sel(&mut state, items.len(), 1),
                Key::Quit => return Ok(()),
                Key::Enter => {
                    let i = state.selected().unwrap_or(0);
                    if i == list.len() {
                        add_user(term, conn)?;
                    } else if i == list.len() + 1 {
                        create_invite(term, conn)?;
                    } else {
                        user_actions(term, conn, &list[i])?;
                    }
                    break; // список пользователей мог измениться - перечитываем
                }
                Key::Other => {}
            }
        }
    }
}

/// Чистая функция форматирования строк меню пользователей - вынесена отдельно,
/// чтобы проверить юнит-тестом без поднятия терминала.
fn user_menu_items(list: &[users::User]) -> Vec<String> {
    list.iter()
        .map(|u| format!("#{:<4} {:<20} {:<8} создан {}", u.id, u.username, u.role, format_ts(u.created_at)))
        .collect()
}

fn add_user(term: &mut Term, conn: &Connection) -> Res<()> {
    let Some(username) = text_input(term, "Логин нового пользователя", false)? else { return Ok(()) };
    if username.trim().is_empty() {
        return Ok(());
    }
    let Some(password) = text_input(term, "Пароль (минимум 8 символов)", true)? else { return Ok(()) };
    if password.chars().count() < 8 {
        message(term, "Ошибка", "Пароль должен содержать минимум 8 символов")?;
        return Ok(());
    }
    let Some(role) = pick_role(term)? else { return Ok(()) };
    match users::create(conn, username.trim(), &password, role, 0) {
        Ok(id) => message(term, "Готово", &format!("Пользователь «{username}» создан (ID {id}, роль {role})"))?,
        Err(e) => message(term, "Ошибка", &format!("Не удалось создать пользователя: {e}"))?,
    }
    Ok(())
}

fn pick_role(term: &mut Term) -> Res<Option<&'static str>> {
    let roles: [&str; 3] = ["owner", "user", "guest"];
    let mut state = ListState::default();
    state.select(Some(1));
    loop {
        term.draw(|f| draw_menu(f, "Роль", &roles, &mut state, Some("↑↓ выбор, Enter - ок, Esc - отмена")))?;
        match read_key()? {
            Key::Up => move_sel(&mut state, roles.len(), -1),
            Key::Down => move_sel(&mut state, roles.len(), 1),
            Key::Enter => return Ok(Some(roles[state.selected().unwrap_or(1)])),
            Key::Quit => return Ok(None),
            Key::Other => {}
        }
    }
}

fn user_actions(term: &mut Term, conn: &Connection, u: &users::User) -> Res<()> {
    let items = ["Сменить пароль", "Удалить пользователя"];
    let mut state = ListState::default();
    state.select(Some(0));
    loop {
        term.draw(|f| {
            draw_menu(
                f,
                &format!("#{} {} ({})", u.id, u.username, u.role),
                &items,
                &mut state,
                None,
            )
        })?;
        match read_key()? {
            Key::Up => move_sel(&mut state, items.len(), -1),
            Key::Down => move_sel(&mut state, items.len(), 1),
            Key::Quit => return Ok(()),
            Key::Enter => match state.selected().unwrap_or(0) {
                0 => {
                    let Some(pass) = text_input(term, &format!("Новый пароль для «{}»", u.username), true)? else { continue };
                    if pass.chars().count() < 8 {
                        message(term, "Ошибка", "Пароль должен содержать минимум 8 символов")?;
                        continue;
                    }
                    match users::reset_password(conn, u.id, &pass) {
                        Ok(_) => message(term, "Готово", "Пароль обновлён, старые сессии завершены")?,
                        Err(e) => message(term, "Ошибка", &format!("{e}"))?,
                    }
                }
                _ => {
                    if confirm(term, &format!("Удалить пользователя «{}»? Действие необратимо.", u.username))? {
                        let n = conn.execute("DELETE FROM users WHERE id = ?1", [u.id])?;
                        if n > 0 {
                            message(term, "Готово", "Пользователь удалён")?;
                            return Ok(());
                        } else {
                            message(term, "Ошибка", "Пользователь не найден (уже удалён?)")?;
                        }
                    }
                }
            },
            Key::Other => {}
        }
    }
}

fn create_invite(term: &mut Term, conn: &Connection) -> Res<()> {
    let Some(role) = pick_role_for_invite(term)? else { return Ok(()) };
    match users::create_invite(conn, 1, role, 0, Some(7 * 86400)) {
        Ok(inv) => message(
            term,
            "Пригласительная ссылка",
            &format!(
                "Токен: {}\nРоль: {}\nДействителен до: {}\n\nОтдайте токен приглашённому - регистрация примет его в приложении/веб-клиенте.",
                inv.token,
                inv.role,
                format_ts(inv.expires_at)
            ),
        )?,
        Err(e) => message(term, "Ошибка", &format!("{e}"))?,
    }
    Ok(())
}

fn pick_role_for_invite(term: &mut Term) -> Res<Option<&'static str>> {
    let roles: [&str; 2] = ["user", "guest"];
    let mut state = ListState::default();
    state.select(Some(0));
    loop {
        term.draw(|f| draw_menu(f, "Роль приглашённого", &roles, &mut state, None))?;
        match read_key()? {
            Key::Up => move_sel(&mut state, roles.len(), -1),
            Key::Down => move_sel(&mut state, roles.len(), 1),
            Key::Enter => return Ok(Some(roles[state.selected().unwrap_or(0)])),
            Key::Quit => return Ok(None),
            Key::Other => {}
        }
    }
}

// ---------------------------------------------------------------- библиотека

fn library_screen(term: &mut Term, conn: &Connection, cfg: &Config) -> Res<()> {
    let items = ["Папки библиотеки", "Сканировать сейчас", "Здоровье библиотеки"];
    let mut state = ListState::default();
    state.select(Some(0));
    loop {
        term.draw(|f| draw_menu(f, "Библиотека", &items, &mut state, None))?;
        match read_key()? {
            Key::Up => move_sel(&mut state, items.len(), -1),
            Key::Down => move_sel(&mut state, items.len(), 1),
            Key::Quit => return Ok(()),
            Key::Enter => match state.selected().unwrap_or(0) {
                0 => dirs_screen(term, cfg)?,
                1 => {
                    if confirm(
                        term,
                        "Запустить сканирование? Если сервер запущен, автосканирование папок может \
                         идти параллельно - конфликта по данным не будет (SQLite WAL), но диск \
                         и CPU займутся дважды.",
                    )? {
                        scan_now(term, cfg)?;
                    }
                }
                _ => health_screen(term, conn)?,
            },
            Key::Other => {}
        }
    }
}

fn dirs_screen(term: &mut Term, cfg: &Config) -> Res<()> {
    loop {
        let mut items: Vec<String> = cfg
            .music_dirs
            .iter()
            .map(|d| {
                let exists = if d.exists() { "OK" } else { "не найдена" };
                format!("{} [{}]", d.display(), exists)
            })
            .collect();
        items.push("+ Добавить папку".to_string());

        let mut state = ListState::default();
        state.select(Some(0));
        term.draw(|f| draw_menu_owned(f, "Папки музыки", &items, &mut state, None))?;
        match read_key()? {
            Key::Quit => return Ok(()),
            Key::Enter => {
                if state.selected() == Some(cfg.music_dirs.len()) {
                    let Some(path) = text_input(term, "Путь к папке с музыкой", false)? else { continue };
                    if path.trim().is_empty() {
                        continue;
                    }
                    let abs = std::path::PathBuf::from(path.trim());
                    let abs = if abs.is_absolute() { abs } else { std::env::current_dir()?.join(abs) };
                    if !abs.exists() {
                        message(term, "Ошибка", "Путь не существует на диске")?;
                        continue;
                    }
                    let cfg_path = crate::config::find_config_path();
                    let text = std::fs::read_to_string(&cfg_path).unwrap_or_default();
                    let updated = append_music_dir(&text, &abs.to_string_lossy());
                    std::fs::write(&cfg_path, updated)?;
                    message(term, "Готово", "Папка добавлена в config.toml. Перезапустите сервер, чтобы он её подхватил, либо запустите скан из этого TUI.")?;
                    return Ok(()); // cfg в памяти уже устарел - выходим, а не читаем его повторно
                }
            }
            _ => {}
        }
    }
}

/// Дописывает `music_dirs` в текст config.toml. Логика идентична `cli.rs::append_music_dir`
/// (там она приватная) - здесь короткая копия ради независимости модулей; альтернатива
/// (расшаривать через `pub(crate)`) не даёт выигрыша, т.к. функция всего в 12 строк.
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

fn scan_now(term: &mut Term, cfg: &Config) -> Res<()> {
    status(term, "Сканирование", "Идёт сканирование библиотеки, подождите...")?;
    let mut conn = crate::db::open(&cfg.db_path)?;
    match crate::scanner::scan(&mut conn, &cfg.music_dirs, 0) {
        Ok(rep) => message(
            term,
            "Сканирование завершено",
            &format!(
                "Файлов проверено: {}\nДобавлено новых: {}\nОбновлено тегов: {}\nУдалено треков: {}\nОшибок: {}",
                rep.scanned, rep.added, rep.updated, rep.removed, rep.failed
            ),
        ),
        Err(e) => message(term, "Ошибка сканирования", &format!("{e}")),
    }
}

fn health_screen(term: &mut Term, conn: &Connection) -> Res<()> {
    let h = crate::library::health(conn, &Ident::default())?;
    let mut text = format!(
        "Всего треков: {}\nБитых файлов: {}\nБез исполнителя: {}\nБез альбома: {}\nБез года: {}\nГрупп дублей: {}\n",
        h.tracks, h.broken.count, h.without_artist.count, h.without_album.count, h.without_year.count, h.duplicate_groups.len()
    );
    if h.broken.count > 0 {
        text.push_str("\nПримеры битых файлов:\n");
        for b in h.broken.items.iter().take(8) {
            text.push_str(&format!("  ✗ {} ({})\n", b.path, b.error));
        }
    }
    message(term, "Здоровье библиотеки", &text)
}

fn library_mode_screen(term: &mut Term, conn: &Connection) -> Res<()> {
    let items = ["Общая (shared) - одна библиотека на всех", "Раздельная (separate) - у каждого своя"];
    let current = users::library_mode(conn);
    let mut state = ListState::default();
    state.select(Some(if current == LibraryMode::Separate { 1 } else { 0 }));
    loop {
        term.draw(|f| draw_menu(f, "Режим библиотеки", &items, &mut state, None))?;
        match read_key()? {
            Key::Up => move_sel(&mut state, items.len(), -1),
            Key::Down => move_sel(&mut state, items.len(), 1),
            Key::Quit => return Ok(()),
            Key::Enter => {
                let picked = if state.selected() == Some(1) { LibraryMode::Separate } else { LibraryMode::Shared };
                if picked == current {
                    return Ok(());
                }
                if confirm(
                    term,
                    "Смена режима удалит ВСЕ треки из базы (файлы на диске не трогает) - потребуется \
                     новый скан. Продолжить?",
                )? {
                    users::set_library_mode(conn, picked)?;
                    message(term, "Готово", "Режим библиотеки изменён. Запустите скан заново.")?;
                }
                return Ok(());
            }
            Key::Other => {}
        }
    }
}

// ---------------------------------------------------------------- устройства

fn devices_screen(term: &mut Term, conn: &Connection) -> Res<()> {
    loop {
        let mut stmt = conn.prepare("SELECT id, name, created_at, last_seen_at FROM devices ORDER BY id")?;
        let rows: Vec<(i64, String, i64, Option<i64>)> = stmt
            .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))?
            .collect::<rusqlite::Result<_>>()?;
        drop(stmt);

        if rows.is_empty() {
            message(term, "Устройства", "Сопряжённых устройств пока нет.")?;
            return Ok(());
        }

        let items: Vec<String> = rows
            .iter()
            .map(|(id, name, created, seen)| {
                let seen_s = seen.map(format_ts).unwrap_or_else(|| "никогда".into());
                format!("#{:<4} {:<25} подключено {}  посл. активность {}", id, name, format_ts(*created), seen_s)
            })
            .collect();

        let mut state = ListState::default();
        state.select(Some(0));
        term.draw(|f| draw_menu_owned(f, "Устройства (Enter - отозвать)", &items, &mut state, None))?;
        match read_key()? {
            Key::Quit => return Ok(()),
            Key::Enter => {
                let i = state.selected().unwrap_or(0);
                let (id, name, ..) = &rows[i];
                if confirm(term, &format!("Отозвать сопряжение устройства «{name}»?"))? {
                    conn.execute("DELETE FROM devices WHERE id = ?1", [*id])?;
                    message(term, "Готово", "Устройство отозвано")?;
                }
            }
            _ => {}
        }
    }
}

// ---------------------------------------------------------------- общие ссылки

fn shares_screen(term: &mut Term, conn: &Connection) -> Res<()> {
    loop {
        let list = crate::share::list(conn)?;
        if list.is_empty() {
            message(term, "Общие ссылки", "Активных гостевых ссылок нет.")?;
            return Ok(());
        }
        let items: Vec<String> = list.iter().map(share_menu_line).collect();

        let mut state = ListState::default();
        state.select(Some(0));
        term.draw(|f| draw_menu_owned(f, "Общие ссылки (Enter - отозвать)", &items, &mut state, None))?;
        match read_key()? {
            Key::Quit => return Ok(()),
            Key::Enter => {
                let s = &list[state.selected().unwrap_or(0)];
                if confirm(term, &format!("Отозвать ссылку «{}»?", s.title))? {
                    // is_owner=true: TUI работает от имени администратора машины, тут запрос
                    // владельца сервера не проверяем повторно - тот, кто держит доступ к серверной
                    // консоли, уже полноправнее любого пользователя приложения.
                    crate::share::revoke_by_hash(conn, &s.token_hash, None, true)?;
                    message(term, "Готово", "Ссылка отозвана")?;
                }
            }
            _ => {}
        }
    }
}

fn share_menu_line(s: &crate::share::ShareInfo) -> String {
    let exp = s.expires_at.map(format_ts).unwrap_or_else(|| "бессрочно".into());
    let plays = match s.max_plays {
        Some(m) => format!("{}/{}", s.play_count, m),
        None => format!("{}/∞", s.play_count),
    };
    format!("«{}» - {} треков, прослушиваний {}, до {}", s.title, s.track_ids.len(), plays, exp)
}

#[cfg(test)]
mod tests {
    use super::{track_menu_items, TrackRow};

    #[test]
    fn строка_трека_показывает_исполнителя_и_альбом() {
        let rows = vec![TrackRow {
            id: 7,
            title: "Песня".into(),
            artist: Some("Артист".into()),
            album: Some("Альбом".into()),
            path: "/музыка/а.flac".into(),
        }];
        let items = track_menu_items(&rows);
        assert!(items[0].contains("Артист"), "{}", items[0]);
        assert!(items[0].contains("Песня"), "{}", items[0]);
        assert!(items[0].contains("Альбом"), "{}", items[0]);
    }

    #[test]
    fn трек_без_тегов_не_показывает_пустоту() {
        // Пустые поля читаются как сломанный интерфейс: человек не понимает, что выбирает.
        let rows = vec![TrackRow {
            id: 8,
            title: "Без тегов".into(),
            artist: None,
            album: None,
            path: "/музыка/б.mp3".into(),
        }];
        let items = track_menu_items(&rows);
        assert!(items[0].contains("без исполнителя"), "{}", items[0]);
        assert!(items[0].contains("без альбома"), "{}", items[0]);
    }
}

#[cfg(test)]
mod tests_existing {
    use super::*;

    #[test]
    fn clip_to_width_counts_chars_not_bytes() {
        // Кириллица - 2 байта на символ в UTF-8. Если бы обрезка шла по байтам,
        // строка либо паниковала бы на границе символа, либо портила текст.
        let s = "Библиотека музыки";
        let clipped = clip_to_width(s, 10);
        assert_eq!(clipped.chars().count(), 10);
        assert!(clipped.ends_with('…'));

        // Короткая строка не трогается.
        assert_eq!(clip_to_width("корот", 10), "корот");

        // Граничные случаи не паникуют.
        assert_eq!(clip_to_width("привет", 0), "");
        assert_eq!(clip_to_width("привет", 1), "…");
    }

    #[test]
    fn move_sel_wraps_around() {
        let mut state = ListState::default();
        state.select(Some(0));
        move_sel(&mut state, 3, -1);
        assert_eq!(state.selected(), Some(2)); // вверх с первого пункта -> последний
        move_sel(&mut state, 3, 1);
        assert_eq!(state.selected(), Some(0));
        move_sel(&mut state, 0, 1); // пустой список не паникует
    }

    #[test]
    fn append_music_dir_appends_and_extends() {
        let empty = "port = 4533\n";
        let with_dirs = append_music_dir(empty, "/music");
        assert!(with_dirs.contains("music_dirs = [\"/music\"]"));

        let extended = append_music_dir(&with_dirs, "/more");
        assert!(extended.contains("music_dirs = [\"/music\", \"/more\"]"));
    }

    #[test]
    fn share_menu_line_formats_limits_and_infinity() {
        let limited = crate::share::ShareInfo {
            token_hash: "h".into(),
            title: "Плейлист".into(),
            track_ids: vec![1, 2, 3],
            created_by: None,
            created_at: 0,
            expires_at: None,
            max_plays: Some(5),
            play_count: 2,
        };
        let line = share_menu_line(&limited);
        assert!(line.contains("2/5"));
        assert!(line.contains("бессрочно"));

        let unlimited = crate::share::ShareInfo { max_plays: None, ..limited };
        assert!(share_menu_line(&unlimited).contains("2/∞"));
    }
}
