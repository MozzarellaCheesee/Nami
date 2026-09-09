use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

use axum::extract::{ConnectInfo, Path, Query, Request, State};
use axum::http::{header, StatusCode};
use axum::middleware::Next;
use axum::response::{Html, IntoResponse, Response};
use axum::routing::{delete, get, post};
use axum::{Json, Router};
use rusqlite::Connection;
use serde::{Deserialize, Serialize};
use tower::ServiceExt;
use tower_http::services::ServeFile;

use crate::auth::{self, RateLimiter};
use crate::config::Config;
use crate::{host, scanner};

pub struct AppState {
    /// ponytail: одно соединение под мьютексом. Запросы к SQLite здесь короткие
    /// (индексный SELECT), отдача файла лок не держит. Пул (r2d2) стоит заводить,
    /// когда профиль покажет ожидание на этом мьютексе, а не заранее.
    pub db: Mutex<Connection>,
    pub cfg: Config,
    /// None, когда сервер поднят без TLS - тогда пейринг помечается небезопасным.
    pub fingerprint: Option<String>,
    pub rate: RateLimiter,
}

pub type Shared = Arc<AppState>;

/// Ошибка API одной строкой - дерева причин наружу не отдаём.
struct ApiError(StatusCode, String);

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.0, Json(serde_json::json!({ "error": self.1 }))).into_response()
    }
}

impl From<rusqlite::Error> for ApiError {
    fn from(e: rusqlite::Error) -> Self {
        ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string())
    }
}

impl From<crate::Err> for ApiError {
    fn from(e: crate::Err) -> Self {
        ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string())
    }
}

type ApiResult<T> = Result<T, ApiError>;

pub fn router(state: Shared) -> Router {
    let protected = Router::new()
        .route("/api/host-capabilities", get(host_capabilities))
        .route("/api/tracks", get(tracks))
        .route("/api/tracks/{id}", get(track))
        .route("/api/tracks/{id}/stream", get(stream))
        .route("/api/scan", post(scan))
        .route("/api/auth/devices", get(devices))
        .route("/api/auth/devices/{id}", delete(revoke_device))
        .route_layer(axum::middleware::from_fn_with_state(state.clone(), require_token));

    Router::new()
        .route("/api/health", get(health))
        .route("/api/auth/pair", post(pair))
        .route("/setup", get(setup_page))
        .merge(protected)
        .with_state(state)
}

/// Bearer-токен устройства. Публичны только health, пейринг и страница мастера.
async fn require_token(State(st): State<Shared>, req: Request, next: Next) -> Response {
    let token = req
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .map(str::to_string);

    let ok = match token {
        Some(t) => {
            let db = st.db.lock().unwrap();
            auth::verify(&db, &t).is_some()
        }
        None => false,
    };
    if ok {
        next.run(req).await
    } else {
        ApiError(StatusCode::UNAUTHORIZED, "нужен токен устройства".into()).into_response()
    }
}

#[derive(Serialize)]
struct Health {
    status: &'static str,
    version: &'static str,
    tracks: i64,
    /// false - сервер поднят без TLS, пейринг небезопасен (только локальная отладка).
    tls: bool,
}

async fn health(State(st): State<Shared>) -> ApiResult<Json<Health>> {
    let tracks = st
        .db
        .lock()
        .unwrap()
        .query_row("SELECT COUNT(*) FROM tracks", [], |r| r.get(0))?;
    Ok(Json(Health {
        status: "ok",
        version: env!("CARGO_PKG_VERSION"),
        tracks,
        tls: st.fingerprint.is_some(),
    }))
}

async fn host_capabilities() -> Json<host::HostCapabilities> {
    Json(host::measure())
}

#[derive(Deserialize)]
struct Page {
    limit: Option<i64>,
    offset: Option<i64>,
}

#[derive(Serialize)]
struct Track {
    id: i64,
    title: String,
    artist: Option<String>,
    album: Option<String>,
    album_artist: Option<String>,
    track_no: Option<i64>,
    year: Option<i64>,
    duration_ms: i64,
    size_bytes: i64,
    format: Option<String>,
}

