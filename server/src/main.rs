//! Self-hosted сервер NAMI, этап 1: библиотека, passthrough-отдача аудио, сопряжение по QR.
//!
//! Бюджет, под который всё писано: 1 ядро, 512 МБ RAM, 50 000 треков. Отсюда rusqlite вместо
//! sqlx, потоковое сканирование без буферизации библиотеки в памяти и жёсткий потолок страницы
//! в /api/tracks.

mod api;
mod auth;
mod config;
mod db;
mod host;
mod scanner;
mod tls;

use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

pub type Err = Box<dyn std::error::Error + Send + Sync>;
pub type Res<T> = std::result::Result<T, Err>;

#[tokio::main]
async fn main() -> Res<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "nami_server=info,tower_http=warn".into()),
        )
        .init();

    let cfg = config::Config::load(std::path::Path::new("config.toml"))?;
    if cfg.music_dirs.is_empty() {
        tracing::warn!("music_dirs пуст - сканировать нечего, см. config.example.toml");
    }

    let caps = host::measure();
    tracing::info!(
        "хост: {} ядер, {} МБ RAM (доступно {}); базовый минимум: {}",
        caps.cpu_cores,
        caps.total_memory_mb,
        caps.available_memory_mb,
        if caps.meets_baseline { "да" } else { "НЕТ" }
    );

    let conn = db::open(&cfg.db_path)?;
    tracing::info!("библиотека: {} треков", api::track_count(&conn));

    let tls_cfg = if cfg.tls {
        let t = tls::load_or_create(&cfg.data_dir, vec!["localhost".into(), local_ip()])?;
        tracing::info!("TLS: отпечаток sha256:{}", t.fingerprint);
        Some(t)
    } else {
        tracing::warn!("TLS выключен - сопряжение небезопасно, только локальная отладка");
        None
    };

    let state = Arc::new(api::AppState {
        db: Mutex::new(conn),
        fingerprint: tls_cfg.as_ref().map(|t| t.fingerprint.clone()),
        rate: auth::RateLimiter::default(),
        cfg: cfg.clone(),
    });

    let addr = SocketAddr::from(([0, 0, 0, 0], cfg.port));
    let app = api::router(state).into_make_service_with_connect_info::<SocketAddr>();
    let scheme = if tls_cfg.is_some() { "https" } else { "http" };
    tracing::info!("слушаю {scheme}://{addr} - мастер настройки на {scheme}://<адрес>:{}/setup", cfg.port);

    match tls_cfg {
        Some(t) => {
            let rustls = axum_server::tls_rustls::RustlsConfig::from_pem(t.cert_pem, t.key_pem)
                .await?;
            axum_server::bind_rustls(addr, rustls).serve(app).await?;
        }
        None => axum_server::bind(addr).serve(app).await?,
    }
    Ok(())
}

/// Адрес в локальной сети - нужен только как SAN сертификата, чтобы клиент,
/// подключающийся по IP, не спотыкался о несовпадение имени.
///
/// ponytail: определяется трюком с UDP-сокетом (ничего никуда не отправляется, ядро просто
/// выбирает исходящий интерфейс). Полное перечисление интерфейсов - отдельный крейт ради
/// одной строки; если сервер стоит на машине с несколькими сетями, сертификат
/// перегенерируется удалением cert.pem.
fn local_ip() -> String {
    std::net::UdpSocket::bind("0.0.0.0:0")
        .and_then(|s| {
            s.connect("203.0.113.1:80")?;
            s.local_addr()
        })
        .map(|a| a.ip().to_string())
        .unwrap_or_else(|_| "127.0.0.1".into())
}
