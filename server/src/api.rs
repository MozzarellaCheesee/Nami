use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

use axum::extract::{ConnectInfo, Extension, Path, Query, Request, State};
use axum::http::{header, StatusCode};
use axum::middleware::Next;
use axum::response::{Html, IntoResponse, Response};
use axum::routing::{delete, get, post};
use axum::{Json, Router};
use rusqlite::Connection;
use serde::{Deserialize, Serialize};
use tower::ServiceExt;
use tower_http::services::ServeFile;

use crate::auth::{self, QrChallenges, RateLimiter};
use crate::config::Config;
use crate::share;
use crate::users::{self, Ident};
use crate::{host, scanner, sync, transcode};

pub struct AppState {
    /// ponytail: одно соединение под мьютексом. Запросы к SQLite здесь короткие
    /// (индексный SELECT), отдача файла лок не держит. Пул (r2d2) стоит заводить,
    /// когда профиль покажет ожидание на этом мьютексе, а не заранее.
    pub db: Mutex<Connection>,
    pub cfg: Config,
    /// None, когда сервер поднят без TLS - тогда пейринг помечается небезопасным.
    pub fingerprint: Option<String>,
    pub rate: RateLimiter,
    /// QR-challenge для авторизации Android-клиента.
    pub qr_challenges: QrChallenges,
    /// Найден ли ffmpeg в PATH - проверяется один раз на старте.
    pub ffmpeg: bool,
    /// Найден ли fpcalc (chromaprint) в PATH - без него анализ не считает fingerprint.
    pub fpcalc: bool,
    /// События изменений состояния для подключённых по WebSocket.
    ///
    /// broadcast, а не список сокетов под мьютексом: рассылка "всем подписчикам"
    /// - это ровно то, что он делает, а отвалившийся клиент отписывается сам.
    pub events: tokio::sync::broadcast::Sender<String>,
    /// Отдельный канал для позиции воспроизведения: она обновляется на порядок чаще
    /// и в общем канале заглушила бы редкие события изменений (см. sync.rs).
    pub positions: tokio::sync::broadcast::Sender<String>,
    /// Живые джем-сессии (только в памяти, см. jam.rs).
    pub jams: crate::jam::Registry,
    /// Метрики Prometheus
    pub metrics: crate::metrics::Metrics,
}

impl AppState {
    /// Разослать событие изменения. Нет подписчиков - не ошибка.
    pub fn notify(&self, ev: serde_json::Value) {
        let _ = self.events.send(ev.to_string());
    }
    pub fn notify_position(&self, ev: serde_json::Value) {
        let _ = self.positions.send(ev.to_string());
    }
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
        .route("/api/tracks/{id}/stream/auto", get(stream_auto))
        .route("/api/tracks/match", post(tracks_match))
        .route("/api/tracks/{id}/artwork", get(artwork_handler))
        .route("/api/tracks/{id}/waveform", get(waveform_handler))
        .route("/api/tracks/{id}/radio", get(radio_handler))
        .route("/api/tracks/{id}/hls/master.m3u8", get(hls_master))
        .route("/api/tracks/{id}/hls/{profile}/index.m3u8", get(hls_index))
        .route("/api/tracks/{id}/hls/{profile}/{seg}", get(hls_segment))
        .route("/api/tracks/{id}/lyrics", get(lyrics))
        .route("/api/lyrics", get(lyrics_by_meta))
        .route(
            "/api/tracks/upload",
            // Тело пишется в файл потоком, поэтому потолок axum по умолчанию (2 МБ)
            // здесь не нужен: он бы отсекал любой нормальный альбомный FLAC.
            post(upload).layer(axum::extract::DefaultBodyLimit::disable()),
        )
        .route("/api/transcode/profiles", get(transcode_profiles))
        .route("/api/scan", post(scan))
        .route("/api/library/health", get(library_health))
        .route("/api/library/enrich", post(library_enrich))
        .route("/api/library/analyze", post(library_analyze))
        .route("/api/sync", get(sync_pull).post(sync_push))
        .route("/api/position", get(position_get).post(position_post))
        .route("/api/auth/devices", get(devices))
        .route("/api/auth/devices/{id}", delete(revoke_device))
        .route("/api/auth/logout", post(logout))
        .route("/api/me", get(me).patch(patch_me))
        .route("/api/me/subsonic-password", axum::routing::put(put_subsonic_password))
        .route("/api/me/password", axum::routing::put(put_my_password))
        .route("/api/me/listenbrainz-token", axum::routing::put(put_listenbrainz_token))
        .route("/api/scrobble", post(scrobble_handler))
        .route("/api/users", get(list_users).post(create_user))
        .route("/api/users/{id}", delete(delete_user))
        .route("/api/users/{id}/password", axum::routing::put(reset_user_password))
        .route("/api/users/{id}/folders", get(get_folders).put(put_folders))
        .route("/api/invites", post(create_invite))
        .route("/api/library-mode", get(get_library_mode).put(put_library_mode))
        .route("/api/now-playing", get(now_playing))
        .route("/api/jam/history", get(jam_history))
        .route("/api/share", post(create_share))
        .route("/api/share/{token}", delete(revoke_share))
        .route_layer(axum::middleware::from_fn_with_state(state.clone(), require_token));

    Router::new()
        .route("/api/health", get(health))
        .route("/metrics", get(metrics_handler))
        .route("/api/auth/pair", post(pair))
        .route("/api/auth/qr/start", post(qr_start))
        .route("/api/auth/qr/confirm", post(qr_confirm))
        .route("/api/auth/register", post(register))
        .route("/api/auth/login", post(login))
        .route("/api/invites/{token}/accept", post(accept_invite))
        .route("/setup", get(setup_page))
        // Гостевая авторизация в Jam без аккаунта
        .route("/api/jam/guest-auth", post(jam_guest_auth))
        // Гостевые ссылки: без логина и без приложения, проверка - токен в пути.
        .route("/share/{token}", get(share_page))
        .route("/share/{token}/stream/{id}", get(share_stream))
        // Токен проверяется внутри: у WebSocket-рукопожатия нет заголовка Authorization.
        .route("/api/ws", get(ws))
        // OpenSubsonic живёт со своей аутентификацией (u/t/s в query), поэтому мимо
        // общей прослойки Bearer-токена.
        .merge(crate::subsonic::router())
        .merge(protected)
        .with_state(state)
}

/// Достаёт Bearer-токен из заголовка.
fn bearer(req: &Request) -> Option<&str> {
    req.headers()
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
}

/// Токен из query-параметра `?token=` - для `<audio>`/`<img>` в веб-клиенте, которым
/// заголовок Authorization задать нечем (как уже сделано для WebSocket). Токены
/// hex/alphanumeric, percent-декодирование не нужно.
fn query_token(req: &Request) -> Option<String> {
    req.uri()
        .query()?
        .split('&')
        .find_map(|kv| kv.strip_prefix("token="))
        .map(|s| s.to_string())
}

/// Опознаёт токен: сначала как токен устройства, потом как сессию человека.
///
/// Один заголовок на два вида токенов намеренно: клиенту всё равно, чем он вошёл,
/// а ручкам ниже нужен единый `Ident`.
pub fn identify(conn: &Connection, token: &str) -> Option<Ident> {
    if let Some(device_id) = auth::verify(conn, token) {
        let user_id = conn
            .query_row("SELECT user_id FROM devices WHERE id=?1", [device_id], |r| r.get(0))
            .ok()
            .flatten();
        return Some(Ident { user_id, device_id: Some(device_id) });
    }
    users::verify_session(conn, token).map(|user_id| Ident { user_id: Some(user_id), device_id: None })
}

/// Bearer-токен устройства или сессии. Публичны только health, пейринг, вход и мастер.
async fn require_token(State(st): State<Shared>, req: Request, next: Next) -> Response {
    let ident = match bearer(&req).map(str::to_string).or_else(|| query_token(&req)) {
        Some(t) => identify(&st.db.lock().unwrap(), &t),
        None => None,
    };
    if let Some(ident) = ident {
        // Ручкам ниже нужен id устройства (позиция воспроизведения хранится по нему)
        // и id пользователя (состояние и видимость библиотеки - его).
        let mut req = req;
        if let Some(d) = ident.device_id {
            req.extensions_mut().insert(d);
        }
        req.extensions_mut().insert(ident);
        next.run(req).await
    } else {
        ApiError(StatusCode::UNAUTHORIZED, "нужен токен устройства или сессии".into())
            .into_response()
    }
}

/// Требует id устройства - для ручек, которым он обязателен (позиция воспроизведения).
fn need_device(ident: &Ident) -> ApiResult<i64> {
    ident.device_id.ok_or_else(|| {
        ApiError(StatusCode::BAD_REQUEST, "нужен токен устройства, а не сессии".into())
    })
}

/// Требует прав владельца. В одиночном режиме (пользователей нет) - разрешено:
/// иначе первое же устройство не смогло бы ничего настроить.
fn need_owner(conn: &Connection, ident: &Ident) -> ApiResult<()> {
    let ok = match ident.user_id {
        Some(id) => users::is_owner(conn, id),
        None => users::count(conn) == 0,
    };
    if ok {
        Ok(())
    } else {
        Err(ApiError(StatusCode::FORBIDDEN, "только для владельца сервера".into()))
    }
}