const TRACK_COLS: &str =
    "id, title, artist, album, album_artist, track_no, year, duration_ms, size_bytes, format";

fn row_to_track(r: &rusqlite::Row<'_>) -> rusqlite::Result<Track> {
    Ok(Track {
        id: r.get(0)?,
        title: r.get(1)?,
        artist: r.get(2)?,
        album: r.get(3)?,
        album_artist: r.get(4)?,
        track_no: r.get(5)?,
        year: r.get(6)?,
        duration_ms: r.get(7)?,
        size_bytes: r.get(8)?,
        format: r.get(9)?,
    })
}

async fn tracks(State(st): State<Shared>, Query(p): Query<Page>) -> ApiResult<Json<Vec<Track>>> {
    // Потолок страницы жёсткий: без него один запрос вытянет всю библиотеку в RAM.
    let limit = p.limit.unwrap_or(200).clamp(1, 1000);
    let offset = p.offset.unwrap_or(0).max(0);
    let db = st.db.lock().unwrap();
    let mut stmt = db.prepare(&format!(
        "SELECT {TRACK_COLS} FROM tracks ORDER BY artist, album, track_no, title LIMIT ?1 OFFSET ?2"
    ))?;
    let rows = stmt
        .query_map([limit, offset], row_to_track)?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(Json(rows))
}

async fn track(State(st): State<Shared>, Path(id): Path<i64>) -> ApiResult<Json<Track>> {
    let db = st.db.lock().unwrap();
    db.query_row(&format!("SELECT {TRACK_COLS} FROM tracks WHERE id=?1"), [id], row_to_track)
        .map(Json)
        .map_err(|_| ApiError(StatusCode::NOT_FOUND, "нет такого трека".into()))
}

/// Passthrough-отдача: файл байт-в-байт, никакого транскодинга.
///
/// Range/206/If-Range реализует ServeFile из tower-http - переписывать разбор
/// заголовка Range руками смысла нет, готовая реализация уже покрывает крайние случаи.
async fn stream(State(st): State<Shared>, Path(id): Path<i64>, req: Request) -> Response {
    let path: Option<String> = st
        .db
        .lock()
        .unwrap()
        .query_row("SELECT path FROM tracks WHERE id=?1", [id], |r| r.get(0))
        .ok();
    let Some(path) = path else {
        return ApiError(StatusCode::NOT_FOUND, "нет такого трека".into()).into_response();
    };
    match ServeFile::new(path).oneshot(req).await {
        Ok(resp) => resp.map(axum::body::Body::new),
        Err(e) => ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()).into_response(),
    }
}

