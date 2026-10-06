//! Remote exposure is explicit. Conflicting or invalid configuration stays local.
use roboco_proto::{PairedSession, PairingLink, RemoteAccessSnapshot, RemoteAccessStatus};
use serde::{Deserialize, Serialize};
use std::{
    net::{IpAddr, Ipv4Addr, SocketAddr},
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};

const FILE: &str = "remote-access.json";
pub const DEFAULT_REMOTE_PORT: u16 = 27655;

/// How a paired client reaches this engine. `Network` is a listener a client dials
/// directly (LAN or an operator tunnel); `Tailcat` publishes that listener over
/// Tailscale's data plane through the app-owned adapter.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum NetworkTransport {
    #[default]
    Network,
    Tailcat,
}

impl NetworkTransport {
    /// Parse the `--network-transport` / `ROBOCO_NETWORK_TRANSPORT` spelling.
    pub fn parse(value: &str) -> Option<Self> {
        match value.trim().to_ascii_lowercase().as_str() {
            "network" => Some(Self::Network),
            "tailcat" => Some(Self::Tailcat),
            _ => None,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Network => "network",
            Self::Tailcat => "tailcat",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields, rename_all = "camelCase")]
pub struct RemoteAccessSettings {
    pub enabled: bool,
    pub bind_address: SocketAddr,
    pub public_url: Option<String>,
    pub transport: NetworkTransport,
}impl Default for RemoteAccessSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            bind_address: SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), DEFAULT_REMOTE_PORT),
            public_url: None,
            transport: NetworkTransport::Network,
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct NetworkOptions {
    pub flag: Option<bool>,
    pub environment: Option<bool>,
    pub invalid_environment: bool,
    pub bind_address: Option<SocketAddr>,
    pub public_url: Option<String>,
    pub transport: Option<NetworkTransport>,
    pub invalid_transport: bool,
    pub derp_map: Option<String>,
}

impl NetworkOptions {
    /// Headless-only overrides. The desktop uses the saved Settings file.
    pub fn from_environment(
        flag: Option<bool>,
        bind_address: Option<SocketAddr>,
        public_url: Option<String>,
        transport: Option<NetworkTransport>,
        derp_map: Option<String>,
    ) -> Self {
        let raw = std::env::var("ROBOCO_NETWORK").ok();
        let raw_transport = std::env::var("ROBOCO_NETWORK_TRANSPORT").ok();
        let environment_transport = raw_transport.as_deref().and_then(NetworkTransport::parse);
        let environment =
            raw.as_deref()
                .and_then(|value| match value.trim().to_ascii_lowercase().as_str() {
                    "1" | "true" => Some(true),
                    "0" | "false" => Some(false),
                    _ => None,
                });
        Self {
            flag,
            environment,
            invalid_environment: raw.is_some() && environment.is_none(),
            bind_address,
            public_url,
            transport: transport.or(environment_transport),
            invalid_transport: raw_transport.is_some() && environment_transport.is_none(),
            derp_map: derp_map.or_else(crate::tailcat::derp_map_from_environment),
        }
    }
}

pub struct LoadedSettings {
    pub settings: RemoteAccessSettings,
    pub stored: bool,
    pub error: Option<String>,
}

pub fn load(directory: &Path) -> LoadedSettings {
    match std::fs::read(directory.join(FILE)) {
        Ok(bytes) => match serde_json::from_slice(&bytes) {
            Ok(settings) => LoadedSettings {
                settings,
                stored: true,
                error: None,
            },
            Err(error) => LoadedSettings {
                settings: Default::default(),
                stored: true,
                error: Some(format!("Remote access settings are invalid: {error}")),
            },
        },
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => LoadedSettings {
            settings: Default::default(),
            stored: false,
            error: None,
        },
        Err(error) => LoadedSettings {
            settings: Default::default(),
            stored: true,
            error: Some(format!("Remote access settings could not be read: {error}")),
        },
    }
}

