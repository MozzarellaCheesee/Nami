//! Per-user Discord OAuth account linking and encrypted grants for the presence worker.
use crate::{api::Shared, auth, db::now, users::Ident};
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::{Html, IntoResponse, Response};
use axum::{
    extract::{Query, State},
    Extension, Json,
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use ring::aead::{self, Aad, LessSafeKey, Nonce, UnboundKey};
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::time::Duration;

const SCOPES: &str = "identify openid sdk.social_layer_presence";
const TTL: i64 = 600;

#[derive(Clone)]
pub struct DiscordConfig {
    pub(crate) client_id: String,
    client_secret: String,
    redirect_uri: String,
    key: [u8; 32],
}

impl std::fmt::Debug for DiscordConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DiscordConfig")
            .field("client_id", &self.client_id)
            .finish_non_exhaustive()
    }
}

impl DiscordConfig {
    pub fn from_env() -> crate::Res<Option<Self>> {
        let names = [
            "NAMI_DISCORD_CLIENT_ID",
            "NAMI_DISCORD_CLIENT_SECRET",
            "NAMI_DISCORD_REDIRECT_URI",
            "NAMI_DISCORD_TOKEN_KEY",
        ];
        let values: Vec<String> = names
            .iter()
            .map(|n| std::env::var(n).unwrap_or_default())
            .collect();
        if values.iter().all(|v| v.is_empty()) {
            return Ok(None);
        }
        if values.iter().any(|v| v.is_empty()) {
            return Err("Set all four NAMI_DISCORD_* OAuth variables".into());
        }
        let key: [u8; 32] = hex::decode(&values[3])
            .ok()
            .and_then(|v| v.try_into().ok())
            .ok_or("NAMI_DISCORD_TOKEN_KEY must be 64 hex characters")?;
        let redirect = url::Url::parse(&values[2]).map_err(|_| "Invalid Discord redirect URI")?;
        let local = matches!(
            redirect.host_str(),
            Some("localhost" | "127.0.0.1" | "[::1]")
        );
        if !(redirect.scheme() == "https" || (local && redirect.scheme() == "http"))
            || redirect.host_str().is_none()
            || !redirect.username().is_empty()
            || redirect.password().is_some()
            || redirect.query().is_some()
            || redirect.fragment().is_some()
            || redirect.path() != "/api/discord/callback"
        {
            return Err("Discord redirect must be an HTTPS URL ending in /api/discord/callback (HTTP only on localhost)".into());
        }
        if !values[0].bytes().all(|c| c.is_ascii_digit())
            || values[0].parse::<u64>().unwrap_or(0) == 0
        {
            return Err("Invalid NAMI_DISCORD_CLIENT_ID".into());
        }
        Ok(Some(Self {
            client_id: values[0].clone(),
            client_secret: values[1].clone(),
            redirect_uri: values[2].clone(),
            key,
        }))
    }
}