async fn scan(State(st): State<Shared>) -> ApiResult<Json<scanner::ScanReport>> {
    // Сканирование блокирующее (walkdir + разбор тегов) - уводим с async-потоков.
    let rep = tokio::task::spawn_blocking(move || {
        let mut db = st.db.lock().unwrap();
        scanner::scan(&mut db, &st.cfg.music_dirs)
    })
    .await
    .map_err(|e| ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?
    .map_err(|e| ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(rep))
}

#[derive(Deserialize)]
struct PairReq {
    code: String,
    #[serde(default)]
    device_name: String,
}

#[derive(Serialize)]
struct PairResp {
    token: String,
    device_name: String,
}

async fn pair(
    State(st): State<Shared>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Json(req): Json<PairReq>,
) -> Response {
    if !st.rate.allow(peer.ip()) {
        return ApiError(StatusCode::TOO_MANY_REQUESTS, "слишком много попыток".into())
            .into_response();
    }
    let mut db = st.db.lock().unwrap();
    match auth::pair(&mut db, req.code.trim(), &req.device_name) {
        Ok(token) => Json(PairResp { token, device_name: req.device_name }).into_response(),
        Err(_) => {
            ApiError(StatusCode::UNAUTHORIZED, "код неверен или уже использован".into())
                .into_response()
        }
    }
}

#[derive(Serialize)]
struct Device {
    id: i64,
    name: String,
    created_at: i64,
    last_seen_at: Option<i64>,
}

async fn devices(State(st): State<Shared>) -> ApiResult<Json<Vec<Device>>> {
    let db = st.db.lock().unwrap();
    let mut stmt =
        db.prepare("SELECT id, name, created_at, last_seen_at FROM devices ORDER BY created_at")?;
    let rows = stmt
        .query_map([], |r| {
            Ok(Device {
                id: r.get(0)?,
                name: r.get(1)?,
                created_at: r.get(2)?,
                last_seen_at: r.get(3)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(Json(rows))
}

async fn revoke_device(State(st): State<Shared>, Path(id): Path<i64>) -> ApiResult<StatusCode> {
    let n = st.db.lock().unwrap().execute("DELETE FROM devices WHERE id=?1", [id])?;
    Ok(if n == 1 { StatusCode::NO_CONTENT } else { StatusCode::NOT_FOUND })
}

#[derive(Deserialize)]
struct SetupQuery {
    /// Токен уже сопряжённого устройства - нужен, как только первое устройство появилось.
    token: Option<String>,
}

/// Минимальная страница мастера настройки: код сопряжения и QR к нему.
///
/// Пока не сопряжено ни одного устройства - открыта (первый запуск, закрывать нечем).
/// После первого сопряжения новые коды выдаются только по токену уже доверенного
/// устройства: иначе любой в той же Wi-Fi сети бессрочно печатает себе коды доступа.
/// ponytail: токен передаётся query-параметром, потому что это ссылка, открываемая
/// в браузере, а не XHR. Полноценный вход владельца по паролю - вместе с веб-панелью.
async fn setup_page(
    State(st): State<Shared>,
    Query(q): Query<SetupQuery>,
    req: Request,
) -> ApiResult<Html<String>> {
    let code = {
        let db = st.db.lock().unwrap();
        let paired: i64 = db.query_row("SELECT COUNT(*) FROM devices", [], |r| r.get(0))?;
        if paired > 0 {
            let ok = q.token.as_deref().is_some_and(|t| auth::verify(&db, t).is_some());
            if !ok {
                return Err(ApiError(
                    StatusCode::UNAUTHORIZED,
                    "устройства уже сопряжены: новый код доступен только по ссылке \
                     /setup?token=<токен доверенного устройства>"
                        .into(),
                ));
            }
        }
        auth::create_code(&db)?
    };
    let host = req
        .headers()
        .get(header::HOST)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("localhost")
        .split(':')
        .next()
        .unwrap_or("localhost")
        .to_string();

    let (fp_part, warning) = match &st.fingerprint {
        Some(fp) => (format!("&fp=sha256:{fp}"), String::new()),
        None => (
            String::new(),
            "<p class=warn>Сервер поднят без TLS: сопряжение небезопасно, \
             только для локальной разработки.</p>"
                .to_string(),
        ),
    };
    let uri = format!(
        "nami://pair?v=1&host={host}&port={}{fp_part}&code={code}",
        st.cfg.port
    );
    let qr = qrcode::QrCode::new(uri.as_bytes())
        .map_err(|e| ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?
        .render::<qrcode::render::svg::Color>()
        .min_dimensions(280, 280)
        .build();

    Ok(Html(format!(
        "<!doctype html><meta charset=utf-8><title>NAMI - сопряжение</title>\
         <style>body{{font:16px system-ui;max-width:520px;margin:40px auto;text-align:center}}\
         code{{font-size:28px;letter-spacing:4px}}.warn{{color:#b00}}</style>\
         <h1>Сопряжение устройства</h1>{qr}<p>Код: <code>{code}</code></p>\
         <p>Действует 10 минут, одно устройство.</p>{warning}"
    )))
}

/// Кол-во треков в библиотеке - используется в логе старта.
pub fn track_count(conn: &Connection) -> i64 {
    conn.query_row("SELECT COUNT(*) FROM tracks", [], |r| r.get(0)).unwrap_or(0)
}