pub fn save(directory: &Path, settings: &RemoteAccessSettings) -> anyhow::Result<()> {
    if let Some(url) = &settings.public_url {
        crate::pairing::pairing_url(url, "")?;
    }
    std::fs::create_dir_all(directory)?;
    let temporary = directory.join(format!("remote-access-{}.tmp", uuid::Uuid::new_v4()));
    std::fs::write(&temporary, serde_json::to_vec_pretty(settings)?)?;
    if let Err(error) = std::fs::rename(&temporary, directory.join(FILE)) {
        let _ = std::fs::remove_file(temporary);
        return Err(error.into());
    }
    Ok(())
}

pub fn resolve(loaded: &LoadedSettings, options: &NetworkOptions) -> RemoteAccessStatus {
    let values: Vec<_> = [
        loaded.stored.then_some(loaded.settings.enabled),
        options.flag,
        options.environment,
    ]
    .into_iter()
    .flatten()
    .collect();
    let conflict = values.windows(2).any(|pair| pair[0] != pair[1]);
    let transport_conflict = match (
        loaded.stored.then_some(loaded.settings.transport),
        options.transport,
    ) {
        (Some(stored), Some(requested)) => stored != requested,
        _ => false,
    };
    let error = loaded
        .error
        .clone()
        .or_else(|| {
            options
                .invalid_environment
                .then(|| "ROBOCO_NETWORK must be true, false, 1, or 0".to_owned())
        })
        .or_else(|| {
            options
                .invalid_transport
                .then(|| "ROBOCO_NETWORK_TRANSPORT must be network or tailcat".to_owned())
        })
        .or_else(|| {
            transport_conflict.then(|| {
                "Remote access transport settings disagree; remote access is off".to_owned()
            })
        })
        .or_else(|| {
            conflict.then(|| {
                "Remote access settings and startup override disagree; remote access is off"
                    .to_owned()
            })
        });
    let source = if options.flag.is_some() {
        "--network"
    } else if options.environment.is_some() || options.invalid_environment {
        "ROBOCO_NETWORK"
    } else if loaded.stored {
        FILE
    } else {
        "default"
    };
    RemoteAccessStatus {
        enabled: error.is_none() && values.last().copied().unwrap_or(false),
        configured_enabled: loaded.settings.enabled,
        address: None,
        source: source.into(),
        error,
    }
}

/// The transport in force. A conflict fails closed to `Network`; callers act on
/// `status.enabled` before serving anything.
pub fn effective_transport(loaded: &LoadedSettings, options: &NetworkOptions) -> NetworkTransport {
    match (
        loaded.stored.then_some(loaded.settings.transport),
        options.transport,
    ) {
        (Some(stored), Some(requested)) if stored != requested => NetworkTransport::Network,
        (_, Some(requested)) => requested,
        (Some(stored), None) => stored,
        (None, None) => NetworkTransport::Network,
    }
}

pub struct RemoteAccessController {
    directory: PathBuf,
    service: Mutex<Option<Arc<dyn roboco_rpc::RpcService>>>,
    state: tokio::sync::Mutex<ControlState>,
}

struct ControlState {
    options: NetworkOptions,
    status: RemoteAccessStatus,
    listener: Option<crate::EngineListener>,
    transport: NetworkTransport,
    tailcat_address: Option<String>,
    tailcat: Option<crate::tailcat::TailcatServer>,
}

impl RemoteAccessController {
    pub fn new(directory: &Path) -> Arc<Self> {
        let status = resolve(&load(directory), &NetworkOptions::default());
        Arc::new(Self {
            directory: directory.into(),
            service: Mutex::new(None),
            state: tokio::sync::Mutex::new(ControlState {
                options: Default::default(),
                status,
                listener: None,
                transport: NetworkTransport::Network,
                tailcat_address: None,
                tailcat: None,
            }),
        })
    }

    pub async fn initialize(
        &self,
        service: Arc<dyn roboco_rpc::RpcService>,
        options: NetworkOptions,
    ) {
        *self.service.lock().unwrap() = Some(service);
        let mut state = self.state.lock().await;
        state.options = options;
        self.apply(&mut state).await;
    }