#[derive(Debug)]
pub struct Error(pub(crate) StatusCode, pub(crate) &'static str);
impl IntoResponse for Error {
    fn into_response(self) -> Response {
        response(self.0, Json(json!({"error": self.1})))
    }
}
impl From<rusqlite::Error> for Error {
    fn from(_: rusqlite::Error) -> Self {
        Self(StatusCode::INTERNAL_SERVER_ERROR, "Discord storage error")
    }
}
pub(crate) fn internal() -> Error {
    Error(
        StatusCode::INTERNAL_SERVER_ERROR,
        "Discord integration error",
    )
}
fn bad_flow() -> Error {
    Error(
        StatusCode::BAD_REQUEST,
        "Authorization expired or cancelled; start again",
    )
}
fn response(status: StatusCode, body: impl IntoResponse) -> Response {
    let mut result = (status, body).into_response();
    result
        .headers_mut()
        .insert(header::CACHE_CONTROL, "no-store".parse().unwrap());
    result
        .headers_mut()
        .insert("Referrer-Policy", "no-referrer".parse().unwrap());
    result.headers_mut().insert(
        "Content-Security-Policy",
        "default-src 'none'; frame-ancestors 'none'"
            .parse()
            .unwrap(),
    );
    result
}
fn config(st: &Shared) -> Result<DiscordConfig, Error> {
    st.cfg.discord.clone().ok_or(Error(
        StatusCode::SERVICE_UNAVAILABLE,
        "Discord OAuth is not configured on this server",
    ))
}

// Unlike media routes, these endpoints never accept a Nami credential in a query string.
pub(crate) fn owner(
    st: &Shared,
    headers: &HeaderMap,
    ident: &Ident,
) -> Result<(i64, String), Error> {
    let token = headers
        .get(header::AUTHORIZATION)
        .and_then(|h| h.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .ok_or(Error(
            StatusCode::UNAUTHORIZED,
            "Bearer authentication required",
        ))?;
    let db = st.db.lock().map_err(|_| internal())?;
    let verified = crate::api::identify(&db, token)
        .ok_or(Error(StatusCode::UNAUTHORIZED, "Invalid Nami session"))?;
    let uid = verified
        .user_id
        .filter(|id| Some(*id) == ident.user_id)
        .filter(|id| crate::users::get(&db, *id).is_some())
        .ok_or(Error(
            StatusCode::FORBIDDEN,
            "Sign in to a Nami user account first",
        ))?;
    Ok((uid, auth::hash_token(token)))
}

#[derive(Serialize, Deserialize)]
pub(crate) struct Tokens {
    pub(crate) access_token: String,
    refresh_token: String,
}

fn seal(cfg: &DiscordConfig, uid: i64, tokens: &Tokens) -> Result<String, Error> {
    let key =
        LessSafeKey::new(UnboundKey::new(&aead::AES_256_GCM, &cfg.key).map_err(|_| internal())?);
    let mut nonce = [0; 12];
    getrandom::fill(&mut nonce).map_err(|_| internal())?;
    let mut data = serde_json::to_vec(tokens).map_err(|_| internal())?;
    let aad = format!("nami-discord-v1:{uid}:{}", cfg.client_id);
    key.seal_in_place_append_tag(
        Nonce::assume_unique_for_key(nonce),
        Aad::from(aad.as_bytes()),
        &mut data,
    )
    .map_err(|_| internal())?;
    Ok(hex::encode([nonce.to_vec(), data].concat()))
}
pub(crate) fn unseal(cfg: &DiscordConfig, uid: i64, cipher: &str) -> Result<Tokens, Error> {
    let mut data = hex::decode(cipher).map_err(|_| internal())?;
    if data.len() < 28 {
        return Err(internal());
    }
    let nonce: [u8; 12] = data[..12].try_into().map_err(|_| internal())?;
    let key =
        LessSafeKey::new(UnboundKey::new(&aead::AES_256_GCM, &cfg.key).map_err(|_| internal())?);
    let aad = format!("nami-discord-v1:{uid}:{}", cfg.client_id);
    let plain = key
        .open_in_place(
            Nonce::assume_unique_for_key(nonce),
            Aad::from(aad.as_bytes()),
            &mut data[12..],
        )
        .map_err(|_| internal())?;
    serde_json::from_slice(plain).map_err(|_| internal())
}

fn start(
    db: &Connection,
    cfg: &DiscordConfig,
    uid: i64,
    credential: &str,
) -> Result<String, Error> {
    let state = auth::random_hex(32);
    let verifier = auth::random_hex(32);
    let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
    db.execute(
        "DELETE FROM discord_oauth_pending WHERE expires_at<=?1",
        [now()],
    )?;
    db.execute("INSERT INTO discord_oauth_pending(user_id,state_hash,credential_hash,verifier,expires_at)
        VALUES(?1,?2,?3,?4,?5) ON CONFLICT(user_id) DO UPDATE SET state_hash=excluded.state_hash,
        credential_hash=excluded.credential_hash,verifier=excluded.verifier,expires_at=excluded.expires_at,consumed=0",
        params![uid, auth::hash_token(&state), credential, verifier, now()+TTL])?;
    let mut url = url::Url::parse("https://discord.com/oauth2/authorize").unwrap();
    url.query_pairs_mut().extend_pairs([
        ("client_id", cfg.client_id.as_str()),
        ("redirect_uri", cfg.redirect_uri.as_str()),
        ("response_type", "code"),
        ("scope", SCOPES),
        ("state", &state),
        ("code_challenge", &challenge),
        ("code_challenge_method", "S256"),
        ("prompt", "consent"),
    ]);
    Ok(url.to_string())
}

pub(crate) fn credential_alive(db: &Connection, uid: i64, hash: &str) -> bool {
    db.query_row(
        "SELECT EXISTS(SELECT 1 FROM users WHERE id=?1) AND
        (EXISTS(SELECT 1 FROM devices WHERE user_id=?1 AND token_hash=?2) OR
         EXISTS(SELECT 1 FROM sessions WHERE user_id=?1 AND token_hash=?2 AND expires_at>?3))",
        params![uid, hash, now()],
        |r| r.get::<_, bool>(0),
    )
    .unwrap_or(false)
}

fn consume(db: &Connection, state: &str) -> Result<(i64, String, String), Error> {
    if state.len() != 64 || !state.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(bad_flow());
    }
    let hash = auth::hash_token(state);
    let flow = db.query_row("UPDATE discord_oauth_pending SET consumed=1
        WHERE state_hash=?1 AND consumed=0 AND expires_at>?2 RETURNING user_id,verifier,credential_hash",
        params![hash, now()], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?)))
        .optional()?.ok_or_else(bad_flow)?;
    if !credential_alive(db, flow.0, &flow.2) {
        return Err(bad_flow());
    }
    Ok(flow)
}