#[derive(Serialize)]
struct Health {
    status: &'static str,
    version: &'static str,
    tracks: i64,
    /// false - сервер поднят без TLS, пейринг небезопасен (только локальная отладка).
    tls: bool,
    /// Найден ли ffmpeg: false - профили транскодинга отвечают 503.
    ffmpeg: bool,
    /// Найден ли fpcalc: false - анализ считает громкость, но не chromaprint.
    fpcalc: bool,
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
        ffmpeg: st.ffmpeg,
        fpcalc: st.fpcalc,
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
    /// ReplayGain track gain, дБ. NULL - трек ещё не анализировали.
    replaygain_track_gain: Option<f64>,
    replaygain_track_peak: Option<f64>,
    /// Интегральная громкость EBU R128, LUFS.
    r128_loudness: Option<f64>,
    /// Оценка темпа, ударов в минуту. NULL - трек ещё не анализировали.
    bpm: Option<f64>,
    /// Тональность, например `A Minor`.
    musical_key: Option<String>,
}

const TRACK_COLS: &str = "id, title, artist, album, album_artist, track_no, year, duration_ms, \
     size_bytes, format, rg_track_gain, rg_track_peak, r128_loudness, bpm, musical_key";

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
        replaygain_track_gain: r.get(10)?,
        replaygain_track_peak: r.get(11)?,
        r128_loudness: r.get(12)?,
        bpm: r.get(13)?,
        musical_key: r.get(14)?,
    })
}

async fn tracks(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Query(p): Query<Page>,
) -> ApiResult<Json<Vec<Track>>> {
    // Потолок страницы жёсткий: без него один запрос вытянет всю библиотеку в RAM.
    let limit = p.limit.unwrap_or(200).clamp(1, 1000);
    let offset = p.offset.unwrap_or(0).max(0);
    let db = st.db.lock().unwrap();
    let (clause, mut params) = users::visibility(&db, &ident);
    params.push(rusqlite::types::Value::Integer(limit));
    params.push(rusqlite::types::Value::Integer(offset));
    let mut stmt = db.prepare(&format!(
        "SELECT {TRACK_COLS} FROM tracks WHERE 1=1{clause}
         ORDER BY artist, album, track_no, title LIMIT ? OFFSET ?"
    ))?;
    let rows = stmt
        .query_map(rusqlite::params_from_iter(params), row_to_track)?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(Json(rows))
}

/// Один трек со всеми деталями. Отдельно от списочного `Track`: сюда добавлен
/// chromaprint-fingerprint - в списке на тысячу треков он был бы лишними килобайтами
/// на строку, а на экране информации о треке нужен.
#[derive(Serialize)]
struct TrackDetail {
    #[serde(flatten)]
    track: Track,
    fingerprint: Option<String>,
    /// Когда трек прогнали через анализатор. NULL - ещё нет.
    analyzed_at: Option<i64>,
}

async fn track(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Path(id): Path<i64>,
) -> ApiResult<Json<TrackDetail>> {
    let db = st.db.lock().unwrap();
    let (clause, mut params) = if ident.user_id.is_none() && ident.device_id.is_some() {
        (String::new(), Vec::new())
    } else {
        users::visibility(&db, &ident)
    };
    params.insert(0, rusqlite::types::Value::Integer(id));
    db.query_row(
        &format!("SELECT {TRACK_COLS}, fingerprint, analyzed_at FROM tracks WHERE id=?{clause}"),
        rusqlite::params_from_iter(params),
        |r| {
            Ok(TrackDetail {
                track: row_to_track(r)?,
                fingerprint: r.get(15)?,
                analyzed_at: r.get(16)?,
            })
        },
    )
    .map(Json)
    .map_err(|_| ApiError(StatusCode::NOT_FOUND, "нет такого трека".into()))
}

#[derive(Deserialize)]
struct StreamQuery {
    /// Имя профиля транскодинга. Без него - passthrough, байт-в-байт.
    profile: Option<String>,
}

/// Отдача аудио: по умолчанию passthrough (файл байт-в-байт), с `?profile=` - транскод.
///
/// Range/206/If-Range реализует ServeFile из tower-http - переписывать разбор
/// заголовка Range руками смысла нет, готовая реализация уже покрывает крайние случаи.
/// Транскод отдаётся тем же ServeFile из кеша, поэтому перемотка работает и там.
async fn stream(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Path(id): Path<i64>,
    Query(q): Query<StreamQuery>,
    req: Request,
) -> Response {
    if !users::can_see_track(&st.db.lock().unwrap(), &ident, id) {
        return ApiError(StatusCode::NOT_FOUND, "нет такого трека".into()).into_response();
    }
    serve_track(st, id, q.profile.as_deref(), req).await
}

#[derive(Deserialize)]
struct StreamAutoQuery {
    /// Явное указание типа сети: wifi или cellular. Без него - определение по User-Agent.
    network: Option<String>,
}

/// Умный стриминг: Wi-Fi - оригинал, cellular - транскод в Opus 128kbps.
///
/// Определение типа сети: query `?network=wifi|cellular` или анализ User-Agent
/// (Android/iOS без "wifi" в строке агента считаются cellular). Fallback - Wi-Fi.
async fn stream_auto(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Path(id): Path<i64>,
    Query(q): Query<StreamAutoQuery>,
    req: Request,
) -> Response {
    if !users::can_see_track(&st.db.lock().unwrap(), &ident, id) {
        return ApiError(StatusCode::NOT_FOUND, "нет такого трека".into()).into_response();
    }

    let is_cellular = match q.network.as_deref() {
        Some("cellular") => true,
        Some("wifi") => false,
        _ => {
            // Определяем по User-Agent: Android/iOS без "wifi" считаем cellular
            req.headers()
                .get(header::USER_AGENT)
                .and_then(|v| v.to_str().ok())
                .map(|ua| {
                    let lower = ua.to_lowercase();
                    (lower.contains("android") || lower.contains("iphone") || lower.contains("ipad"))
                        && !lower.contains("wifi")
                })
                .unwrap_or(false)
        }
    };

    let profile = if is_cellular {
        if !st.ffmpeg {
            return ApiError(
                StatusCode::SERVICE_UNAVAILABLE,
                "транскодинг недоступен (ffmpeg не найден в PATH)".into(),
            )
            .into_response();
        }
        Some("mobile")
    } else {
        None
    };

    serve_track(st, id, profile, req).await
}

/// Обложка трека: встроенная или файл рядом (см. artwork.rs). Отдаётся как есть,
/// с годовым immutable-кешем - на смену обложки клиент дёргает URL с новым `?v=`.
async fn artwork_handler(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Path(id): Path<i64>,
) -> Response {
    serve_artwork(st, id, &ident).await
}

/// Общая отдача обложки: и для `/api/tracks/{id}/artwork`, и для Subsonic `getCoverArt`.
pub async fn serve_artwork(st: Shared, id: i64, ident: &Ident) -> Response {
    let path: Option<String> = {
        let db = st.db.lock().unwrap();
        if !users::can_see_track(&db, ident, id) {
            return ApiError(StatusCode::NOT_FOUND, "нет такого трека".into()).into_response();
        }
        db.query_row("SELECT path FROM tracks WHERE id=?1", [id], |r| r.get(0)).ok()
    };
    let Some((bytes, mime)) = path.and_then(|p| crate::artwork::load(&p)) else {
        return ApiError(StatusCode::NOT_FOUND, "у трека нет обложки".into()).into_response();
    };
    (
        [
            (header::CONTENT_TYPE, mime),
            (header::CACHE_CONTROL, "public, max-age=31536000, immutable".into()),
        ],
        bytes,
    )
        .into_response()
}

#[derive(Deserialize)]
struct MatchItem {
    title: String,
    artist: Option<String>,
    duration_ms: Option<i64>,
}

#[derive(Deserialize)]
struct MatchBody {
    tracks: Vec<MatchItem>,
}

/// Сопоставление треков клиента с библиотекой сервера по (исполнитель, название,
/// длительность ±2 с) - та же эвристика, что при дедупликации загрузок. Ответ -
/// массив `matches` той же длины и порядка, элемент = id трека сервера или null.
/// Нужно клиенту, чтобы стримить/брать анализ с сервера для локально известного трека.
async fn tracks_match(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Json(b): Json<MatchBody>,
) -> ApiResult<Json<serde_json::Value>> {
    if b.tracks.len() > 1000 {
        return Err(ApiError(StatusCode::BAD_REQUEST, "не больше 1000 треков за запрос".into()));
    }
    let db = st.db.lock().unwrap();
    let (clause, vis) = users::visibility(&db, &ident);
    let sql = format!(
        "SELECT id FROM tracks WHERE 1=1{clause}
           AND lower(title)=lower(?) AND lower(COALESCE(artist,''))=lower(COALESCE(?,''))
           AND abs(duration_ms - ?) <= {} LIMIT 1",
        scanner::DURATION_TOLERANCE_MS
    );
    let mut stmt = db.prepare(&sql)?;
    let mut matches: Vec<Option<i64>> = Vec::with_capacity(b.tracks.len());
    for t in &b.tracks {
        let mut params = vis.clone();
        params.push(rusqlite::types::Value::Text(t.title.clone()));
        params.push(match &t.artist {
            Some(a) => rusqlite::types::Value::Text(a.clone()),
            None => rusqlite::types::Value::Null,
        });
        params.push(rusqlite::types::Value::Integer(t.duration_ms.unwrap_or(0)));
        let id = stmt.query_row(rusqlite::params_from_iter(params), |r| r.get::<_, i64>(0)).ok();
        matches.push(id);
    }
    Ok(Json(serde_json::json!({ "matches": matches })))
}

