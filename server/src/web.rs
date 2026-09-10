//! Встроенный PWA-клиент: раздача статики из web/dist через rust-embed.

use axum::{
    body::Body,
    http::{header, StatusCode, Uri},
    response::{IntoResponse, Response},
    routing::get,
    Router,
};
use rust_embed::Embed;

#[derive(Embed)]
#[folder = "../web/dist"]
struct Assets;

pub fn router() -> Router {
    Router::new()
        .route("/", get(index))
        .route("/admin", get(index))
        .route("/manifest.json", get(file))
        .route("/sw.js", get(file))
        .route("/assets/{*path}", get(file))
}

async fn index() -> Response {
    serve("index.html")
}

async fn file(uri: Uri) -> Response {
    serve(uri.path().trim_start_matches('/'))
}

fn serve(path: &str) -> Response {
    // Пробуем найти в dist/, если не нашли - ищем с префиксом public/
    let asset = Assets::get(path).or_else(|| {
        if !path.starts_with("public/") {
            Assets::get(&format!("public/{}", path))
        } else {
            None
        }
    });

    match asset {
        Some(content) => {
            let mime = mime_guess::from_path(path).first_or_octet_stream();
            let body = Body::from(content.data);
            Response::builder()
                .status(StatusCode::OK)
                .header(header::CONTENT_TYPE, mime.as_ref())
                .body(body)
                .unwrap()
        }
        None => (StatusCode::NOT_FOUND, "404").into_response(),
    }
}