fn agent() -> ureq::Agent {
    ureq::Agent::config_builder()
        .timeout_global(Some(Duration::from_secs(15)))
        .max_redirects(0)
        .http_status_as_error(false)
        .build()
        .into()
}
fn token_request(cfg: &DiscordConfig, fields: &[(&str, &str)]) -> Result<Value, Error> {
    let mut form = vec![
        ("client_id", cfg.client_id.as_str()),
        ("client_secret", cfg.client_secret.as_str()),
    ];
    form.extend_from_slice(fields);
    let body = url::form_urlencoded::Serializer::new(String::new())
        .extend_pairs(form)
        .finish();
    let mut res = agent()
        .post("https://discord.com/api/v10/oauth2/token")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .send(body.as_bytes())
        .map_err(|_| Error(StatusCode::BAD_GATEWAY, "Discord is unavailable; try again"))?;
    let status = res.status();
    let text = res
        .body_mut()
        .with_config()
        .limit(65536)
        .read_to_string()
        .map_err(|_| internal())?;
    let value: Value = serde_json::from_str(&text).map_err(|_| internal())?;
    if !status.is_success() {
        return Err(if value["error"] == "invalid_grant" {
            Error(
                StatusCode::CONFLICT,
                "Discord authorization expired; reconnect",
            )
        } else {
            Error(StatusCode::BAD_GATEWAY, "Discord authorization failed")
        });
    }
    Ok(value)
}
fn parse_tokens(value: &Value) -> Result<(Tokens, i64, String), Error> {
    let access = value["access_token"]
        .as_str()
        .filter(|s| !s.is_empty())
        .ok_or_else(internal)?;
    let refresh = value["refresh_token"]
        .as_str()
        .filter(|s| !s.is_empty())
        .ok_or_else(internal)?;
    let ttl = value["expires_in"]
        .as_i64()
        .filter(|s| *s > 0 && *s <= 31_536_000)
        .ok_or_else(internal)?;
    let scopes = value["scope"].as_str().ok_or_else(internal)?;
    if !SCOPES
        .split_whitespace()
        .all(|s| scopes.split_whitespace().any(|v| v == s))
        || !value["token_type"]
            .as_str()
            .is_some_and(|t| t.eq_ignore_ascii_case("bearer"))
    {
        return Err(Error(
            StatusCode::BAD_GATEWAY,
            "Discord did not grant the required permissions",
        ));
    }
    Ok((
        Tokens {
            access_token: access.into(),
            refresh_token: refresh.into(),
        },
        now() + ttl,
        scopes.into(),
    ))
}

fn finish(
    db: &mut Connection,
    cfg: &DiscordConfig,
    uid: i64,
    state: &str,
    credential: &str,
    discord_id: &str,
    username: &str,
    tokens: &Tokens,
    expires: i64,
    scopes: &str,
) -> Result<(), Error> {
    let cipher = seal(cfg, uid, tokens)?;
    let tx = db.transaction()?;
    if !credential_alive(&tx, uid, credential) {
        return Err(bad_flow());
    }
    let removed = tx.execute(
        "DELETE FROM discord_oauth_pending WHERE user_id=?1 AND state_hash=?2
        AND consumed=1 AND expires_at>?3",
        params![uid, auth::hash_token(state), now()],
    )?;
    if removed != 1 {
        return Err(bad_flow());
    }
    let other: bool = tx.query_row(
        "SELECT EXISTS(SELECT 1 FROM discord_connections WHERE discord_user_id=?1 AND user_id<>?2)",
        params![discord_id, uid],
        |r| r.get(0),
    )?;
    if other {
        return Err(Error(
            StatusCode::CONFLICT,
            "This Discord account is already linked to another Nami user",
        ));
    }
    tx.execute("INSERT INTO discord_connections(user_id,discord_user_id,username,client_id,token_cipher,expires_at,scopes)
        VALUES(?1,?2,?3,?4,?5,?6,?7) ON CONFLICT(user_id) DO UPDATE SET discord_user_id=excluded.discord_user_id,
        username=excluded.username,client_id=excluded.client_id,token_cipher=excluded.token_cipher,
        expires_at=excluded.expires_at,scopes=excluded.scopes,refreshing_until=0",
        params![uid, discord_id, username, cfg.client_id, cipher, expires, scopes])?;
    tx.commit()?;
    Ok(())
}