/// Радио от трека: похожие по исполнителю, темпу и тональности. Порт `RadioBuilder.kt` -
/// эвристика по локальной библиотеке, не рекомендатель. Взвешенный рулеточный выбор,
/// поэтому два радио от одного трека идут не в одинаковом порядке. Seed первым.
async fn radio_handler(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Path(id): Path<i64>,
    Query(p): Query<Page>,
) -> ApiResult<Json<Vec<Track>>> {
    let want = p.limit.unwrap_or(40).clamp(2, 100) as usize;
    let db = st.db.lock().unwrap();
    let (clause, vis) = users::visibility(&db, &ident);

    let mut seed_params = vec![rusqlite::types::Value::Integer(id)];
    seed_params.extend(vis.iter().cloned());
    let seed = db
        .query_row(
            &format!("SELECT {TRACK_COLS} FROM tracks WHERE id=?{clause}"),
            rusqlite::params_from_iter(seed_params),
            row_to_track,
        )
        .map_err(|_| ApiError(StatusCode::NOT_FOUND, "нет такого трека".into()))?;

    let mut pool_params = vec![rusqlite::types::Value::Integer(id)];
    pool_params.extend(vis.iter().cloned());
    let mut stmt = db.prepare(&format!(
        "SELECT {TRACK_COLS} FROM tracks WHERE id<>?{clause} LIMIT 3000"
    ))?;
    let mut scored: Vec<(Track, i64)> = stmt
        .query_map(rusqlite::params_from_iter(pool_params), row_to_track)?
        .collect::<rusqlite::Result<Vec<_>>>()?
        .into_iter()
        .map(|t| {
            let s = radio_score(&seed, &t);
            (t, s)
        })
        .collect();

    // Рулетка: вес = score+1 (трек с нулём тоже может выпасть, просто редко).
    let mut rng = Xorshift::seeded();
    let mut out = vec![seed];
    let target = want.min(scored.len() + 1);
    while out.len() < target && !scored.is_empty() {
        let total: i64 = scored.iter().map(|(_, s)| s + 1).sum();
        let mut pick = (rng.next_f64() * total as f64) as i64;
        let mut idx = 0;
        while idx < scored.len() - 1 && pick >= scored[idx].1 + 1 {
            pick -= scored[idx].1 + 1;
            idx += 1;
        }
        out.push(scored.remove(idx).0);
    }
    Ok(Json(out))
}

fn radio_score(seed: &Track, c: &Track) -> i64 {
    let mut s = 0;
    if let (Some(a), Some(b)) = (&seed.artist, &c.artist) {
        if a.eq_ignore_ascii_case(b) {
            s += 3;
        }
    }
    if let (Some(a), Some(b)) = (&seed.musical_key, &c.musical_key) {
        if a == b {
            s += 2;
        }
    }
    if let (Some(a), Some(b)) = (seed.bpm, c.bpm) {
        if (a - b).abs() <= 15.0 {
            s += 1;
        }
    }
    s
}

/// Крошечный xorshift64* - для рулетки radio крипто-стойкость не нужна, а отдельный
/// PRNG-крейт ради одного места незачем. Затравка - из системного getrandom.
struct Xorshift(u64);
impl Xorshift {
    fn seeded() -> Self {
        let mut b = [0u8; 8];
        getrandom::fill(&mut b).ok();
        Xorshift(u64::from_le_bytes(b) | 1)
    }
    fn next_f64(&mut self) -> f64 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.0 = x;
        (x >> 11) as f64 / (1u64 << 53) as f64
    }
}

/// Форма волны трека: 120 значений RMS-громкости 0..1, посчитанных анализатором.
/// Пусто (404), пока трек не прошёл `POST /api/library/analyze`.
async fn waveform_handler(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Path(id): Path<i64>,
) -> Response {
    let db = st.db.lock().unwrap();
    if !users::can_see_track(&db, &ident, id) {
        return ApiError(StatusCode::NOT_FOUND, "нет такого трека".into()).into_response();
    }
    let raw: Option<String> =
        db.query_row("SELECT waveform FROM tracks WHERE id=?1", [id], |r| r.get(0)).ok().flatten();
    match raw {
        Some(json) => (
            [(header::CONTENT_TYPE, "application/json"), (header::CACHE_CONTROL, "public, max-age=86400")],
            json,
        )
            .into_response(),
        None => ApiError(StatusCode::NOT_FOUND, "форма волны ещё не посчитана".into()).into_response(),
    }
}

// ---------------------------------------------------------------- HLS

/// Путь, размер и mtime трека - если он видим запрашивающему.
fn track_src(st: &Shared, ident: &Ident, id: i64) -> Option<(std::path::PathBuf, i64, i64)> {
    let db = st.db.lock().unwrap();
    if !users::can_see_track(&db, ident, id) {
        return None;
    }
    db.query_row("SELECT path, size_bytes, mtime FROM tracks WHERE id=?1", [id], |r| {
        Ok((r.get::<_, String>(0)?, r.get(1)?, r.get(2)?))
    })
    .ok()
    .map(|(p, s, m)| (std::path::PathBuf::from(p), s, m))
}

/// Готовит каталог HLS-варианта в кеше (нарезает при промахе).
async fn hls_ready(
    st: &Shared,
    ident: &Ident,
    id: i64,
    profile: &str,
) -> Result<std::path::PathBuf, Response> {
    if !crate::hls::is_variant(profile) {
        return Err(ApiError(StatusCode::NOT_FOUND, "нет такого HLS-варианта".into()).into_response());
    }
    if !st.ffmpeg {
        return Err(ApiError(
            StatusCode::SERVICE_UNAVAILABLE,
            "ffmpeg не найден в PATH - HLS недоступен".into(),
        )
        .into_response());
    }
    let Some((src, size, mtime)) = track_src(st, ident, id) else {
        return Err(ApiError(StatusCode::NOT_FOUND, "нет такого трека".into()).into_response());
    };
    let bitrate = crate::hls::VARIANTS.iter().find(|(n, _, _)| *n == profile).map(|(_, b, _)| *b).unwrap();
    let cache_dir = st.cfg.cache_dir();
    let profile = profile.to_string();
    tokio::task::spawn_blocking(move || {
        crate::hls::ensure(&cache_dir, &src, id, size, mtime, &profile, bitrate)
    })
    .await
    .map_err(|e| ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()).into_response())?
    .map_err(|e| ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()).into_response())
}

async fn hls_master(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Path(id): Path<i64>,
) -> Response {
    if track_src(&st, &ident, id).is_none() {
        return ApiError(StatusCode::NOT_FOUND, "нет такого трека".into()).into_response();
    }
    (
        [(header::CONTENT_TYPE, "application/vnd.apple.mpegurl")],
        crate::hls::master_playlist(),
    )
        .into_response()
}

async fn hls_index(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Path((id, profile)): Path<(i64, String)>,
) -> Response {
    let dir = match hls_ready(&st, &ident, id, &profile).await {
        Ok(d) => d,
        Err(r) => return r,
    };
    match std::fs::read_to_string(dir.join("index.m3u8")) {
        Ok(body) => (
            [(header::CONTENT_TYPE, "application/vnd.apple.mpegurl")],
            body,
        )
            .into_response(),
        Err(e) => ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()).into_response(),
    }
}

async fn hls_segment(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Path((id, profile, seg)): Path<(i64, String, String)>,
    req: Request,
) -> Response {
    if !crate::hls::valid_segment(&seg) {
        return ApiError(StatusCode::NOT_FOUND, "нет такого сегмента".into()).into_response();
    }
    let dir = match hls_ready(&st, &ident, id, &profile).await {
        Ok(d) => d,
        Err(r) => return r,
    };
    match ServeFile::new(dir.join(&seg)).oneshot(req).await {
        Ok(resp) => {
            let mut resp = resp.map(axum::body::Body::new);
            resp.headers_mut()
                .insert(header::CONTENT_TYPE, header::HeaderValue::from_static("video/mp2t"));
            resp
        }
        Err(e) => ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()).into_response(),
    }
}

