//! Веб-мастер первичной настройки.
//!
//! Запускается автоматически ТОЛЬКО когда рядом нет `config.toml` (см. main.rs): в этом
//! режиме сервер поднимает один этот роутер по HTTP, без БД и без остального API. Мастер
//! пишет `config.toml` и откладывает логин/пароль владельца в файл `.nami-setup-owner`
//! рядом. После перезапуска сервер видит config, запускается нормально и при первом старте
//! (пользователей ещё нет) заводит владельца из этого файла, затем удаляет его.
//!
//! Маршрут `/setup` у обычного (уже настроенного) сервера занят другой страницей -
//! мастером СОПРЯЖЕНИЯ устройств по QR (api.rs). Это разные фазы жизни сервера и никогда
//! не работают одновременно.

use std::path::{Path, PathBuf};

use axum::extract::Query;
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde::{Deserialize, Serialize};

/// Куда мастер кладёт логин/пароль владельца до перезапуска.
const OWNER_FILE: &str = ".nami-setup-owner";

pub fn routes() -> Router {
    Router::new()
        .route("/setup", get(page))
        .route("/", get(redirect_to_setup))
        .route("/setup/api/validate-path", get(validate_path))
        .route("/setup/api/save", post(save_config))
}

async fn redirect_to_setup() -> Response {
    axum::response::Redirect::to("/setup").into_response()
}

async fn page() -> Html<&'static str> {
    Html(include_str!("../web/setup.html"))
}

#[derive(Deserialize)]
struct ValidatePathQuery {
    path: String,
}

#[derive(Serialize)]
struct ValidatePathResult {
    ok: bool,
    exists: bool,
    audio_files: usize,
    message: String,
}

async fn validate_path(Query(q): Query<ValidatePathQuery>) -> Json<ValidatePathResult> {
    let path = Path::new(&q.path);
    if !path.is_dir() {
        if !path.exists() {
            if let Err(e) = std::fs::create_dir_all(path) {
                return Json(ValidatePathResult {
                    ok: false,
                    exists: false,
                    audio_files: 0,
                    message: format!("Папка не существует и её не удалось создать: {e}"),
                });
            }
        } else {
            return Json(ValidatePathResult {
                ok: false,
                exists: true,
                audio_files: 0,
                message: "Указанный путь существует, но это файл, а не папка".into(),
            });
        }
    }

    let audio = walkdir::WalkDir::new(path)
        .max_depth(5)
        .into_iter()
        .filter_map(|e| e.ok())
        .filter(|e| e.file_type().is_file())
        .filter(|e| {
            e.path()
                .extension()
                .and_then(|s| s.to_str())
                .map(|x| {
                    matches!(
                        x.to_ascii_lowercase().as_str(),
                        "mp3" | "flac" | "m4a" | "ogg" | "opus" | "wav" | "aac" | "alac" | "aif" | "aiff"
                    )
                })
                .unwrap_or(false)
        })
        .count();

    Json(ValidatePathResult {
        ok: true,
        exists: true,
        audio_files: audio,
        message: if audio > 0 {
            format!("Папка добавлена (найдено {audio} аудиофайлов)")
        } else {
            "Папка добавлена (пока пустая, треки можно загрузить позже из приложения)".into()
        },
    })
}

#[derive(Deserialize)]
struct SaveConfigRequest {
    music_dirs: Vec<String>,
    port: u16,
    tls: bool,
    admin_username: String,
    admin_password: String,
    transcode_cache_mb: u32,
    watch: bool,
    /// Внешний адрес (Tailscale MagicDNS, домен, Cloudflare Tunnel). Пусто - только дома.
    #[serde(default)]
    external_url: String,
}

async fn save_config(Json(req): Json<SaveConfigRequest>) -> Response {
    if req.admin_username.trim().is_empty() || req.admin_password.chars().count() < 8 {
        return (StatusCode::BAD_REQUEST, Json(msg("Логин обязателен, пароль - от 8 символов")))
            .into_response();
    }

    let mut music_dirs = req
        .music_dirs
        .into_iter()
        .map(|d| d.trim().to_string())
        .filter(|d| !d.is_empty())
        .collect::<Vec<_>>();

    if music_dirs.is_empty() {
        let _ = std::fs::create_dir_all("music");
        music_dirs.push("music".to_string());
    }

    let dirs = music_dirs
        .iter()
        .map(|p| format!("\"{}\"", p.replace('\\', "\\\\").replace('"', "")))
        .collect::<Vec<_>>()
        .join(", ");
    // Нормализуем внешний адрес: без завершающего слэша, с явной схемой.
    let ext = req.external_url.trim().trim_end_matches('/');
    let ext = if ext.is_empty() {
        String::new()
    } else if ext.starts_with("http://") || ext.starts_with("https://") {
        ext.to_string()
    } else {
        format!("https://{ext}")
    };
    let config = format!(
        "# Конфигурация Nami, создана мастером настройки.\n\
         port = {}\n\
         tls = {}\n\
         music_dirs = [{dirs}]\n\
         transcode_cache_mb = {}\n\
         watch = {}\n\
         external_url = \"{}\"\n",
        req.port,
        req.tls,
        req.transcode_cache_mb,
        req.watch,
        ext.replace('\\', "\\\\").replace('"', ""),
    );
    if let Err(e) = std::fs::write("config.toml", config) {
        return (StatusCode::INTERNAL_SERVER_ERROR, Json(msg(&format!("config.toml: {e}"))))
            .into_response();
    }
    if let Err(e) =
        write_owner_file(&owner_path(), req.admin_username.trim(), &req.admin_password)
    {
        return (StatusCode::INTERNAL_SERVER_ERROR, Json(msg(&format!("{OWNER_FILE}: {e}"))))
            .into_response();
    }

    Json(msg("Готово. Перезапустите сервер - он подхватит config.toml и заведёт владельца."))
        .into_response()
}

fn msg(text: &str) -> serde_json::Value {
    serde_json::json!({ "ok": true, "message": text })
}

fn owner_path() -> PathBuf {
    Path::new("config.toml").with_file_name(OWNER_FILE)
}

fn write_owner_file(path: &Path, user: &str, pass: &str) -> std::io::Result<()> {
    std::fs::write(path, format!("{user}\n{pass}\n"))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600));
    }
    Ok(())
}

/// Читает и УДАЛЯЕТ отложенные мастером логин/пароль владельца. `None` - файла нет.
pub fn pending_owner() -> Option<(String, String)> {
    read_owner_file(&owner_path())
}

fn read_owner_file(path: &Path) -> Option<(String, String)> {
    let text = std::fs::read_to_string(path).ok()?;
    let mut lines = text.lines();
    let user = lines.next()?.trim().to_string();
    let pass = lines.next()?.to_string();
    let _ = std::fs::remove_file(path);
    if user.is_empty() || pass.is_empty() {
        None
    } else {
        Some((user, pass))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn файл_владельца_пишется_читается_и_удаляется() {
        let dir = std::env::temp_dir().join(format!("nami-setup-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join(OWNER_FILE);

        write_owner_file(&path, "хозяин", "пароль12345").unwrap();
        assert_eq!(
            read_owner_file(&path),
            Some(("хозяин".into(), "пароль12345".into()))
        );
        // Прочитан один раз - файла больше нет.
        assert!(!path.exists());
        assert_eq!(read_owner_file(&path), None);

        std::fs::remove_dir_all(&dir).ok();
    }
}