fn status_value(db: &Connection, uid: i64, cfg: Option<&DiscordConfig>) -> Result<Value, Error> {
    let row = db.query_row("SELECT discord_user_id,username,expires_at,client_id FROM discord_connections WHERE user_id=?1", [uid],
        |r| Ok((r.get::<_, String>(0)?,r.get::<_, String>(1)?,r.get::<_, i64>(2)?,r.get::<_, String>(3)?))).optional()?;
    Ok(match row {
        Some((id, username, expiry, app)) => json!({"configured":cfg.is_some(),"linked":true,
            "discord_user_id":id,"username":username,"expires_at":expiry,
            "needs_refresh":expiry<=now()+60,"needs_reconnect":cfg.is_none_or(|c| c.client_id!=app),
            "presence_supported":false}),
        None => json!({"configured":cfg.is_some(),"linked":false,"presence_supported":false}),
    })
}

pub async fn status(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    headers: HeaderMap,
) -> Result<Response, Error> {
    let (uid, _) = owner(&st, &headers, &ident)?;
    let mut value = status_value(
        &*st.db.lock().map_err(|_| internal())?,
        uid,
        st.cfg.discord.as_ref(),
    )?;
    st.discord_presence.add_status(uid, &mut value);
    Ok(response(StatusCode::OK, Json(value)))
}
pub async fn authorize(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    headers: HeaderMap,
) -> Result<Response, Error> {
    let (uid, credential) = owner(&st, &headers, &ident)?;
    let cfg = config(&st)?;
    let url = start(
        &*st.db.lock().map_err(|_| internal())?,
        &cfg,
        uid,
        &credential,
    )?;
    Ok(response(
        StatusCode::OK,
        Json(json!({"authorize_url":url,"expires_in":TTL})),
    ))
}
#[derive(Deserialize)]
pub struct Callback {
    state: String,
    code: Option<String>,
    error: Option<String>,
}
pub async fn callback(
    State(st): State<Shared>,
    Query(q): Query<Callback>,
) -> Result<Response, Error> {
    let cfg = config(&st)?;
    let (uid, verifier, credential) = consume(&*st.db.lock().map_err(|_| internal())?, &q.state)?;
    if q.error.is_some() {
        return Err(Error(
            StatusCode::BAD_REQUEST,
            "Discord authorization was cancelled",
        ));
    }
    let code = q
        .code
        .filter(|s| !s.is_empty() && s.len() <= 4096)
        .ok_or_else(bad_flow)?;
    tokio::task::spawn_blocking(move || {
        let value = token_request(
            &cfg,
            &[
                ("grant_type", "authorization_code"),
                ("code", &code),
                ("redirect_uri", &cfg.redirect_uri),
                ("code_verifier", &verifier),
            ],
        )?;
        let (tokens, expiry, scopes) = parse_tokens(&value)?;
        let mut res = agent()
            .get("https://discord.com/api/v10/users/@me")
            .header("Authorization", format!("Bearer {}", tokens.access_token))
            .call()
            .map_err(|_| Error(StatusCode::BAD_GATEWAY, "Discord profile is unavailable"))?;
        if !res.status().is_success() {
            return Err(Error(
                StatusCode::BAD_GATEWAY,
                "Discord profile request failed",
            ));
        }
        let text = res
            .body_mut()
            .with_config()
            .limit(65536)
            .read_to_string()
            .map_err(|_| internal())?;
        let user: Value = serde_json::from_str(&text).map_err(|_| internal())?;
        let id = user["id"]
            .as_str()
            .filter(|s| s.parse::<u64>().is_ok())
            .ok_or_else(internal)?;
        let name = user["username"].as_str().ok_or_else(internal)?;
        finish(
            &mut *st.db.lock().map_err(|_| internal())?,
            &cfg,
            uid,
            &q.state,
            &credential,
            id,
            name,
            &tokens,
            expiry,
            &scopes,
        )
    })
    .await
    .map_err(|_| internal())??;
    Ok(response(StatusCode::OK, Html("<!doctype html><html lang=ru><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>Nami · Discord</title><h1>Discord подключён</h1><p>Аккаунт привязан к вашему пользователю Nami. Войдите в этот же аккаунт сервера на телефоне.</p><p><a href='/'>Вернуться в WebUI Nami</a></p><p>В приложении можно закрыть эту страницу и вернуться к плееру.</p></html>")))
}