/// Отдача файла трека (passthrough или транскод). Проверку прав делает вызывающий:
/// у гостевой ссылки она своя (токен ссылки), у обычного клиента - видимость библиотеки.
pub async fn serve_track(
    st: Shared,
    id: i64,
    profile: Option<&str>,
    req: Request,
) -> Response {
    let row: Option<(String, i64, i64)> = st
        .db
        .lock()
        .unwrap()
        .query_row("SELECT path, size_bytes, mtime FROM tracks WHERE id=?1", [id], |r| {
            Ok((r.get(0)?, r.get(1)?, r.get(2)?))
        })
        .ok();
    let Some((path, size, mtime)) = row else {
        return ApiError(StatusCode::NOT_FOUND, "нет такого трека".into()).into_response();
    };

    let (file, mime) = match profile {
        None | Some("") | Some("original") => (std::path::PathBuf::from(path), None),
        Some(name) => {
            let Some(p) = transcode::profile(name) else {
                return ApiError(
                    StatusCode::BAD_REQUEST,
                    format!("нет профиля {name} - см. GET /api/transcode/profiles"),
                )
                .into_response();
            };
            if !st.ffmpeg {
                return ApiError(
                    StatusCode::SERVICE_UNAVAILABLE,
                    "ffmpeg не найден в PATH - транскодинг недоступен".into(),
                )
                .into_response();
            }
            let cache_dir = st.cfg.cache_dir();
            let limit = st.cfg.transcode_cache_mb;
            let src = std::path::PathBuf::from(path);
            // ffmpeg блокирует поток надолго - только не на async-исполнителе.
            let ready = tokio::task::spawn_blocking(move || {
                let r = transcode::ensure(&cache_dir, &src, id, size, mtime, p);
                if r.as_ref().is_ok_and(|r| r.encoded) {
                    let _ = transcode::evict(&cache_dir, limit);
                }
                r
            })
            .await;
            match ready {
                Ok(Ok(r)) => (r.path, Some(p.mime)),
                Ok(Err(e)) => {
                    return ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string())
                        .into_response()
                }
                Err(e) => {
                    return ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string())
                        .into_response()
                }
            }
        }
    };

    let resp = match ServeFile::new(file).oneshot(req).await {
        Ok(resp) => resp.map(axum::body::Body::new),
        Err(e) => {
            return ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()).into_response()
        }
    };
    match mime {
        // По расширению .opus/.m4a ServeFile угадывает не всегда - ставим тип сами.
        Some(m) => {
            let mut resp = resp;
            resp.headers_mut()
                .insert(header::CONTENT_TYPE, header::HeaderValue::from_static(m));
            resp
        }
        None => resp,
    }
}

async fn transcode_profiles(State(st): State<Shared>) -> Json<serde_json::Value> {
    Json(serde_json::json!({
        "available": st.ffmpeg,
        "profiles": transcode::PROFILES,
    }))
}

