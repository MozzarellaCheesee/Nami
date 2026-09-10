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
