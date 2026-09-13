//! Встроенный веб-клиент: одна страница из `web/app`, вкомпилированная в бинарник через
//! rust-embed. Сборщика/бандлера нет намеренно - это простой vanilla-JS клиент (вход,
//! библиотека, плеер через `<audio>`, панель владельца), лишний toolchain ему не нужен.

use axum::{
    body::Body,
    http::{header, StatusCode, Uri},
    response::{IntoResponse, Response},
    routing::get,
    Router,
};
use rust_embed::Embed;

#[derive(Embed)]
#[folder = "../web/app"]
struct Assets;

pub fn router() -> Router {
    Router::new()
        .route("/", get(index))
        .route("/manifest.json", get(file))
        .route("/sw.js", get(file))
        // Шрифты лежат подпапкой, а роутер перечисляет пути поимённо - без этой строки
        // @font-face получал бы 404. `serve` уже отдаёт правильный MIME через mime_guess.
        .route("/fonts/{*path}", get(file))
}

async fn index() -> Response {
    serve("index.html")
}

async fn file(uri: Uri) -> Response {
    serve(uri.path().trim_start_matches('/'))
}

fn serve(path: &str) -> Response {
    match Assets::get(path) {
        Some(content) => {
            let mime = mime_guess::from_path(path).first_or_octet_stream();
            Response::builder()
                .status(StatusCode::OK)
                .header(header::CONTENT_TYPE, mime.as_ref())
                .body(Body::from(content.data))
                .unwrap()
        }
        None => (StatusCode::NOT_FOUND, "404").into_response(),
    }
}

#[cfg(test)]
mod tests {
    use super::Assets;

    #[test]
    fn invite_link_opens_registration_flow() {
        let html = Assets::get("index.html").expect("embedded web client");
        let html = std::str::from_utf8(&html.data).expect("utf-8 html");
        assert!(html.contains("id=\"inviteView\""));
        assert!(html.contains("if (inviteToken()) showAuthView()"));
        assert!(html.contains("'/accept'"));
    }

    /// Имена пользователей и библиотек подставляются в JS-строки внутри onclick-атрибутов,
    /// поэтому escapeHtml обязан экранировать апостроф и бэктик, а не только `& < > "`.
    /// Без этого имя вида `x', alert(1), '` выполняет произвольный код в сессии владельца.
    #[test]
    fn escape_html_covers_quote_characters() {
        let html = Assets::get("index.html").expect("embedded web client");
        let html = std::str::from_utf8(&html.data).expect("utf-8 html");
        let body = html
            .split_once("function escapeHtml(s)")
            .expect("escapeHtml defined")
            .1;
        let body = body.split_once('}').expect("escapeHtml body").0;
        for needle in ["&amp;", "&lt;", "&gt;", "&quot;", "&#39;", "&#96;"] {
            assert!(body.contains(needle), "escapeHtml не экранирует {needle}");
        }
    }

    /// Ловит ровно тот баг, что был: JS читал плоские поля (`tracks_total`,
    /// `broken_count`, `broken_files`), которых `Health` в `server/src/library.rs`
    /// не отдаёт - там `tracks` и вложенные `{count, items}` (`Sampled<T>`). Если
    /// кто-то опять переименует поле на одной стороне и забудет про другую, сборка
    /// это не поймает - но эта проверка ловит хотя бы уход в сторону старых имён.
    #[test]
    fn в_панели_есть_проверка_и_установка_обновления() {
        let html = Assets::get("index.html").expect("embedded web client");
        let html = std::str::from_utf8(&html.data).expect("utf-8 html");
        assert!(html.contains("/update/check"), "нет запроса проверки обновления");
        assert!(html.contains("/update/install"), "нет запроса установки обновления");
        assert!(html.contains("adminCheckUpdate"), "нет кнопки проверки");
    }

    #[test]
    fn активная_вкладка_переживает_перезагрузку() {
        let html = Assets::get("index.html").expect("embedded web client");
        let html = std::str::from_utf8(&html.data).expect("utf-8 html");
        // Вкладка запоминается и восстанавливается, иначе перезагрузка страницы всегда
        // выкидывала бы обратно на список треков.
        assert!(html.contains("nami_active_tab"), "нет ключа хранения вкладки");
        assert!(html.contains("localStorage.setItem(TAB_STORAGE_KEY"), "вкладка не сохраняется");
        assert!(html.contains("restoreTab()"), "вкладка не восстанавливается");
    }

    #[test]
    fn health_report_uses_actual_server_field_names() {
        let html = Assets::get("index.html").expect("embedded web client");
        let html = std::str::from_utf8(&html.data).expect("utf-8 html");
        let body = html
            .split_once("async function adminCheckHealth()")
            .expect("adminCheckHealth defined")
            .1;
        let body = body.split_once("\n// --- Модальные окна")
            .map(|(b, _)| b)
            .unwrap_or(body);
        // Реальные имена полей ответа /api/library/health.
        for needle in ["h.tracks", "h.broken", "h.missing", "h.without_artist",
            "h.without_album", "h.without_year", "h.duplicate_groups"] {
            assert!(body.contains(needle), "адрес поля {needle} пропал из отчёта о здоровье");
        }
        // Старые несуществующие плоские имена, из-за которых был undefined.
        for stale in ["tracks_total", "broken_count", "missing_tags_count", "broken_files"] {
            assert!(!body.contains(stale), "в отчёте вернулось несуществующее поле {stale}");
        }
    }

    /// `BrokenFile` (server/src/library.rs) - это `{ path, error, failed_at }`, не
    /// `TrackRef`: полей artist/title у него нет. Раздел битых файлов обязан читать
    /// `f.error`, а не гонять их через общий рендерер треков - иначе текст ошибки,
    /// ради которого раздел вообще существует, пропадает с экрана.
    #[test]
    fn broken_files_render_with_error_not_generic_track_template() {
        let html = Assets::get("index.html").expect("embedded web client");
        let html = std::str::from_utf8(&html.data).expect("utf-8 html");
        assert!(html.contains("f.error"), "раздел битых файлов не показывает текст ошибки");
        assert!(html.contains("renderBrokenFile"), "битые файлы должны рендериться своим шаблоном");
    }
}
