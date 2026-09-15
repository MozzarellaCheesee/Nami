//! Phone metadata -> the OAuth owner's isolated Social SDK process.
use crate::{
    api::Shared,
    db::now,
    discord::{self, Error},
    users::Ident,
};
use axum::{
    extract::State,
    http::{HeaderMap, StatusCode},
    Extension, Json,
};
use rusqlite::{params, OptionalExtension};
use serde::Deserialize;
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    path::PathBuf,
    process::Stdio,
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Mutex,
    },
    time::{Duration, Instant},
};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
    process::{ChildStdin, Command},
    task::JoinHandle,
};

const TTL: Duration = Duration::from_secs(60);

#[derive(Default)]
pub struct Runtime {
    ready: AtomicBool,
    sequence: AtomicU64,
    entries: Mutex<HashMap<i64, Entry>>,
}
#[derive(Clone)]
struct Entry {
    credential: String,
    activity: Activity,
    version: u64,
    updated: Instant,
    retry: Instant,
    status: &'static str,
}
#[derive(Clone)]
struct Activity {
    title: String,
    description: String,
    start: i64,
    end: i64,
}
impl Activity {
    fn same(&self, other: &Self) -> bool {
        self.title == other.title
            && self.description == other.description
            && (self.start - other.start).abs() <= 2
            && (self.end - other.end).abs() <= 2
    }
}
impl Runtime {
    pub fn clear(&self, uid: i64) {
        self.entries.lock().unwrap().remove(&uid);
    }
    pub fn add_status(&self, uid: i64, value: &mut Value) {
        value["presence_supported"] = self.ready.load(Ordering::Relaxed).into();
        value["presence_status"] = self
            .entries
            .lock()
            .unwrap()
            .get(&uid)
            .filter(|e| e.updated.elapsed() < TTL)
            .map_or("waiting", |e| e.status)
            .into();
    }
    fn set_status(&self, uid: i64, version: u64, status: &'static str) {
        if let Some(e) = self
            .entries
            .lock()
            .unwrap()
            .get_mut(&uid)
            .filter(|e| e.version == version)
        {
            e.status = status;
        }
    }
    fn accept(
        &self,
        uid: i64,
        credential: String,
        activity: Option<Activity>,
        takeover: bool,
    ) -> bool {
        let mut entries = self.entries.lock().unwrap();
        if entries.get(&uid).is_some_and(|e| {
            e.updated.elapsed() < TTL
                && e.credential != credential
                && (activity.is_none() || !takeover)
        }) {
            return false;
        }
        let Some(activity) = activity else {
            entries.remove(&uid);
            return true;
        };
        if let Some(e) = entries
            .get_mut(&uid)
            .filter(|e| e.credential == credential && e.activity.same(&activity))
        {
            e.updated = Instant::now();
        } else {
            entries.insert(
                uid,
                Entry {
                    credential,
                    activity,
                    version: self.sequence.fetch_add(1, Ordering::Relaxed) + 1,
                    updated: Instant::now(),
                    retry: Instant::now(),
                    status: "connecting",
                },
            );
        }
        true
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Playback {
    playing: bool,
    #[serde(default)]
    title: String,
    #[serde(default)]
    description: String,
    #[serde(default)]
    position_ms: i64,
    #[serde(default)]
    duration_ms: i64,
    #[serde(default)]
    takeover: bool,
}
impl Playback {
    fn activity(&self) -> Result<Option<Activity>, Error> {
        if !self.playing {
            return Ok(None);
        }
        if self.title.trim().is_empty()
            || self.title.chars().count() > 128
            || self.description.chars().count() > 128
            || self
                .title
                .chars()
                .chain(self.description.chars())
                .any(char::is_control)
            || !(0..=604_800_000).contains(&self.duration_ms)
            || !(0..=604_800_000).contains(&self.position_ms)
            || (self.duration_ms > 0 && self.position_ms > self.duration_ms)
        {
            return Err(Error(StatusCode::BAD_REQUEST, "Invalid playback metadata"));
        }
        let start = now() - self.position_ms / 1000;
        Ok(Some(Activity {
            title: self.title.clone(),
            description: if self.description.trim().is_empty() {
                "Nami".into()
            } else {
                self.description.clone()
            },
            start,
            end: if self.duration_ms > 0 {
                start + self.duration_ms / 1000
            } else {
                0
            },
        }))
    }
}
pub async fn playback(
    State(st): State<Shared>,
    Extension(ident): Extension<Ident>,
    headers: HeaderMap,
    Json(body): Json<Playback>,
) -> Result<Json<Value>, Error> {
    let (uid, credential) = discord::owner(&st, &headers, &ident)?;
    let activity = body.activity()?;
    // Lock order is always DB -> runtime; revoke/reassignment cannot race this authorization.
    let db = st.db.lock().map_err(|_| discord::internal())?;
    if !discord::credential_alive(&db, uid, &credential) {
        return Err(Error(StatusCode::UNAUTHORIZED, "Invalid Nami session"));
    }
    let linked: bool = db.query_row(
        "SELECT EXISTS(SELECT 1 FROM discord_connections WHERE user_id=?1 AND client_id=?2)",
        params![uid, st.cfg.discord.as_ref().map(|c| c.client_id.as_str())],
        |r| r.get(0),
    )?;
    if !linked {
        return Err(Error(
            StatusCode::CONFLICT,
            "Connect your Discord account first",
        ));
    }
    if !st.discord_presence.ready.load(Ordering::Relaxed) {
        return Err(Error(
            StatusCode::SERVICE_UNAVAILABLE,
            "Discord Social SDK publisher is unavailable",
        ));
    }
    let accepted = st
        .discord_presence
        .accept(uid, credential, activity, body.takeover);
    Ok(Json(json!({"accepted": accepted})))
}

struct Desired {
    entry: Entry,
    discord_id: String,
    cipher: String,
    expires: i64,
}
fn desired(st: &Shared, uid: i64) -> Option<Desired> {
    let db = st.db.lock().ok()?;
    let mut entries = st.discord_presence.entries.lock().ok()?;
    let entry = entries.get(&uid)?;
    let cfg = st.cfg.discord.as_ref()?;
    let row = db.query_row("SELECT discord_user_id,token_cipher,expires_at FROM discord_connections WHERE user_id=?1 AND client_id=?2",
        params![uid, cfg.client_id], |r| Ok((r.get(0)?, r.get(1)?, r.get::<_, i64>(2)?))).optional().ok().flatten();
    if entry.updated.elapsed() >= TTL
        || !discord::credential_alive(&db, uid, &entry.credential)
        || row.is_none()
    {
        entries.remove(&uid);
        return None;
    }
    let (discord_id, cipher, expires) = row?;
    Some(Desired {
        entry: entry.clone(),
        discord_id,
        cipher,
        expires,
    })
}

fn command(path: &PathBuf) -> Command {
    let mut cmd = Command::new(path);
    // Tokens are sent over private stdin, never argv, environment or logs.
    for key in ["NAMI_DISCORD_CLIENT_SECRET", "NAMI_DISCORD_TOKEN_KEY"] {
        cmd.env_remove(key);
    }
    #[cfg(windows)]
    cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
    cmd.kill_on_drop(true);
    cmd
}
async fn send(stdin: &mut ChildStdin, line: String) -> Result<(), ()> {
    tokio::time::timeout(Duration::from_secs(2), stdin.write_all(line.as_bytes()))
        .await
        .map_err(|_| ())?
        .map_err(|_| ())
}

pub async fn spawn(st: Shared) {
    let Some(path) = std::env::var_os("NAMI_DISCORD_BRIDGE")
        .filter(|s| !s.is_empty())
        .map(PathBuf::from)
    else {
        return;
    };
    if st.cfg.discord.is_none() {
        return;
    }
    if !path.is_absolute() {
        tracing::warn!("NAMI_DISCORD_BRIDGE must be an absolute executable path");
        return;
    }
    let check = tokio::time::timeout(
        Duration::from_secs(5),
        command(&path).arg("--check").output(),
    )
    .await;
    let ready = check.is_ok_and(|r| {
        r.is_ok_and(|o| {
            o.status.success()
                && String::from_utf8_lossy(&o.stdout).trim() == "nami-discord-bridge-v1"
        })
    });
    st.discord_presence.ready.store(ready, Ordering::Relaxed);
    if !ready {
        tracing::warn!("Discord Social SDK bridge could not start; presence disabled");
        return;
    }
    tokio::spawn(async move {
        // One SDK process per actively listening user isolates SDK globals and credentials.
        // Idle users consume no SDK process. Large deployments should measure SDK memory usage.
        let mut jobs: HashMap<i64, JoinHandle<()>> = HashMap::new();
        let mut interval = tokio::time::interval(Duration::from_secs(1));
        loop {
            interval.tick().await;
            jobs.retain(|_, job| !job.is_finished());
            let ids: Vec<i64> = st
                .discord_presence
                .entries
                .lock()
                .unwrap()
                .keys()
                .copied()
                .collect();
            for uid in ids {
                if jobs.contains_key(&uid) {
                    continue;
                }
                if desired(&st, uid).is_none_or(|d| d.entry.retry > Instant::now()) {
                    continue;
                }
                let st = st.clone();
                let path = path.clone();
                jobs.insert(
                    uid,
                    tokio::spawn(async move {
                        if run(&st, uid, &path).await.is_err() {
                            if let Some(e) =
                                st.discord_presence.entries.lock().unwrap().get_mut(&uid)
                            {
                                e.status = "error";
                                e.retry = Instant::now() + Duration::from_secs(15);
                            }
                        }
                    }),
                );
            }
        }
    });
}

async fn run(st: &Shared, uid: i64, path: &PathBuf) -> Result<(), ()> {
    let cfg = st.cfg.discord.clone().ok_or(())?;
    if desired(st, uid).is_some_and(|d| d.expires <= now() + 60) {
        let st = st.clone();
        let cfg = cfg.clone();
        tokio::task::spawn_blocking(move || discord::refresh_tokens(&st, &cfg, uid))
            .await
            .map_err(|_| ())?
            .map_err(|_| ())?;
    }
    let Some(initial) = desired(st, uid) else {
        return Ok(());
    };
    st.discord_presence
        .set_status(uid, initial.entry.version, "connecting");
    let mut child = command(path)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|_| ())?;
    let mut stdin = child.stdin.take().ok_or(())?;
    let mut lines = BufReader::new(child.stdout.take().ok_or(())?).lines();
    let mut cipher = String::new();
    let mut account = String::new();
    let mut sent = 0;
    let mut refresh: Option<JoinHandle<Result<(), Error>>> = None;
    let mut next_refresh = Instant::now();
    let mut interval = tokio::time::interval(Duration::from_secs(1));
    let mut last_set = Instant::now() - Duration::from_secs(5);
    let mut connected_at = Instant::now();
    let result = async {
        loop {
            tokio::select! {
                line = lines.next_line() => {
                    let line = line.map_err(|_| ())?.ok_or(())?;
                    let Some((status, version)) = line.split_once(' ') else { return Err(()); };
                    let version = version.parse().map_err(|_| ())?;
                    match status {
                        "published" => st.discord_presence.set_status(uid, version, "published"),
                        "connecting" => { st.discord_presence.set_status(uid, version, "connecting"); connected_at = Instant::now(); }
                        "error" => return Err(()),
                        _ => return Err(()),
                    }
                }
                _ = interval.tick() => {
                    let Some(d) = desired(st, uid) else { break; };
                    if !account.is_empty() && account != d.discord_id { break; }
                    if d.expires <= now() { return Err(()); }
                    if refresh.as_ref().is_some_and(|j| j.is_finished()) {
                        let _ = refresh.take().unwrap().await;
                    }
                    if d.expires <= now()+60 && refresh.is_none() && Instant::now() >= next_refresh {
                        let st = st.clone(); let cfg = cfg.clone();
                        refresh = Some(tokio::task::spawn_blocking(move || discord::refresh_tokens(&st, &cfg, uid)));
                        next_refresh = Instant::now() + Duration::from_secs(30);
                    }
                    if cipher != d.cipher {
                        let token = discord::unseal(&cfg, uid, &d.cipher).map_err(|_| ())?;
                        send(&mut stdin, format!("TOKEN {} {}\n", d.discord_id, hex::encode(token.access_token))).await?;
                        cipher = d.cipher; account = d.discord_id; sent = 0;
                        connected_at = Instant::now();
                    }
                    if sent != d.entry.version && last_set.elapsed() >= Duration::from_secs(5) {
                        let a = &d.entry.activity;
                        send(&mut stdin, format!("SET {} {} {} {} {}\n", d.entry.version, hex::encode(&a.title), hex::encode(&a.description), a.start, a.end)).await?;
                        sent = d.entry.version;
                        last_set = Instant::now();
                        connected_at = Instant::now();
                    }
                    send(&mut stdin, "PING\n".into()).await?;
                    if d.entry.status == "connecting" && connected_at.elapsed() > Duration::from_secs(45) { return Err(()); }
                }
            }
        }
        Ok(())
    }.await;
    let _ = send(&mut stdin, "QUIT\n".into()).await;
    drop(stdin);
    if tokio::time::timeout(Duration::from_secs(3), child.wait())
        .await
        .is_err()
    {
        let _ = child.kill().await;
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::discord::tests::{cfg, database, link, shared};
    use axum::{
        body::{to_bytes, Body},
        http::Request,
    };
    use tower::ServiceExt;

    async fn post(st: Shared, token: &str, suffix: &str, value: Value) -> (StatusCode, Value) {
        let response = crate::api::router(st)
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(format!("/api/me/discord/playback{suffix}"))
                    .header("Authorization", format!("Bearer {token}"))
                    .header("Content-Type", "application/json")
                    .body(Body::from(value.to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        let status = response.status();
        let bytes = to_bytes(response.into_body(), 65536).await.unwrap();
        (
            status,
            serde_json::from_slice(&bytes).unwrap_or(Value::Null),
        )
    }
    #[tokio::test]
    async fn authenticated_phone_updates_only_its_owner_and_revocation_invalidates_it() {
        let mut db = database();
        link(&mut db, 1, "100");
        link(&mut db, 2, "200");
        db.execute(
            "INSERT INTO devices(name,token_hash,created_at) VALUES('anonymous',?1,0)",
            [crate::auth::hash_token("anonymous")],
        )
        .unwrap();
        let st = shared(db, Some(cfg()));
        st.discord_presence.ready.store(true, Ordering::Relaxed);
        let body = json!({"playing":true,"title":"A","description":"Artist · Джем","position_ms":1000,"duration_ms":180000});
        assert_eq!(
            post(st.clone(), "token1", "?user_id=2", body.clone())
                .await
                .0,
            StatusCode::OK
        );
        assert!(desired(&st, 2).is_none());
        assert_eq!(desired(&st, 1).unwrap().discord_id, "100");
        assert_eq!(
            post(st.clone(), "token2", "", body.clone()).await.0,
            StatusCode::OK
        );
        assert_eq!(desired(&st, 2).unwrap().discord_id, "200");
        assert_eq!(
            post(st.clone(), "anonymous", "", body.clone()).await.0,
            StatusCode::FORBIDDEN
        );
        assert_eq!(
            post(st.clone(), "token1", "", json!({"playing":false}))
                .await
                .0,
            StatusCode::OK
        );
        assert!(desired(&st, 1).is_none());
        assert!(desired(&st, 2).is_some());
        post(st.clone(), "token1", "", body.clone()).await;
        st.db
            .lock()
            .unwrap()
            .execute(
                "UPDATE devices SET user_id=2 WHERE token_hash=?1",
                [crate::auth::hash_token("token1")],
            )
            .unwrap();
        assert!(desired(&st, 1).is_none()); // revoked/reassigned source cannot keep publishing
        st.db
            .lock()
            .unwrap()
            .execute("DELETE FROM discord_connections WHERE user_id=2", [])
            .unwrap();
        assert!(desired(&st, 2).is_none());
        assert_eq!(post(st, "token2", "", body).await.0, StatusCode::CONFLICT);
    }
    #[tokio::test]
    async fn missing_sdk_does_not_accept_or_report_publication() {
        let mut db = database();
        link(&mut db, 1, "100");
        let st = shared(db, Some(cfg()));
        assert_eq!(
            post(
                st.clone(),
                "token1",
                "",
                json!({"playing":true,"title":"Track"})
            )
            .await
            .0,
            StatusCode::SERVICE_UNAVAILABLE
        );
        assert!(desired(&st, 1).is_none());
        let mut value = json!({});
        st.discord_presence.add_status(1, &mut value);
        assert_eq!(value["presence_supported"], false);
    }
    #[test]
    fn expired_phone_heartbeat_removes_activity_without_touching_other_users() {
        let mut db = database();
        link(&mut db, 1, "100");
        link(&mut db, 2, "200");
        let st = shared(db, Some(cfg()));
        for uid in [1, 2] {
            st.discord_presence.accept(
                uid,
                crate::auth::hash_token(&format!("token{uid}")),
                Some(activity("Track")),
                true,
            );
        }
        st.discord_presence
            .entries
            .lock()
            .unwrap()
            .get_mut(&1)
            .unwrap()
            .updated = Instant::now() - TTL;
        assert!(desired(&st, 1).is_none());
        assert!(desired(&st, 2).is_some());
    }
    fn activity(title: &str) -> Activity {
        Activity {
            title: title.into(),
            description: "Artist · Джем".into(),
            start: 1,
            end: 30,
        }
    }
    #[test]
    fn devices_cannot_clear_or_steal_each_others_activity_with_heartbeats() {
        let rt = Runtime::default();
        assert!(rt.accept(1, "a".into(), Some(activity("A")), true));
        assert!(rt.accept(2, "b".into(), Some(activity("B")), true));
        assert!(!rt.accept(1, "other".into(), None, true));
        assert!(!rt.accept(1, "other".into(), Some(activity("C")), false));
        assert!(rt.accept(1, "other".into(), Some(activity("C")), true));
        rt.clear(1);
        assert_eq!(
            rt.entries.lock().unwrap().get(&2).unwrap().activity.title,
            "B"
        );
    }
    #[test]
    fn stale_publish_ack_does_not_mark_a_new_track_published() {
        let rt = Runtime::default();
        rt.accept(1, "a".into(), Some(activity("A")), true);
        let version = rt.entries.lock().unwrap()[&1].version;
        rt.accept(1, "a".into(), Some(activity("B")), true);
        rt.set_status(1, version, "published");
        assert_eq!(rt.entries.lock().unwrap()[&1].status, "connecting");
        rt.entries.lock().unwrap().get_mut(&1).unwrap().updated = Instant::now() - TTL;
        let mut value = json!({});
        rt.add_status(1, &mut value);
        assert_eq!(value["presence_status"], "waiting");
    }
    #[test]
    fn metadata_rejects_target_user_and_invalid_positions() {
        assert!(serde_json::from_value::<Playback>(json!({"playing":true,"user_id":2})).is_err());
        let mut p: Playback = serde_json::from_value(
            json!({"playing":true,"title":"Track", "position_ms":1500,"duration_ms":5000}),
        )
        .unwrap();
        let a = p.activity().unwrap().unwrap();
        assert_eq!(a.end - a.start, 5);
        p.position_ms = 6000;
        assert!(p.activity().is_err());
        p.playing = false;
        assert!(p.activity().unwrap().is_none());
    }
}