fn remove(db: &mut Connection, uid: i64) -> Result<Option<String>, Error> {
    let tx = db.transaction()?;
    tx.execute("DELETE FROM discord_oauth_pending WHERE user_id=?1", [uid])?;
    let cipher = tx
        .query_row(
            "DELETE FROM discord_connections WHERE user_id=?1 RETURNING token_cipher",
            [uid],
            |r| r.get(0),
        )
        .optional()?;
    tx.commit()?;
    Ok(cipher)
}
pub async fn disconnect(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    headers: HeaderMap,
) -> Result<Response, Error> {
    let (uid, _) = owner(&st, &headers, &ident)?;
    let cipher = {
        let mut db = st.db.lock().map_err(|_| internal())?;
        let cipher = remove(&mut db, uid)?;
        st.discord_presence.clear(uid);
        cipher
    };
    let cfg = st.cfg.discord.clone();
    let revoked = tokio::task::spawn_blocking(move || -> bool {
        let Some(cipher) = cipher else {
            return true;
        };
        let Some(cfg) = cfg else {
            return false;
        };
        let Ok(tokens) = unseal(&cfg, uid, &cipher) else {
            return false;
        };
        let body = url::form_urlencoded::Serializer::new(String::new())
            .extend_pairs([
                ("client_id", cfg.client_id.as_str()),
                ("client_secret", cfg.client_secret.as_str()),
                ("token", tokens.refresh_token.as_str()),
                ("token_type_hint", "refresh_token"),
            ])
            .finish();
        agent()
            .post("https://discord.com/api/v10/oauth2/token/revoke")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .send(body.as_bytes())
            .is_ok_and(|r| r.status().is_success())
    })
    .await
    .map_err(|_| internal())?;
    Ok(response(
        StatusCode::OK,
        Json(json!({"linked":false,"revoked_remotely":revoked})),
    ))
}

pub async fn refresh(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    headers: HeaderMap,
) -> Result<Response, Error> {
    let (uid, _) = owner(&st, &headers, &ident)?;
    let cfg = config(&st)?;
    tokio::task::spawn_blocking(move || {
        refresh_tokens(&st, &cfg, uid)?;
        let mut value = status_value(&*st.db.lock().map_err(|_| internal())?, uid, Some(&cfg))?;
        st.discord_presence.add_status(uid, &mut value);
        Ok(response(StatusCode::OK, Json(value)))
    })
    .await
    .map_err(|_| internal())?
}