async fn scan(State(st): State<Shared>) -> ApiResult<Json<scanner::ScanReport>> {
    // Сканирование блокирующее (walkdir + разбор тегов) - уводим с async-потоков.
    let rep = tokio::task::spawn_blocking(move || -> crate::Res<scanner::ScanReport> {
        let mut db = st.db.lock().unwrap();
        // Библиотека по умолчанию (music_dirs), затем каждая заведённая отдельно.
        // Папка загрузок сканируется вместе со своей библиотекой - иначе всё,
        // что клиенты прислали, вычистилось бы как "файлы, которых больше нет".
        let mut dirs0 = st.cfg.music_dirs.clone();
        dirs0.push(st.cfg.upload_dir(0));
        let mut total = scanner::scan(&mut db, &dirs0, 0)?;
        for (id, mut dirs) in scanner::library_dirs(&db)? {
            if id == 0 || dirs.is_empty() {
                continue;
            }
            dirs.push(st.cfg.upload_dir(id));
            let r = scanner::scan(&mut db, &dirs, id)?;
            total.scanned += r.scanned;
            total.added += r.added;
            total.updated += r.updated;
            total.removed += r.removed;
            total.failed += r.failed;
        }
        Ok(total)
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
struct QrStartResp {
    /// QR-код в формате SVG
    qr_svg: String,
    /// Challenge для подтверждения
    challenge: String,
}

/// Генерирует QR-код для авторизации Android-клиента.
/// QR содержит: nami://auth?challenge=...&fp=sha256:...&hosts=ip1,ip2
async fn qr_start(
    State(st): State<Shared>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    req: Request,
) -> Response {
    if !st.rate.allow(peer.ip()) {
        return ApiError(StatusCode::TOO_MANY_REQUESTS, "слишком много попыток".into())
            .into_response();
    }

    // Определяем владельца: если устройства уже есть - нужен токен
    let ident = bearer(&req).and_then(|t| identify(&st.db.lock().unwrap(), t));
    let db = st.db.lock().unwrap();
    let paired: i64 = match db.query_row("SELECT COUNT(*) FROM devices", [], |r| r.get(0)) {
        Ok(n) => n,
        Err(_) => 0,
    };

    if paired > 0 && ident.is_none() {
        return ApiError(
            StatusCode::UNAUTHORIZED,
            "устройства уже сопряжены: новый QR доступен только по токену".into(),
        )
        .into_response();
    }

    let user_id = ident.and_then(|i| i.user_id);
    drop(db);

    // Создаём challenge
    let challenge = st.qr_challenges.create(user_id);

    // Собираем список хостов: локальные IP + публичный из заголовка Host
    let mut hosts = Vec::new();

    // Локальные IP
    for ip in auth::local_ips() {
        hosts.push(ip.to_string());
    }

    // Публичный адрес из Host заголовка (если отличается от локальных)
    if let Some(host_header) = req.headers().get(header::HOST) {
        if let Ok(host_str) = host_header.to_str() {
            let host = host_str.split(':').next().unwrap_or(host_str);
            if !host.is_empty() && !hosts.contains(&host.to_string()) {
                hosts.push(host.to_string());
            }
        }
    }

    let hosts_str = hosts.join(",");

    // Формируем URI для QR
    let (fp_part, port_part) = match &st.fingerprint {
        Some(fp) => (format!("&fp=sha256:{fp}"), format!("&port={}", st.cfg.port)),
        None => (String::new(), format!("&port={}", st.cfg.port)),
    };

    let ext_part = if st.cfg.external_url.trim().is_empty() {
        String::new()
    } else {
        format!("&ext={}", crate::lyrics::urlencode(st.cfg.external_url.trim()))
    };
    let uri = format!(
        "nami://auth?challenge={challenge}{fp_part}{port_part}&hosts={hosts_str}{ext_part}"
    );

    let qr_svg = match qrcode::QrCode::new(uri.as_bytes()) {
        Ok(qr) => qr
            .render::<qrcode::render::svg::Color>()
            .min_dimensions(280, 280)
            .build(),
        Err(e) => {
            return ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()).into_response()
        }
    };

    Json(QrStartResp { qr_svg, challenge }).into_response()
}

#[derive(Deserialize)]
struct QrConfirmReq {
    challenge: String,
    /// Подпись устройства (пока не проверяется - добавится с криптографией устройств)
    #[serde(default)]
    signature: String,
    #[serde(default)]
    device_name: String,
}

/// Подтверждает QR-авторизацию: Android отправляет challenge + подпись, получает токен.
async fn qr_confirm(
    State(st): State<Shared>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Json(req): Json<QrConfirmReq>,
) -> Response {
    if !st.rate.allow(peer.ip()) {
        return ApiError(StatusCode::TOO_MANY_REQUESTS, "слишком много попыток".into())
            .into_response();
    }

    // Проверяем и сжигаем challenge
    let user_id = match st.qr_challenges.consume(&req.challenge) {
        Some(uid) => uid,
        None => {
            return ApiError(
                StatusCode::UNAUTHORIZED,
                "challenge неверен или уже использован".into(),
            )
            .into_response()
        }
    };

    // Создаём устройство
    let token = auth::random_hex(32);
    let name = if req.device_name.trim().is_empty() {
        "Android"
    } else {
        req.device_name.trim()
    };

    let db = st.db.lock().unwrap();
    let result = db.execute(
        "INSERT INTO devices (name, token_hash, created_at, user_id) VALUES (?1, ?2, ?3, ?4)",
        rusqlite::params![name, auth::hash_token(&token), crate::db::now(), user_id],
    );

    match result {
        Ok(_) => Json(PairResp {
            token,
            device_name: name.to_string(),
        })
        .into_response(),
        Err(e) => ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()).into_response(),
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
        let ident = q.token.as_deref().and_then(|t| identify(&db, t));
        if paired > 0 && ident.is_none() {
            return Err(ApiError(
                StatusCode::UNAUTHORIZED,
                "устройства уже сопряжены: новый код доступен только по ссылке \
                 /setup?token=<токен доверенного устройства или сессии>"
                    .into(),
            ));
        }
        // Новое устройство достаётся тому, кто печатает код: свои плейлисты, своя
        // видимость библиотеки. В одиночном режиме владельца нет - и привязки тоже.
        auth::create_code(&db, ident.and_then(|i| i.user_id))?
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

// ---------------------------------------------------------------- синхронизация

#[derive(Deserialize)]
struct Since {
    since: Option<i64>,
}

async fn sync_pull(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Query(q): Query<Since>,
) -> ApiResult<Json<sync::Pull>> {
    let db = st.db.lock().unwrap();
    Ok(Json(sync::pull(&db, ident.state_key(), q.since.unwrap_or(0))?))
}

#[derive(Deserialize)]
struct PushBody {
    changes: Vec<sync::Change>,
}

async fn sync_push(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Json(body): Json<PushBody>,
) -> ApiResult<Json<sync::PushReport>> {
    let rep = {
        let mut db = st.db.lock().unwrap();
        sync::push(&mut db, ident.state_key(), &body.changes)?
    };
    if rep.applied > 0 {
        // Список затронутых сущностей, а не сами изменения: клиент всё равно пойдёт
        // за ними в GET /api/sync?since=, а гнать их вторым путём - два источника правды.
        let mut entities: Vec<&str> = body.changes.iter().map(|c| c.entity.as_str()).collect();
        entities.sort_unstable();
        entities.dedup();
        st.notify(serde_json::json!({
            "type": "changed",
            "user_id": ident.state_key(),
            "entities": entities,
            "at": rep.now,
        }));
    }
    Ok(Json(rep))
}

async fn position_get(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Query(q): Query<Since>,
) -> ApiResult<Json<Vec<sync::Position>>> {
    let db = st.db.lock().unwrap();
    Ok(Json(sync::positions(&db, ident.user_id, q.since.unwrap_or(0))?))
}

async fn position_post(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Json(p): Json<sync::Position>,
) -> ApiResult<Json<serde_json::Value>> {
    let device_id = need_device(&ident)?;
    let at = {
        let db = st.db.lock().unwrap();
        sync::set_position(&db, device_id, &p)?
    };
    st.notify_position(serde_json::json!({
        "type": "position",
        "user_id": ident.state_key(),
        "track_id": p.track_id,
        "position_ms": p.position_ms,
        "at": at,
    }));
    Ok(Json(serde_json::json!({ "updated_at": at })))
}

#[derive(Deserialize)]
struct WsQuery {
    /// Токен: у WebSocket-рукопожатия из браузера заголовок Authorization задать нечем.
    token: Option<String>,
    /// 1 - подписаться ещё и на поток позиции воспроизведения. По умолчанию нет:
    /// позиция обновляется каждые несколько секунд и забила бы редкие события изменений.
    position: Option<u8>,
    /// Код сессии джема для гостевого подключения без токена
    jam_code: Option<String>,
}

#[derive(Deserialize)]
struct JamGuestAuthReq {
    code: String,
}

#[derive(Serialize)]
struct JamGuestAuthResp {
    token: String,
    code: String,
}

async fn jam_guest_auth(
    State(st): State<Shared>,
    Json(body): Json<JamGuestAuthReq>,
) -> ApiResult<Json<JamGuestAuthResp>> {
    let code_upper = body.code.trim().to_uppercase();
    if !st.jams.contains(&code_upper) {
        return Err(ApiError(StatusCode::NOT_FOUND, "нет такой сессии джема".into()));
    }
    let raw_token = format!("jam_{}", auth::random_hex(24));
    let token_hash = auth::hash_token(&raw_token);
    let now = crate::db::now();
    {
        let db = st.db.lock().unwrap();
        db.execute(
            "INSERT INTO devices (name, token_hash, paired_at, last_seen_at) VALUES (?1, ?2, ?3, ?3)",
            rusqlite::params![format!("Jam Guest ({})", code_upper), token_hash, now],
        )?;
    }
    Ok(Json(JamGuestAuthResp {
        token: raw_token,
        code: code_upper,
    }))
}

/// WebSocket с событиями изменений. Не заменяет `GET /api/sync?since=`, а ускоряет его:
/// по событию клиент идёт за самими изменениями обычным HTTP. Поллинг остаётся
/// полноценным запасным путём, если сокет недоступен или разорван.
async fn ws(
    State(st): State<Shared>,
    Query(q): Query<WsQuery>,
    upgrade: axum::extract::ws::WebSocketUpgrade,
) -> Response {
    let ident = match q.token.as_deref() {
        Some(t) => identify(&st.db.lock().unwrap(), t),
        None => {
            if let Some(code) = q.jam_code.as_deref() {
                let code_upper = code.trim().to_uppercase();
                if st.jams.contains(&code_upper) {
                    Some(Ident { user_id: None, device_id: None })
                } else {
                    None
                }
            } else {
                None
            }
        }
    };
    let Some(ident) = ident else {
        return ApiError(StatusCode::UNAUTHORIZED, "нужен токен устройства (?token=) или код джема (?jam_code=)".into())
            .into_response();
    };
    let with_position = q.position == Some(1);
    upgrade.on_upgrade(move |socket| ws_loop(st, socket, ident, with_position))
}

/// Событие адресовано этому подключению? Состояние у каждого пользователя своё,
/// поэтому чужие "changed"/"position" до сокета доходить не должны.
fn addressed_to(text: &str, ident: &Ident) -> bool {
    serde_json::from_str::<serde_json::Value>(text)
        .ok()
        .and_then(|v| v.get("user_id").and_then(|u| u.as_i64()))
        .map(|u| u == ident.state_key())
        .unwrap_or(true)
}

async fn ws_loop(
    st: Shared,
    mut socket: axum::extract::ws::WebSocket,
    ident: Ident,
    with_position: bool,
) {
    use axum::extract::ws::Message;
    let mut changes = st.events.subscribe();
    let mut pos = st.positions.subscribe();
    let mut jam = crate::jam::Membership::default();
    loop {
        let msg = tokio::select! {
            r = changes.recv() => r,
            r = pos.recv(), if with_position => r,
            // События джема идут по тому же сокету - второй канал ради них не заводим.
            r = jam.recv() => r,
            // Клиент закрыл сокет или прислал что-то своё - читаем, чтобы заметить разрыв.
            incoming = socket.recv() => {
                match incoming {
                    None | Some(Err(_)) | Some(Ok(Message::Close(_))) => return,
                    Some(Ok(Message::Text(t))) => {
                        if let Some(reply) = crate::jam::handle(&st, &ident, &mut jam, &t) {
                            if socket.send(Message::Text(reply.into())).await.is_err() {
                                return;
                            }
                        }
                        continue;
                    }
                    _ => continue,
                }
            }
        };
        match msg {
            Ok(text) => {
                if !addressed_to(&text, &ident) {
                    continue;
                }
                if socket.send(Message::Text(text.into())).await.is_err() {
                    return;
                }
            }
            // Медленный клиент отстал от кольцевого буфера. Событий он не увидит,
            // но и не должен: догонит их обычным GET /api/sync?since=.
            Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
            Err(_) => return,
        }
    }
}

// ---------------------------------------------------------------- пользователи

impl From<users::UserError> for ApiError {
    fn from(e: users::UserError) -> Self {
        let code = match e {
            users::UserError::Db(_) => StatusCode::INTERNAL_SERVER_ERROR,
            users::UserError::BadCredentials => StatusCode::UNAUTHORIZED,
            users::UserError::Taken => StatusCode::CONFLICT,
            _ => StatusCode::BAD_REQUEST,
        };
        ApiError(code, e.to_string())
    }
}

#[derive(Deserialize)]
struct Credentials {
    username: String,
    password: String,
}

#[derive(Serialize)]
struct Session {
    user_id: i64,
    token: String,
    role: String,
}

/// Регистрация первого пользователя - владельца сервера. Работает ровно один раз:
/// дальше вход только по инвайту, иначе сервер, торчащий в интернет, заводит владельцев
/// всем желающим.
async fn register(
    State(st): State<Shared>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Json(c): Json<Credentials>,
) -> ApiResult<Json<Session>> {
    if !st.rate.allow(peer.ip()) {
        return Err(ApiError(StatusCode::TOO_MANY_REQUESTS, "слишком много попыток".into()));
    }
    let db = st.db.lock().unwrap();
    if users::count(&db) > 0 {
        return Err(ApiError(
            StatusCode::FORBIDDEN,
            "владелец уже есть - остальные заводятся по инвайту".into(),
        ));
    }
    let id = users::create(&db, &c.username, &c.password, "owner", 0)?;
    let token = users::start_session(&db, id)?;
    Ok(Json(Session { user_id: id, token, role: "owner".into() }))
}

async fn login(
    State(st): State<Shared>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Json(c): Json<Credentials>,
) -> ApiResult<Json<Session>> {
    if !st.rate.allow(peer.ip()) {
        return Err(ApiError(StatusCode::TOO_MANY_REQUESTS, "слишком много попыток".into()));
    }
    let db = st.db.lock().unwrap();
    let (id, token) = users::login(&db, &c.username, &c.password)?;
    let role = users::get(&db, id).map(|u| u.role).unwrap_or_default();
    Ok(Json(Session { user_id: id, token, role }))
}

async fn logout(State(st): State<Shared>, req: Request) -> StatusCode {
    if let Some(t) = bearer(&req) {
        let _ = users::logout(&st.db.lock().unwrap(), t);
    }
    StatusCode::NO_CONTENT
}

async fn accept_invite(
    State(st): State<Shared>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Path(token): Path<String>,
    Json(c): Json<Credentials>,
) -> ApiResult<Json<Session>> {
    if !st.rate.allow(peer.ip()) {
        return Err(ApiError(StatusCode::TOO_MANY_REQUESTS, "слишком много попыток".into()));
    }
    let mut db = st.db.lock().unwrap();
    let id = users::accept_invite(&mut db, &token, &c.username, &c.password)?;
    let session = users::start_session(&db, id)?;
    let role = users::get(&db, id).map(|u| u.role).unwrap_or_default();
    Ok(Json(Session { user_id: id, token: session, role }))
}

async fn me(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
) -> ApiResult<Json<serde_json::Value>> {
    let db = st.db.lock().unwrap();
    let user = ident.user_id.and_then(|id| users::get(&db, id));
    Ok(Json(serde_json::json!({
        "user": user,
        "device_id": ident.device_id,
        "library_mode": users::library_mode(&db),
    })))
}

#[derive(Deserialize)]
struct PatchMe {
    /// Видна ли моя активность на экране «Что слушают».
    now_playing_visible: Option<bool>,
}

async fn patch_me(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Json(p): Json<PatchMe>,
) -> ApiResult<StatusCode> {
    let id = ident
        .user_id
        .ok_or_else(|| ApiError(StatusCode::BAD_REQUEST, "вход не как пользователь".into()))?;
    if let Some(v) = p.now_playing_visible {
        st.db.lock().unwrap().execute(
            "UPDATE users SET now_playing_visible=?2 WHERE id=?1",
            rusqlite::params![id, v as i64],
        )?;
    }
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
struct SubsonicPassword {
    /// Пустая строка - отозвать доступ по Subsonic-протоколу.
    password: String,
}

/// Задаёт или снимает отдельный Subsonic-пароль (см. doc-комментарий subsonic.rs:
/// исторический протокол требует знать пароль, поэтому он отдельный от основного).
async fn put_subsonic_password(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Json(b): Json<SubsonicPassword>,
) -> ApiResult<StatusCode> {
    let id = ident
        .user_id
        .ok_or_else(|| ApiError(StatusCode::BAD_REQUEST, "вход не как пользователь".into()))?;
    let value = b.password.trim();
    if !value.is_empty() && value.chars().count() < 8 {
        return Err(ApiError(StatusCode::BAD_REQUEST, "пароль - от 8 символов".into()));
    }
    st.db.lock().unwrap().execute(
        "UPDATE users SET subsonic_password=?2 WHERE id=?1",
        rusqlite::params![id, if value.is_empty() { None } else { Some(value) }],
    )?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
struct ChangePassword {
    old: String,
    new: String,
}

/// Смена своего пароля. Нужен текущий пароль; после смены все сессии этого
/// пользователя гаснут (текущую придётся получить логином заново).
async fn put_my_password(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Json(b): Json<ChangePassword>,
) -> ApiResult<StatusCode> {
    let id = ident
        .user_id
        .ok_or_else(|| ApiError(StatusCode::BAD_REQUEST, "вход не как пользователь".into()))?;
    users::change_password(&st.db.lock().unwrap(), id, &b.old, &b.new)?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
struct ResetPassword {
    new: String,
}

/// Сброс пароля пользователю - только владельцем, без текущего пароля.
/// Сессии сбрасываемого пользователя гаснут.
async fn reset_user_password(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Path(id): Path<i64>,
    Json(b): Json<ResetPassword>,
) -> ApiResult<StatusCode> {
    let db = st.db.lock().unwrap();
    need_owner(&db, &ident)?;
    users::reset_password(&db, id, &b.new)?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
struct LbToken {
    /// Пустая строка - отключить скробблинг.
    token: String,
}

/// Задаёт или снимает пользовательский токен ListenBrainz для скробблинга.
async fn put_listenbrainz_token(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Json(b): Json<LbToken>,
) -> ApiResult<StatusCode> {
    let id = ident
        .user_id
        .ok_or_else(|| ApiError(StatusCode::BAD_REQUEST, "вход не как пользователь".into()))?;
    let t = b.token.trim();
    st.db.lock().unwrap().execute(
        "UPDATE users SET listenbrainz_token=?2 WHERE id=?1",
        rusqlite::params![id, if t.is_empty() { None } else { Some(t) }],
    )?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
struct ScrobbleBody {
    track_id: i64,
    /// Unix-секунды момента прослушивания. Без него - сейчас.
    played_at: Option<i64>,
}

/// Клиент сообщает «трек прослушан». Кладём в очередь; отправку в ListenBrainz
/// делает фоновый поток (scrobble.rs). Порог «прослушано» - решение клиента.
async fn scrobble_handler(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Json(b): Json<ScrobbleBody>,
) -> ApiResult<StatusCode> {
    let user_id = ident
        .user_id
        .ok_or_else(|| ApiError(StatusCode::BAD_REQUEST, "вход не как пользователь".into()))?;
    let db = st.db.lock().unwrap();
    if !users::can_see_track(&db, &ident, b.track_id) {
        return Err(ApiError(StatusCode::NOT_FOUND, "нет такого трека".into()));
    }
    let (artist, title, album): (Option<String>, String, Option<String>) = db.query_row(
        "SELECT artist, title, album FROM tracks WHERE id=?1",
        [b.track_id],
        |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
    )?;
    crate::scrobble::enqueue(
        &db,
        user_id,
        artist.as_deref().unwrap_or(""),
        &title,
        album.as_deref(),
        b.played_at.unwrap_or_else(crate::db::now),
    )?;
    Ok(StatusCode::NO_CONTENT)
}

async fn list_users(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
) -> ApiResult<Json<Vec<users::User>>> {
    let db = st.db.lock().unwrap();
    need_owner(&db, &ident)?;
    Ok(Json(users::list(&db)?))
}

#[derive(Deserialize)]
struct NewUser {
    username: String,
    password: String,
    #[serde(default = "default_role")]
    role: String,
    #[serde(default)]
    library_id: i64,
}

fn default_role() -> String {
    "user".into()
}

async fn create_user(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Json(u): Json<NewUser>,
) -> ApiResult<Json<serde_json::Value>> {
    let db = st.db.lock().unwrap();
    need_owner(&db, &ident)?;
    let role = if matches!(u.role.as_str(), "user" | "guest" | "owner") { u.role } else { default_role() };
    let id = users::create(&db, &u.username, &u.password, &role, u.library_id)?;
    Ok(Json(serde_json::json!({ "id": id })))
}

async fn delete_user(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Path(id): Path<i64>,
) -> ApiResult<StatusCode> {
    let db = st.db.lock().unwrap();
    need_owner(&db, &ident)?;
    if users::is_owner(&db, id) {
        return Err(ApiError(StatusCode::FORBIDDEN, "владельца удалить нельзя".into()));
    }
    // Состояние и устройства уходят вместе с человеком: оставлять их некому.
    db.execute("DELETE FROM state WHERE user_id=?1", [id])?;
    db.execute("DELETE FROM devices WHERE user_id=?1", [id])?;
    let n = db.execute("DELETE FROM users WHERE id=?1", [id])?;
    Ok(if n == 1 { StatusCode::NO_CONTENT } else { StatusCode::NOT_FOUND })
}

async fn get_folders(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Path(id): Path<i64>,
) -> ApiResult<Json<Vec<String>>> {
    let db = st.db.lock().unwrap();
    // Свои ограничения видно и без прав владельца - иначе клиент не знает, что показывать.
    if ident.user_id != Some(id) {
        need_owner(&db, &ident)?;
    }
    Ok(Json(users::folder_access(&db, id)))
}

async fn put_folders(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Path(id): Path<i64>,
    Json(folders): Json<Vec<String>>,
) -> ApiResult<StatusCode> {
    let db = st.db.lock().unwrap();
    need_owner(&db, &ident)?;
    db.execute("DELETE FROM user_folder_access WHERE user_id=?1", [id])?;
    for f in folders.iter().map(|f| f.trim()).filter(|f| !f.is_empty()) {
        db.execute(
            "INSERT OR IGNORE INTO user_folder_access (user_id, folder_path) VALUES (?1, ?2)",
            rusqlite::params![id, f],
        )?;
    }
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
struct NewInvite {
    #[serde(default = "default_role")]
    role: String,
    #[serde(default)]
    library_id: i64,
    /// Сколько секунд жить ссылке. По умолчанию неделя.
    ttl_secs: Option<i64>,
}

async fn create_invite(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Json(i): Json<NewInvite>,
) -> ApiResult<Json<users::Invite>> {
    let db = st.db.lock().unwrap();
    need_owner(&db, &ident)?;
    let by = ident.user_id.unwrap_or(0);
    Ok(Json(users::create_invite(&db, by, &i.role, i.library_id, i.ttl_secs)?))
}

async fn get_library_mode(State(st): State<Shared>) -> Json<serde_json::Value> {
    let db = st.db.lock().unwrap();
    Json(serde_json::json!({ "mode": users::library_mode(&db) }))
}

#[derive(Deserialize)]
struct ModeBody {
    mode: users::LibraryMode,
}

/// Смена режима библиотеки. ВНИМАНИЕ: чистит tracks - после смены нужен новый скан.
/// Переносить данные между режимами намеренно нечем (см. doc-комментарий users.rs).
async fn put_library_mode(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Json(b): Json<ModeBody>,
) -> ApiResult<Json<serde_json::Value>> {
    let db = st.db.lock().unwrap();
    need_owner(&db, &ident)?;
    users::set_library_mode(&db, b.mode)?;
    Ok(Json(serde_json::json!({
        "mode": b.mode,
        "note": "библиотека очищена, запустите POST /api/scan",
    })))
}

async fn now_playing(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
) -> ApiResult<Json<Vec<sync::NowPlaying>>> {
    let db = st.db.lock().unwrap();
    Ok(Json(sync::now_playing(&db, &ident)?))
}

/// Журнал прошедших джем-сессий своей библиотеки. Живые сессии в памяти, а тут -
/// история: код, время, сколько треков и участников.
async fn jam_history(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Query(p): Query<Page>,
) -> ApiResult<Json<Vec<crate::jam::JamRecord>>> {
    let library = crate::jam::library_key(&st, &ident);
    let limit = p.limit.unwrap_or(50).clamp(1, 200);
    let db = st.db.lock().unwrap();
    Ok(Json(crate::jam::Registry::history(&db, library, limit)?))
}

// ---------------------------------------------------------------- загрузка треков

#[derive(Deserialize)]
struct UploadQuery {
    /// Имя файла клиента - от него берётся только расширение и безопасная основа.
    filename: Option<String>,
}

#[derive(Serialize)]
struct Uploaded {
    track_id: i64,
    /// Почему трек не создан заново; null - создан.
    duplicate_of: Option<scanner::DuplicateOf>,
}

/// Оставляет от присланного имени только безопасную основу: ни разделителей пути,
/// ни `..`, ни управляющих символов - файл ложится строго внутрь папки загрузок.
fn safe_name(raw: &str) -> String {
    let base = raw.rsplit(['/', '\\']).next().unwrap_or(raw);
    let cleaned: String = base
        .chars()
        .map(|c| if c.is_control() || "<>:\"|?*".contains(c) { '_' } else { c })
        .collect();
    let cleaned = cleaned.trim_matches(['.', ' ']).to_string();
    if cleaned.is_empty() {
        "upload".into()
    } else {
        cleaned
    }
}

/// Приём файла с клиента: тело запроса - сам файл, метаданные берутся из тегов
/// тем же кодом, что и при сканировании.
///
/// Дедупликация: сначала по sha256 файла, затем по (исполнитель, название,
/// длительность с допуском). Уже имеющийся трек не создаёт вторую запись -
/// возвращается id существующего.
async fn upload(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Query(q): Query<UploadQuery>,
    body: axum::body::Body,
) -> ApiResult<Json<Uploaded>> {
    let library_id = {
        let db = st.db.lock().unwrap();
        match users::library_mode(&db) {
            users::LibraryMode::Separate => ident
                .user_id
                .and_then(|id| users::get(&db, id).map(|u| u.library_id))
                .unwrap_or(0),
            users::LibraryMode::Shared => 0,
        }
    };
    let dir = st.cfg.upload_dir(library_id);
    std::fs::create_dir_all(&dir).map_err(|e| ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let name = safe_name(q.filename.as_deref().unwrap_or("upload"));
    // Расширение сохраняем и у временного файла: lofty выбирает разборщик по нему,
    // а безымянный .part не опознаётся ни как FLAC, ни как что-либо ещё.
    let ext = std::path::Path::new(&name)
        .extension()
        .map(|e| format!(".{}", e.to_string_lossy()))
        .unwrap_or_default();

    // Пишем во временный файл: под настоящим именем он появится только когда
    // окажется, что это не дубль, - иначе папка загрузок копила бы мусор.
    let tmp = dir.join(format!("{}{ext}", users::random_token()));
    let written = write_body(&tmp, body).await;
    if let Err(e) = written {
        let _ = std::fs::remove_file(&tmp);
        return Err(ApiError(StatusCode::BAD_REQUEST, e.to_string()));
    }

    let result = tokio::task::spawn_blocking(move || -> crate::Res<Uploaded> {
        // Что бы дальше ни случилось, временный файл в папке загрузок не остаётся.
        let cleanup = |r: crate::Res<Uploaded>| {
            let _ = std::fs::remove_file(&tmp);
            r
        };
        let meta = match scanner::read_meta(&tmp) {
            Ok(m) => m,
            Err(e) => return cleanup(Err(e)),
        };
        let hash = match scanner::file_hash(&tmp) {
            Ok(h) => h,
            Err(e) => return cleanup(Err(e.into())),
        };
        let db = st.db.lock().unwrap();
        if let Some((id, why)) = scanner::find_duplicate(&db, &hash, &meta, library_id) {
            return cleanup(Ok(Uploaded { track_id: id, duplicate_of: Some(why) }));
        }
        // Раскладка по шаблону автосортировки (config.import_pattern). Пустой шаблон -
        // файл в корень папки загрузок, как было.
        let rel = scanner::sort_path(&st.cfg.import_pattern, &meta, &ext);
        let mut final_path = dir.join(&rel);
        if let Some(parent) = final_path.parent() {
            if let Err(e) = std::fs::create_dir_all(parent) {
                return cleanup(Err(e.into()));
            }
        }
        // Столкновение имён у разных треков - добавляем короткий хеш перед расширением.
        if final_path.exists() {
            let stem = rel.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
            let suf_ext = rel
                .extension()
                .map(|e| format!(".{}", e.to_string_lossy()))
                .unwrap_or_default();
            final_path.set_file_name(format!("{stem}_{}{suf_ext}", &hash[..8]));
        }
        if let Err(e) = std::fs::rename(&tmp, &final_path) {
            return cleanup(Err(e.into()));
        }
        let (id, dup) = scanner::add_file(&db, &final_path, library_id)?;
        Ok(Uploaded { track_id: id, duplicate_of: dup })
    })
    .await
    .map_err(|e| ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    match result {
        Ok(u) => Ok(Json(u)),
        // Не аудио или битые теги - это ошибка клиента, а не сервера.
        Err(e) => Err(ApiError(StatusCode::BAD_REQUEST, format!("файл не принят: {e}"))),
    }
}

/// Сливает тело запроса в файл кусками: память не зависит от размера файла.
async fn write_body(path: &std::path::Path, body: axum::body::Body) -> crate::Res<()> {
    use http_body_util::BodyExt;
    let mut file = std::fs::File::create(path)?;
    let mut body = body;
    let mut empty = true;
    while let Some(frame) = body.frame().await {
        if let Ok(data) = frame?.into_data() {
            if !data.is_empty() {
                empty = false;
                std::io::Write::write_all(&mut file, &data)?;
            }
        }
    }
    if empty {
        return Err("пустое тело запроса".into());
    }
    Ok(())
}

// ---------------------------------------------------------------- здоровье библиотеки

/// Отчёт о здоровье в пределах видимости запрашивающего. Файлы, не открывшиеся при
/// сканировании, попадают в отчёт целиком - их не к чему привязать по папкам, поэтому
/// их видит только владелец (в одиночном режиме - любое устройство).
async fn library_health(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
) -> ApiResult<Json<crate::library::Health>> {
    let db = st.db.lock().unwrap();
    let mut h = crate::library::health(&db, &ident)?;
    if need_owner(&db, &ident).is_err() {
        h.broken = crate::library::Sampled { count: 0, items: Vec::new() };
    }
    Ok(Json(h))
}

#[derive(Deserialize)]
struct EnrichQuery {
    /// Сколько треков обработать за один вызов.
    limit: Option<usize>,
}

/// Обогащение метаданных из MusicBrainz - порциями: их политика позволяет не больше
/// одного запроса в секунду, поэтому вызов с limit=20 честно длится около двадцати
/// секунд, а `remaining` в ответе говорит, сколько осталось на следующий раз.
async fn library_enrich(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Query(q): Query<EnrichQuery>,
) -> ApiResult<Json<crate::library::EnrichReport>> {
    need_owner(&st.db.lock().unwrap(), &ident)?;
    let limit = q.limit.unwrap_or(20).clamp(1, 100);
    let rep = tokio::task::spawn_blocking(move || {
        let db = st.db.lock().unwrap();
        crate::library::enrich(&db, limit)
    })
    .await
    .map_err(|e| ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))??;
    Ok(Json(rep))
}

#[derive(Deserialize)]
struct AnalyzeQuery {
    /// Сколько треков обработать за один вызов.
    limit: Option<usize>,
}

/// Анализ аудио (ReplayGain/R128 через ffmpeg, chromaprint через fpcalc) - порциями:
/// ffmpeg декодирует файл целиком, это секунды на трек. `remaining` в ответе говорит,
/// сколько треков осталось без анализа.
async fn library_analyze(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Query(q): Query<AnalyzeQuery>,
) -> ApiResult<Json<crate::analyzer::AnalyzeReport>> {
    need_owner(&st.db.lock().unwrap(), &ident)?;
    let limit = q.limit.unwrap_or(20).clamp(1, 200);
    let rep = tokio::task::spawn_blocking(move || {
        let db = st.db.lock().unwrap();
        crate::analyzer::analyze_batch(&db, limit, true)
    })
    .await
    .map_err(|e| ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))??;
    Ok(Json(rep))
}

// ---------------------------------------------------------------- лирика

#[derive(Deserialize)]
struct LyricsQuery {
    /// 1 - искать заново, даже если в кеше уже что-то лежит.
    refresh: Option<u8>,
    /// 1 - вернуть ещё и перевод (нужен ключ DeepL в конфигурации сервера).
    translate: Option<u8>,
    /// Язык перевода (код DeepL). По умолчанию - из конфигурации.
    lang: Option<String>,
}

/// Лирика трека: кеш в БД, при промахе - поиск в LRCLIB.
///
/// Сеть и разбор - в `spawn_blocking`: ureq синхронный, а держать на нём
/// async-исполнитель нельзя (сервер однопоточный по бюджету).
async fn lyrics(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Path(id): Path<i64>,
    Query(q): Query<LyricsQuery>,
) -> ApiResult<Json<crate::lyrics::Lyrics>> {
    let (title, artist, album, duration_ms) = {
        let db = st.db.lock().unwrap();
        if !users::can_see_track(&db, &ident, id) {
            return Err(ApiError(StatusCode::NOT_FOUND, "нет такого трека".into()));
        }
        db.query_row(
            "SELECT title, artist, album, duration_ms FROM tracks WHERE id=?1",
            [id],
            |r| Ok((r.get::<_, String>(0)?, r.get::<_, Option<String>>(1)?, r.get::<_, Option<String>>(2)?, r.get::<_, i64>(3)?)),
        )?
    };
    let refresh = q.refresh == Some(1);
    let translate = q.translate == Some(1);
    let lang = q.lang.unwrap_or_else(|| st.cfg.lyrics_target_lang.clone());

    let out = tokio::task::spawn_blocking(move || -> ApiResult<crate::lyrics::Lyrics> {
        use crate::lyrics;
        let cached = if refresh { None } else { lyrics::load(&st.db.lock().unwrap(), id) };
        let cached = match cached {
            Some(c) => c,
            None => {
                let found = lyrics::fetch_lrclib(&title, artist.as_deref(), album.as_deref(), duration_ms);
                let db = st.db.lock().unwrap();
                match found {
                    Some((raw, synced)) => lyrics::store(&db, id, &raw, synced, "lrclib")?,
                    // Промах тоже кешируется: иначе каждый показ трека без лирики -
                    // это два запроса в lrclib.
                    None => lyrics::store(&db, id, "", false, "none")?,
                }
                lyrics::load(&db, id).ok_or_else(|| {
                    ApiError(StatusCode::INTERNAL_SERVER_ERROR, "кеш лирики не записался".into())
                })?
            }
        };

        let mut cached = cached;
        if translate && cached.translation.is_none() && !cached.raw.is_empty() {
            if st.cfg.deepl_api_key.trim().is_empty() {
                return Err(ApiError(
                    StatusCode::SERVICE_UNAVAILABLE,
                    "перевод не настроен: задайте deepl_api_key в config.toml".into(),
                ));
            }
            let texts: Vec<String> =
                lyrics::parse_lrc(&cached.raw).into_iter().map(|l| l.text).collect();
            if let Some(tr) = lyrics::translate_deepl(&texts, st.cfg.deepl_api_key.trim(), &lang) {
                let json = serde_json::to_string(&tr).unwrap_or_default();
                lyrics::store_translation(&st.db.lock().unwrap(), id, &json)?;
                cached.translation = Some(json);
            }
        }
        Ok(lyrics::build(id, &cached))
    })
    .await
    .map_err(|e| ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))??;
    Ok(Json(out))
}

#[derive(Deserialize)]
struct LyricsMetaQuery {
    title: String,
    artist: Option<String>,
    album: Option<String>,
    duration_ms: Option<i64>,
    translate: Option<u8>,
    lang: Option<String>,
}

/// Лирика по метаданным - для клиента, чьи треки серверу не известны (нет track_id).
/// Поиск в LRCLIB + разбор + опционально перевод серверным ключом DeepL. Без кеша:
/// кеш лирики привязан к track_id, а его тут нет.
async fn lyrics_by_meta(
    State(st): State<Shared>,
    Extension(_ident): Extension<Ident>,
    Query(q): Query<LyricsMetaQuery>,
) -> ApiResult<Json<crate::lyrics::Lyrics>> {
    if q.title.trim().is_empty() {
        return Err(ApiError(StatusCode::BAD_REQUEST, "нужен параметр title".into()));
    }
    let translate = q.translate == Some(1);
    let lang = q.lang.unwrap_or_else(|| st.cfg.lyrics_target_lang.clone());
    let out = tokio::task::spawn_blocking(move || -> crate::lyrics::Lyrics {
        use crate::lyrics::{self, Lyrics};
        let now = crate::db::now();
        let Some((raw, synced)) = lyrics::fetch_lrclib(
            q.title.trim(),
            q.artist.as_deref(),
            q.album.as_deref(),
            q.duration_ms.unwrap_or(0),
        ) else {
            return Lyrics { track_id: 0, source: "none".into(), synced: false, lines: vec![], fetched_at: now };
        };
        let mut lines = lyrics::parse_lrc(&raw);
        if translate && !st.cfg.deepl_api_key.trim().is_empty() {
            let texts: Vec<String> = lines.iter().map(|l| l.text.clone()).collect();
            if let Some(tr) = lyrics::translate_deepl(&texts, st.cfg.deepl_api_key.trim(), &lang) {
                for (l, t) in lines.iter_mut().zip(tr) {
                    l.translation = Some(t);
                }
            }
        }
        let has_time = lines.iter().any(|l| l.time_ms.is_some());
        Lyrics { track_id: 0, source: "lrclib".into(), synced: synced && has_time, lines, fetched_at: now }
    })
    .await
    .map_err(|e| ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(out))
}

// ---------------------------------------------------------------- гостевые ссылки

async fn create_share(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    Json(req): Json<share::NewShare>,
) -> ApiResult<Json<share::Share>> {
    if req.track_ids.is_empty() {
        return Err(ApiError(StatusCode::BAD_REQUEST, "нечем делиться: track_ids пуст".into()));
    }
    let db = st.db.lock().unwrap();
    // Поделиться можно только тем, что видишь сам - иначе гостевая ссылка стала бы
    // обходом ограничения доступа к папкам.
    for id in &req.track_ids {
        if !users::can_see_track(&db, &ident, *id) {
            return Err(ApiError(StatusCode::FORBIDDEN, format!("трек {id} вам не виден")));
        }
    }
    Ok(Json(share::create(&db, ident.user_id, &req)?))
}

async fn revoke_share(
    State(st): State<Shared>,
    Extension(_ident): Extension<Ident>,
    Path(token): Path<String>,
) -> ApiResult<StatusCode> {
    let n = share::revoke(&st.db.lock().unwrap(), &token)?;
    Ok(if n == 1 { StatusCode::NO_CONTENT } else { StatusCode::NOT_FOUND })
}

/// Страница гостя. Срок и счётчик проверяются здесь и ещё раз на каждом потоке.
async fn share_page(State(st): State<Shared>, Path(token): Path<String>) -> Response {
    let db = st.db.lock().unwrap();
    match share::lookup(&db, &token) {
        Some(live) => Html(share::page(&db, &token, &live)).into_response(),
        None => (
            StatusCode::GONE,
            Html(
                "<!doctype html><meta charset=utf-8><title>Ссылка недоступна</title>\
                 <p style=\"font:16px system-ui;margin:40px\">Ссылка больше не действует."
                    .to_string(),
            ),
        )
            .into_response(),
    }
}

/// Поток по гостевой ссылке. Отдельная проверка токена ссылки вместо токена устройства.
async fn share_stream(
    State(st): State<Shared>,
    Path((token, id)): Path<(String, i64)>,
    req: Request,
) -> Response {
    // Перемотка - это Range с ненулевым началом; новым прослушиванием она не считается.
    let is_start = req
        .headers()
        .get(header::RANGE)
        .and_then(|v| v.to_str().ok())
        .map(|v| v == "bytes=0-")
        .unwrap_or(true);
    {
        let db = st.db.lock().unwrap();
        let Some(live) = share::lookup(&db, &token) else {
            return ApiError(StatusCode::GONE, "ссылка больше не действует".into()).into_response();
        };
        if !live.track_ids.contains(&id) {
            return ApiError(StatusCode::NOT_FOUND, "этого трека нет в ссылке".into())
                .into_response();
        }
        if is_start && !share::count_play(&db, &token) {
            return ApiError(StatusCode::GONE, "ссылка больше не действует".into()).into_response();
        }
    }
    // Гостю - всегда оригинал: профиль транскодинга он выбрать не может.
    serve_track(st, id, None, req).await
}

/// Кол-во треков в библиотеке - используется в логе старта.
pub fn track_count(conn: &Connection) -> i64 {
    conn.query_row("SELECT COUNT(*) FROM tracks", [], |r| r.get(0)).unwrap_or(0)
}

/// Prometheus метрики
async fn metrics_handler(State(st): State<Shared>) -> Response {
    (
        [(header::CONTENT_TYPE, "text/plain; version=0.0.4")],
        st.metrics.export(),
    )
        .into_response()
}
