use std::path::Path;

use sha2::{Digest, Sha256};

/// Самоподписанный сертификат для локальной сети: его отпечаток уходит в QR, и клиент
/// закрепляет (pin) именно его - иначе в LAN нечем отличить свой сервер от подставного.
///
/// Внешний доступ (свой домен, Tailscale, Cloudflare Tunnel) на этом этапе НЕ реализован:
/// там будет нормальный CA-сертификат и никакого pinning'а. Это отдельный этап мастера настройки.
pub struct Tls {
    pub cert_pem: Vec<u8>,
    pub key_pem: Vec<u8>,
    /// sha256 DER-сертификата, hex в нижнем регистре.
    pub fingerprint: String,
}

/// Читает сертификат из data_dir или генерирует при первом запуске.
///
/// Отпечаток кладётся рядом отдельным файлом: считать его заново из PEM означало бы
/// тянуть base64/x509-парсер ради одной строки, которая и так известна в момент генерации.
pub fn load_or_create(data_dir: &Path, hostnames: Vec<String>) -> crate::Res<Tls> {
    let cert_path = data_dir.join("cert.pem");
    let key_path = data_dir.join("key.pem");
    let fp_path = data_dir.join("cert.fp");

    if let (Ok(cert_pem), Ok(key_pem), Ok(fp)) = (
        std::fs::read(&cert_path),
        std::fs::read(&key_path),
        std::fs::read_to_string(&fp_path),
    ) {
        return Ok(Tls { cert_pem, key_pem, fingerprint: fp.trim().to_string() });
    }

    let rcgen::CertifiedKey { cert, signing_key } =
        rcgen::generate_simple_self_signed(hostnames)?;
    let fingerprint = hex::encode(Sha256::digest(cert.der()));
    let cert_pem = cert.pem().into_bytes();
    let key_pem = signing_key.serialize_pem().into_bytes();

    std::fs::create_dir_all(data_dir)?;
    std::fs::write(&cert_path, &cert_pem)?;
    std::fs::write(&key_path, &key_pem)?;
    std::fs::write(&fp_path, &fingerprint)?;
    Ok(Tls { cert_pem, key_pem, fingerprint })
}