pub(crate) fn refresh_tokens(st: &Shared, cfg: &DiscordConfig, uid: i64) -> Result<(), Error> {
    let cipher: Option<String> = st
        .db
        .lock()
        .map_err(|_| internal())?
        .query_row(
            "UPDATE discord_connections SET refreshing_until=?2 WHERE user_id=?1 AND client_id=?3
             AND refreshing_until<?4 AND expires_at<=?5 RETURNING token_cipher",
            params![uid, now() + 60, cfg.client_id, now(), now() + 60],
            |r| r.get(0),
        )
        .optional()?;
    if let Some(cipher) = cipher {
        let outcome = (|| {
            let tokens = unseal(&cfg, uid, &cipher)?;
            let value = token_request(
                &cfg,
                &[
                    ("grant_type", "refresh_token"),
                    ("refresh_token", &tokens.refresh_token),
                ],
            )?;
            let (tokens, expiry, scopes) = parse_tokens(&value)?;
            let next = seal(&cfg, uid, &tokens)?;
            st.db.lock().map_err(|_| internal())?.execute("UPDATE discord_connections SET token_cipher=?3,
                    expires_at=?4,scopes=?5,refreshing_until=0 WHERE user_id=?1 AND token_cipher=?2",
                    params![uid,cipher,next,expiry,scopes])?;
            Ok::<_, Error>(())
        })();
        let db = st.db.lock().map_err(|_| internal())?;
        if outcome.as_ref().is_err_and(|e| e.0 == StatusCode::CONFLICT) {
            db.execute(
                "DELETE FROM discord_connections WHERE user_id=?1 AND token_cipher=?2",
                params![uid, cipher],
            )?;
        } else {
            db.execute("UPDATE discord_connections SET refreshing_until=0 WHERE user_id=?1 AND token_cipher=?2", params![uid,cipher])?;
        }
        outcome?;
    }
    Ok(())
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use axum::{
        body::{to_bytes, Body},
        http::Request,
    };
    use tower::ServiceExt;

    #[tokio::test]
    async fn refresh_only_considers_the_callers_connection() {
        let mut db = database();
        link(&mut db, 1, "100");
        link(&mut db, 2, "200");
        db.execute(
            "UPDATE discord_connections SET expires_at=0 WHERE user_id=2",
            [],
        )
        .unwrap();
        let before: String = db
            .query_row(
                "SELECT token_cipher FROM discord_connections WHERE user_id=2",
                [],
                |r| r.get(0),
            )
            .unwrap();
        let st = shared(db, Some(cfg()));
        // User 1 has a fresh token. User 2's expired token must not trigger any provider call.
        let (code, value) = request(
            st.clone(),
            "POST",
            "/api/me/discord/refresh?user_id=2",
            Some("token1"),
        )
        .await;
        assert_eq!(code, StatusCode::OK);
        assert_eq!(value["discord_user_id"], "100");
        let db = st.db.lock().unwrap();
        let after: (String, i64) = db
            .query_row(
                "SELECT token_cipher,refreshing_until FROM discord_connections WHERE user_id=2",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .unwrap();
        assert_eq!(after, (before, 0));
    }

    #[tokio::test]
    async fn cancelled_callback_is_single_use_and_keeps_existing_connection() {
        let mut db = database();
        link(&mut db, 1, "100");
        let (state, _) = flow(&db, 1);
        let st = shared(db, Some(cfg()));
        let uri = format!("/api/discord/callback?state={state}&error=access_denied");
        assert_eq!(
            request(st.clone(), "GET", &uri, None).await.0,
            StatusCode::BAD_REQUEST
        );
        assert_eq!(
            request(st.clone(), "GET", &uri, None).await.0,
            StatusCode::BAD_REQUEST
        );
        assert_eq!(
            request(st, "GET", "/api/me/discord", Some("token1"))
                .await
                .1["discord_user_id"],
            "100"
        );
    }

    #[test]
    fn device_reassignment_cannot_complete_the_previous_owners_flow() {
        let mut db = database();
        let (state, credential) = flow(&db, 1);
        consume(&db, &state).unwrap();
        db.execute(
            "UPDATE devices SET user_id=2 WHERE token_hash=?1",
            [&credential],
        )
        .unwrap();
        assert!(finish(
            &mut db,
            &cfg(),
            1,
            &state,
            &credential,
            "100",
            "user",
            &tokens(),
            now() + 600,
            SCOPES
        )
        .is_err());
        assert_eq!(status_value(&db, 1, Some(&cfg())).unwrap()["linked"], false);
    }

    pub(crate) fn cfg() -> DiscordConfig {
        DiscordConfig {
            client_id: "123456789012345678".into(),
            client_secret: "test-secret".into(),
            redirect_uri: "https://nami.example/api/discord/callback".into(),
            key: [7; 32],
        }
    }
    pub(crate) fn database() -> Connection {
        let db = Connection::open_in_memory().unwrap();
        db.execute_batch("PRAGMA foreign_keys=ON").unwrap();
        db.execute_batch(crate::db::SCHEMA).unwrap();
        for uid in 1..=2 {
            db.execute("INSERT INTO users(id,username,password_hash,role,created_at) VALUES(?1,?2,'hash','user',0)",
                params![uid,format!("user{uid}")]).unwrap();
            db.execute(
                "INSERT INTO devices(name,token_hash,created_at,user_id) VALUES('phone',?1,0,?2)",
                params![auth::hash_token(&format!("token{uid}")), uid],
            )
            .unwrap();
        }
        db
    }
    fn flow(db: &Connection, uid: i64) -> (String, String) {
        let credential = auth::hash_token(&format!("token{uid}"));
        let link = start(db, &cfg(), uid, &credential).unwrap();
        let state = url::Url::parse(&link)
            .unwrap()
            .query_pairs()
            .find(|(k, _)| k == "state")
            .unwrap()
            .1
            .into_owned();
        (state, credential)
    }
    fn tokens() -> Tokens {
        Tokens {
            access_token: "private-access".into(),
            refresh_token: "private-refresh".into(),
        }
    }
    pub(crate) fn link(db: &mut Connection, uid: i64, discord_id: &str) {
        let (state, cred) = flow(db, uid);
        consume(db, &state).unwrap();
        finish(
            db,
            &cfg(),
            uid,
            &state,
            &cred,
            discord_id,
            &format!("discord{uid}"),
            &tokens(),
            now() + 600,
            SCOPES,
        )
        .unwrap();
    }
    pub(crate) fn shared(db: Connection, config: Option<DiscordConfig>) -> Shared {
        std::sync::Arc::new(crate::api::AppState {
            db: std::sync::Mutex::new(db),
            cfg: crate::config::Config {
                discord: config,
                ..Default::default()
            },
            fingerprint: None,
            rate: Default::default(),
            qr_challenges: Default::default(),
            ffmpeg: false,
            fpcalc: false,
            events: tokio::sync::broadcast::channel(8).0,
            positions: tokio::sync::broadcast::channel(8).0,
            jams: Default::default(),
            metrics: crate::metrics::Metrics::new(),
            discord_presence: Default::default(),
        })
    }
    async fn request(
        st: Shared,
        method: &str,
        path: &str,
        token: Option<&str>,
    ) -> (StatusCode, Value) {
        let mut req = Request::builder().method(method).uri(path);
        if let Some(token) = token {
            req = req.header("Authorization", format!("Bearer {token}"));
        }
        let res = crate::api::router(st)
            .oneshot(req.body(Body::empty()).unwrap())
            .await
            .unwrap();
        let status = res.status();
        let body = to_bytes(res.into_body(), 65536).await.unwrap();
        (status, serde_json::from_slice(&body).unwrap_or(Value::Null))
    }

    #[test]
    fn encrypted_tokens_are_bound_to_user_and_application() {
        let encrypted = seal(&cfg(), 1, &tokens()).unwrap();
        assert!(!encrypted.contains("private"));
        assert_eq!(
            unseal(&cfg(), 1, &encrypted).unwrap().refresh_token,
            "private-refresh"
        );
        assert!(unseal(&cfg(), 2, &encrypted).is_err());
        let mut other = cfg();
        other.client_id = "other-app".into();
        assert!(unseal(&other, 1, &encrypted).is_err());
        let mut corrupt = hex::decode(encrypted).unwrap();
        corrupt[15] ^= 1;
        assert!(unseal(&cfg(), 1, &hex::encode(corrupt)).is_err());
        assert!(!format!("{:?}", cfg()).contains("test-secret"));
    }
    #[test]
    fn state_is_single_use_expiring_and_bound_to_live_credential() {
        let db = database();
        let (first, _) = flow(&db, 1);
        let (second, _) = flow(&db, 1);
        assert!(consume(&db, &first).is_err());
        assert_eq!(consume(&db, &second).unwrap().0, 1);
        assert!(consume(&db, &second).is_err());
        let (state, _) = flow(&db, 2);
        db.execute(
            "UPDATE discord_oauth_pending SET expires_at=0 WHERE user_id=2",
            [],
        )
        .unwrap();
        assert!(consume(&db, &state).is_err());
        let (state, _) = flow(&db, 2);
        db.execute("DELETE FROM devices WHERE user_id=2", [])
            .unwrap();
        assert!(consume(&db, &state).is_err());
    }
    #[test]
    fn disconnect_cancels_inflight_callback_without_touching_other_user() {
        let mut db = database();
        link(&mut db, 2, "200");
        let (state, credential) = flow(&db, 1);
        consume(&db, &state).unwrap();
        remove(&mut db, 1).unwrap();
        assert!(finish(
            &mut db,
            &cfg(),
            1,
            &state,
            &credential,
            "100",
            "user",
            &tokens(),
            now() + 10,
            SCOPES
        )
        .is_err());
        assert_eq!(status_value(&db, 1, Some(&cfg())).unwrap()["linked"], false);
        assert_eq!(
            status_value(&db, 2, Some(&cfg())).unwrap()["discord_user_id"],
            "200"
        );
    }
    #[test]
    fn same_discord_account_cannot_be_stolen_by_second_user() {
        let mut db = database();
        link(&mut db, 1, "100");
        let (state, credential) = flow(&db, 2);
        consume(&db, &state).unwrap();
        assert_eq!(
            finish(
                &mut db,
                &cfg(),
                2,
                &state,
                &credential,
                "100",
                "other",
                &tokens(),
                now() + 10,
                SCOPES
            )
            .unwrap_err()
            .0,
            StatusCode::CONFLICT
        );
        assert_eq!(
            status_value(&db, 1, Some(&cfg())).unwrap()["username"],
            "discord1"
        );
    }
    #[test]
    fn removing_user_cascades_oauth_credentials_and_pending_flow() {
        let mut db = database();
        link(&mut db, 1, "100");
        flow(&db, 1);
        // Existing devices are not FK-bound in the legacy schema.
        db.execute("DELETE FROM users WHERE id=1", []).unwrap();
        assert_eq!(
            db.query_row("SELECT count(*) FROM discord_connections", [], |r| r
                .get::<_, i64>(0))
                .unwrap(),
            0
        );
        assert_eq!(
            db.query_row("SELECT count(*) FROM discord_oauth_pending", [], |r| r
                .get::<_, i64>(0))
                .unwrap(),
            0
        );
    }
    #[test]
    fn incomplete_grants_are_rejected() {
        let mut value = json!({"access_token":"access","refresh_token":"refresh","expires_in":600,"scope":SCOPES,"token_type":"Bearer"});
        assert!(parse_tokens(&value).is_ok());
        value["scope"] = json!("identify");
        assert!(parse_tokens(&value).is_err());
        value["scope"] = json!(SCOPES);
        value["expires_in"] = json!(-1);
        assert!(parse_tokens(&value).is_err());
    }
    #[tokio::test]
    async fn http_status_and_disconnect_are_scoped_to_authenticated_user() {
        let mut db = database();
        link(&mut db, 1, "100");
        link(&mut db, 2, "200");
        let st = shared(db, None); // No provider network calls when server OAuth is disabled.
        let (code, a) = request(
            st.clone(),
            "GET",
            "/api/me/discord?user_id=2",
            Some("token1"),
        )
        .await;
        assert_eq!(code, StatusCode::OK);
        assert_eq!(a["discord_user_id"], "100");
        assert!(a.get("token_cipher").is_none());
        assert!(a.get("access_token").is_none());
        assert!(a.get("refresh_token").is_none());
        assert_eq!(
            request(
                st.clone(),
                "DELETE",
                "/api/me/discord?user_id=1",
                Some("token2")
            )
            .await
            .0,
            StatusCode::OK
        );
        assert_eq!(
            request(st.clone(), "GET", "/api/me/discord", Some("token1"))
                .await
                .1["linked"],
            true
        );
        assert_eq!(
            request(st, "GET", "/api/me/discord", Some("token2"))
                .await
                .1["linked"],
            false
        );
    }
    #[tokio::test]
    async fn anonymous_and_query_credentials_cannot_manage_discord() {
        let db = database();
        db.execute(
            "INSERT INTO devices(name,token_hash,created_at) VALUES('guest',?1,0)",
            [auth::hash_token("anonymous")],
        )
        .unwrap();
        let st = shared(db, Some(cfg()));
        for method in ["GET", "DELETE"] {
            assert_eq!(
                request(st.clone(), method, "/api/me/discord", None).await.0,
                StatusCode::UNAUTHORIZED
            );
            assert_eq!(
                request(st.clone(), method, "/api/me/discord?token=token1", None)
                    .await
                    .0,
                StatusCode::UNAUTHORIZED
            );
            assert_eq!(
                request(st.clone(), method, "/api/me/discord", Some("anonymous"))
                    .await
                    .0,
                StatusCode::FORBIDDEN
            );
        }
        assert_eq!(
            request(
                st.clone(),
                "POST",
                "/api/me/discord/authorize",
                Some("anonymous")
            )
            .await
            .0,
            StatusCode::FORBIDDEN
        );
        let (code, body) = request(st, "POST", "/api/me/discord/authorize", Some("token2")).await;
        assert_eq!(code, StatusCode::OK);
        assert!(body["authorize_url"]
            .as_str()
            .unwrap()
            .contains("code_challenge_method=S256"));
    }
}