    async fn apply(&self, state: &mut ControlState) {
        let loaded = load(&self.directory);
        state.status = resolve(&loaded, &state.options);
        if let Some(url) = state
            .options
            .public_url
            .as_ref()
            .or(loaded.settings.public_url.as_ref())
            && let Err(error) = crate::pairing::pairing_url(url, "")
        {
            state.status.enabled = false;
            state.status.error = Some(format!("Pairing address is invalid: {error}"));
        }
        // The adapter publishes the listener, so it always comes down first.
        state.tailcat = None;
        state.tailcat_address = None;
        if let Some(mut listener) = state.listener.take() {
            listener.stop().await;
        }
        if state.status.enabled {
            let service = self.service.lock().unwrap().clone();
            if let Some(service) = service {
                let address = state
                    .options
                    .bind_address
                    .unwrap_or(loaded.settings.bind_address);
                match crate::serve_engine_remote(address, service, &self.directory).await {
                    Ok(listener) => {
                        let address = listener.address;
                        state.status.address = Some(address);
                        state.transport = effective_transport(&loaded, &state.options);
                        state.listener = Some(listener);
                        if state.transport == NetworkTransport::Tailcat {
                            // `serve` publishes the listener over Tailscale's data
                            // plane; what clients paste becomes an invite carrying the
                            // address on the wire and never in a log.
                            let directory = self.directory.clone();
                            let derp_map = state.options.derp_map.clone();
                            let started = tokio::task::spawn_blocking(move || {
                                crate::tailcat::serve_target(address).and_then(|target| {
                                    crate::tailcat::TailcatServer::start(
                                        &directory,
                                        target,
                                        derp_map.as_deref(),
                                    )
                                })
                            })
                            .await
                            .map_err(anyhow::Error::from)
                            .and_then(|result| result);
                            match started {
                                Ok(server) => {
                                    state.tailcat_address = Some(server.address().to_owned());
                                    state.tailcat = Some(server);
                                }
                                Err(error) => {
                                    // No route means no remote access: stay local.
                                    state.status.enabled = false;
                                    state.status.address = None;
                                    state.status.error =
                                        Some(format!("Tailcat route unavailable: {error}"));
                                    state.tailcat_address = None;
                                    if let Some(mut listener) = state.listener.take() {
                                        listener.stop().await;
                                    }
                                }
                            }
                        }
                    }
                    Err(error) => {
                        state.status.enabled = false;
                        state.status.error =
                            Some(format!("Remote listener could not start: {error}"));
                    }
                }
            } else {
                state.status.enabled = false;
                state.status.error = Some("Engine is still starting".into());
            }
        }
        tracing::info!(source = %state.status.source, enabled = state.status.enabled, address = ?state.status.address, "remote access configuration applied");
        if let Some(error) = &state.status.error {
            tracing::warn!(message = %error, "remote access remains local-only");
        }
    }

    pub async fn snapshot(&self) -> anyhow::Result<serde_json::Value> {
        let status = self.state.lock().await.status.clone();
        let sessions: Vec<PairedSession> =
            crate::pairing::PairingStore::open(&self.directory)?.list_sessions()?;
        Ok(serde_json::to_value(RemoteAccessSnapshot {
            status,
            sessions,
        })?)
    }

    pub async fn set_enabled(&self, enabled: bool) -> anyhow::Result<serde_json::Value> {
        let mut state = self.state.lock().await;
        let mut settings = load(&self.directory).settings;
        settings.enabled = enabled;
        save(&self.directory, &settings)?;
        self.apply(&mut state).await;
        drop(state);
        self.snapshot().await
    }

    /// Explicit CLI setup: enable Tailcat on this engine and mint only from a
    /// live managed route. Unlike startup flags, this is a settings mutation.
    pub async fn create_tailcat_invite(&self, ttl_seconds: u64) -> anyhow::Result<serde_json::Value> {
        anyhow::ensure!((1..=3600).contains(&ttl_seconds), "ttlSeconds must be from 1 to 3600");
        let mut state = self.state.lock().await;
        let live = state.status.enabled
            && state.transport == NetworkTransport::Tailcat
            && state.status.address.is_some_and(|address| address.ip().is_loopback())
            && state.tailcat.as_mut().is_some_and(|server| server.is_running());
        // Check prerequisites before changing saved exposure settings.
        crate::tailcat::adapter_path()?;
        let loaded = load(&self.directory);
        anyhow::ensure!(loaded.error.is_none(), "{}", loaded.error.unwrap_or_default());
        let mut settings = loaded.settings;
        settings.enabled = true;
        settings.transport = NetworkTransport::Tailcat;
        // Tailcat needs only a loopback target, not an exposed LAN listener.
        settings.bind_address = "127.0.0.1:0".parse()?;
        settings.public_url = None;
        save(&self.directory, &settings)?;
        // Explicit setup supersedes startup-only networking overrides.
        let derp_map = state.options.derp_map.clone();
        state.options = NetworkOptions { derp_map, ..Default::default() };
        if !live {
            self.apply(&mut state).await;
        }
        anyhow::ensure!(state.status.enabled, "{}", state.status.error.as_deref().unwrap_or("Tailcat route is unavailable"));
        let address = state.tailcat_address.as_deref().ok_or_else(|| anyhow::anyhow!("Tailcat route is unavailable"))?;
        let code = crate::pairing::PairingStore::open(&self.directory)?.create_code("", ttl_seconds)?;
        let invite = crate::tailcat::TailcatInvite::new(address, &code.credential, code.expires_at)?;
        Ok(serde_json::json!({"id": code.id, "url": invite.encode()?, "expiresAt": code.expires_at}))
    }

    pub async fn create_link(&self) -> anyhow::Result<serde_json::Value> {
        let state = self.state.lock().await;
        let address = state
            .status
            .address
            .filter(|_| state.status.enabled)
            .ok_or_else(|| {
                anyhow::anyhow!("Enable remote access before creating a pairing link")
            })?;
        if state.transport == NetworkTransport::Tailcat {
            // The client's local listener port exists only on the client, so a
            // Tailcat route is paired with an invite: address plus pair code.
            let address = state.tailcat_address.clone().ok_or_else(|| {
                anyhow::anyhow!("The Tailcat route is not up; enable remote access first")
            })?;
            let code = crate::pairing::PairingStore::open(&self.directory)?
                .create_code("", crate::pairing::DEFAULT_TTL_SECONDS)?;
            let invite =
                crate::tailcat::TailcatInvite::new(&address, &code.credential, code.expires_at)?;
            return Ok(serde_json::to_value(PairingLink {
                url: invite.encode()?,
                expires_at: code.expires_at,
            })?);
        }
        let loaded = load(&self.directory);
        let base = match state
            .options
            .public_url
            .as_ref()
            .or(loaded.settings.public_url.as_ref())
        {
            Some(url) => url.clone(),
            None => advertised_url(address)?,
        };
        crate::pairing::pairing_url(&base, "")?;
        let code = crate::pairing::PairingStore::open(&self.directory)?
            .create_code("", crate::pairing::DEFAULT_TTL_SECONDS)?;
        Ok(serde_json::to_value(PairingLink {
            url: crate::pairing::pairing_url(&base, &code.credential)?,
            expires_at: code.expires_at,
        })?)
    }

    pub fn revoke(&self, session_id: &str) -> anyhow::Result<()> {
        anyhow::ensure!(
            crate::pairing::PairingStore::open(&self.directory)?.revoke(session_id)?,
            "active session not found"
        );
        Ok(())
    }

    /// The Tailcat address this engine published, when that transport is up.
    pub async fn tailcat_address(&self) -> Option<String> {
        self.state.lock().await.tailcat_address.clone()
    }

    pub async fn shutdown(&self) {
        let mut state = self.state.lock().await;
        state.tailcat = None;
        state.tailcat_address = None;
        if let Some(mut listener) = state.listener.take() {
            listener.stop().await;
        }
        state.status.enabled = false;
        state.status.address = None;
        self.service.lock().unwrap().take();
    }
}

fn advertised_url(address: SocketAddr) -> anyhow::Result<String> {
    let mut address = address;
    if address.ip().is_unspecified() {
        // UDP connect selects a local route; no packet is transmitted.
        let route = std::net::UdpSocket::bind("0.0.0.0:0")?;
        route.connect("192.0.2.1:80")?;
        address.set_ip(route.local_addr()?.ip());
        anyhow::ensure!(
            !address.ip().is_loopback() && !address.ip().is_unspecified(),
            "Could not determine LAN address; configure a public URL for pairing"
        );
    }
    Ok(format!("http://{address}"))
}
