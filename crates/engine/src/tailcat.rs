//! Tailcat transport: this engine's paired listener, published over Tailscale's
//! data plane with no control plane and no account.
//!
//! The adapter is the application-owned Go executable `roboco-tailcat`, built
//! against `github.com/tailscale/tailcat`. `serve` publishes one
//! numeric IPv4 loopback target and reports a `tc…` address; `connect` pulls that
//! address to a local loopback listener and reports its URL. Tailcat carries
//! bytes — pairing, session credentials, and revocation stay Roboco's (ADR 0006),
//! and the address itself is a transport handle, never authorization.
//!
//! Managed contract mirrored from the adapter: stdout carries exactly one
//! readiness JSON line, diagnostics go to stderr, the parent holds the write end
//! of the child's stdin so EOF ends the child even when a crash bypasses
//! destructors, `connect` config files are regular mode-0600 files that never
//! carry the secret in argv, and state files are role-tagged.
//!
//! Hygiene: the address can embed a pre-shared key, so logs and errors here never
//! quote it — [`describe`] reports only its shape.
//!
//! The invite grammar ([`TailcatInvite`], [`INVITE_PREFIX`], the address and
//! code validators, [`describe`]) lives in `roboco_proto::tailcat`, shared with
//! the Android core (android-next#02), and is re-exported here.

use anyhow::{Context, Result};
pub use roboco_proto::tailcat::{
    INVITE_PREFIX, InviteError, MAX_ADDRESS_BYTES, TailcatInvite, describe, validate_pair_code,
    validate_tailcat_address,
};
use serde::{Deserialize, Serialize};
use std::{
    io::{BufRead, BufReader},
    net::SocketAddr,
    path::{Path, PathBuf},
    process::{Child, ChildStdin, Command, Stdio},
    sync::mpsc,
    time::{Duration, Instant},
};

/// Adapter executable override (absolute path or a name resolved on `PATH`).
pub const ADAPTER_ENV: &str = "ROBOCO_TAILCAT_ADAPTER";
/// Legacy override left over from the zeron-era adapter; still honoured.
pub const LEGACY_ADAPTER_ENV: &str = "ZERON_TAILCAT_ADAPTER";
/// Operator-managed DERP map, for fleets that do not use the public relays.
pub const DERP_MAP_ENV: &str = "ROBOCO_TAILCAT_DERP_MAP";
/// The managed adapter's file name, installed beside the application binary.
pub const ADAPTER_BINARY: &str = "roboco-tailcat";
/// Where a running engine records the address its adapter published.
pub const ADDRESS_FILE: &str = "tailcat-address.json";

const READY_TIMEOUT: Duration = Duration::from_secs(30);
const STOP_GRACE: Duration = Duration::from_millis(750);

/// The adapter baked into this binary by `ROBOCO_TAILCAT_EMBED` (see
/// `build.rs`). Windows releases carry it: in-app updates replace only
/// `roboco.exe`, and the portable `.exe` is that file alone.
#[cfg(roboco_tailcat_embedded)]
static EMBEDDED_ADAPTER: Option<&[u8]> = Some(include_bytes!(env!("ROBOCO_TAILCAT_EMBED_FILE")));
#[cfg(not(roboco_tailcat_embedded))]
static EMBEDDED_ADAPTER: Option<&[u8]> = None;

/// Resolve the adapter executable: explicit override first, then the copy shipped
/// beside the running application binary.
pub fn resolve_adapter(explicit: Option<&str>, executable_dir: Option<&Path>) -> Result<PathBuf> {
    if let Some(value) = explicit {
        let path = PathBuf::from(value.trim());
        anyhow::ensure!(!path.as_os_str().is_empty(), "{ADAPTER_ENV} is empty");
        return Ok(path);
    }
    let directory = executable_dir.context("the running executable has no directory")?;
    Ok(directory.join(adapter_file_name()))
}

fn adapter_file_name() -> &'static str {
    if cfg!(windows) {
        "roboco-tailcat.exe"
    } else {
        ADAPTER_BINARY
    }
}

/// The adapter this process should run: the explicit override, else the copy
/// embedded in this binary (always this build's), else the one beside it.
pub fn adapter_path() -> Result<PathBuf> {
    let explicit = std::env::var(ADAPTER_ENV)
        .ok()
        .or_else(|| std::env::var(LEGACY_ADAPTER_ENV).ok());
    if explicit.is_none()
        && let Some(bytes) = EMBEDDED_ADAPTER
    {
        let root = roboco_harness::adapters_root().context(
            "no directory to unpack the bundled tailcat adapter into; set ROBOCO_DATA_DIR",
        )?;
        return unpack_adapter(bytes, &root.join(ADAPTER_BINARY));
    }
    let executable = std::env::current_exe().ok();
    let path = resolve_adapter(explicit.as_deref(), executable.as_deref().and_then(Path::parent))?;
    anyhow::ensure!(
        path.is_file(),
        "tailcat adapter not found at {}; install {} beside the Roboco executable or set {ADAPTER_ENV} \
         (source builds: cargo xtask tailcat-adapter)",
        path.display(),
        adapter_file_name()
    );
    Ok(path)
}

/// Unpack an embedded adapter to `<root>/<digest>/roboco-tailcat[.exe]`, once.
/// The digest names the build, so an updated Roboco never runs the previous
/// adapter and a running copy (locked, on Windows) is never rewritten. Other
/// builds' copies are pruned best-effort when a new one is unpacked.
pub fn unpack_adapter(bytes: &[u8], root: &Path) -> Result<PathBuf> {
    use sha2::{Digest, Sha256};
    use std::io::Write;
    let digest = format!("{:x}", Sha256::digest(bytes));
    let directory = root.join(&digest[..16]);
    let path = directory.join(adapter_file_name());
    let complete = |path: &Path| {
        std::fs::metadata(path).is_ok_and(|meta| meta.is_file() && meta.len() == bytes.len() as u64)
    };
    if complete(&path) {
        return Ok(path);
    }
    std::fs::create_dir_all(&directory)
        .with_context(|| format!("cannot create {}", directory.display()))?;
    let mut temporary = tempfile::NamedTempFile::new_in(&directory)?;
    temporary.write_all(bytes)?;
    #[cfg(unix)]
    set_mode(temporary.path(), 0o755)?;
    // Losing a race to a concurrent unpack of the same build is fine.
    if let Err(error) = temporary.persist(&path)
        && !complete(&path)
    {
        return Err(error.error)
            .with_context(|| format!("cannot unpack the tailcat adapter to {}", path.display()));
    }
    if let Ok(entries) = std::fs::read_dir(root) {
        for entry in entries.flatten() {
            if entry.path() != directory && entry.file_type().is_ok_and(|kind| kind.is_dir()) {
                let _ = std::fs::remove_dir_all(entry.path());
            }
        }
    }
    Ok(path)
}

/// DERP map configured for this process, if any.
pub fn derp_map_from_environment() -> Option<String> {
    std::env::var(DERP_MAP_ENV)
        .ok()
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct RecordedAddress {
    version: u32,
    address: String,
}

/// Record the address a running engine published, so a second process (the
/// pairing CLI) can mint invites without restarting the engine. The address is a
/// transport handle and can embed a pre-shared key, so it is written 0600.
pub fn record_address(data_dir: &Path, address: &str) -> Result<()> {
    validate_tailcat_address(address)?;
    write_private(
        &data_dir.join(ADDRESS_FILE),
        &serde_json::to_vec(&RecordedAddress {
            version: 1,
            address: address.to_owned(),
        })?,
    )
}

/// The address the engine at this data directory last published, if any.
pub fn read_recorded_address(data_dir: &Path) -> Result<Option<String>> {
    let path = data_dir.join(ADDRESS_FILE);
    match std::fs::read(&path) {
        Ok(bytes) => {
            let recorded: RecordedAddress = serde_json::from_slice(&bytes)
                .context("the recorded tailcat address is malformed")?;
            anyhow::ensure!(
                recorded.version == 1,
                "unsupported recorded tailcat address version {}",
                recorded.version
            );
            validate_tailcat_address(&recorded.address)?;
            Ok(Some(recorded.address))
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error).with_context(|| format!("cannot read {}", path.display())),
    }
}

/// A running `serve` adapter: publishes one loopback target over Tailcat.
pub struct TailcatServer {
    address: String,
    /// The data directory this server's publication was recorded in, when
    /// the record write succeeded.
    recorded_in: Option<PathBuf>,
    _child: AdapterChild,
}

impl TailcatServer {
    /// Publish `target` (numeric IPv4 loopback) and wait for readiness.
    pub fn start(data_dir: &Path, target: SocketAddr, derp_map: Option<&str>) -> Result<Self> {
        Self::start_with_adapter(adapter_path()?, data_dir, target, derp_map)
    }

    pub fn start_with_adapter(
        adapter: PathBuf,
        data_dir: &Path,
        target: SocketAddr,
        derp_map: Option<&str>,
    ) -> Result<Self> {
        validate_serve_target(target)?;
        let state = private_dir(data_dir)?.join("tailcat-server.key");
        let mut arguments = vec![
            "serve".to_owned(),
            "--state".to_owned(),
            state.display().to_string(),
            "--target".to_owned(),
            target.to_string(),
        ];
        push_derp_map(&mut arguments, derp_map);
        let mut child = AdapterChild::spawn(&adapter, "serve", &arguments)?;
        let ready: ServeReadiness = child.read_readiness("serve")?;
        validate_tailcat_address(&ready.address)?;
        // Best effort: a read-only data dir must not take the transport down.
        let recorded_in = match record_address(data_dir, &ready.address) {
            Ok(()) => Some(data_dir.to_path_buf()),
            Err(error) => {
                tracing::warn!(%error, "tailcat address could not be recorded for the pairing CLI");
                None
            }
        };
        tracing::info!(
            adapter = %adapter.display(),
            target = %target,
            address = %describe(&ready.address),
            "tailcat serve ready; the address is a transport handle and is not logged"
        );
        Ok(Self {
            address: ready.address,
            recorded_in,
            _child: child,
        })
    }

    /// Readiness must not be reused after the managed adapter exits.
    pub fn is_running(&mut self) -> bool {
        matches!(self._child.child.try_wait(), Ok(None))
    }
    pub fn address(&self) -> &str {
        &self.address
    }

    /// The address to mint an invite from: this server's recorded
    /// publication, re-read now, else the copy held since startup.
    ///
    /// A live engine once minted every invite from an in-memory copy that
    /// had drifted from the record by single-bit flips while the record
    /// still dialled; every client's `ParseAddr` refused the drifted one.
    /// The adapter publishes once per `serve` and persists its identity,
    /// so the record is this route's address for as long as it runs.
    pub fn published_address(&self) -> String {
        current_publication(&self.address, self.recorded_in.as_deref())
    }
}

/// [`TailcatServer::published_address`] over its parts: the record in
/// `recorded_in` when it still reads back, else `held`.
fn current_publication(held: &str, recorded_in: Option<&Path>) -> String {
    let Some(data_dir) = recorded_in else {
        return held.to_owned();
    };
    match read_recorded_address(data_dir) {
        Ok(Some(recorded)) => {
            if recorded != held {
                tracing::warn!(
                    recorded = %describe(&recorded),
                    held = %describe(held),
                    "the tailcat address held in memory no longer matches the recorded publication; minting from the record"
                );
            }
            recorded
        }
        Ok(None) => {
            tracing::warn!("the recorded tailcat address is gone; minting from the copy held in memory");
            held.to_owned()
        }
        Err(error) => {
            tracing::warn!(%error, "the recorded tailcat address is unreadable; minting from the copy held in memory");
            held.to_owned()
        }
    }
}

/// A running `connect` adapter: exposes one Tailcat peer at a local loopback URL.
pub struct TailcatClient {
    url: String,
    _child: AdapterChild,
}

impl TailcatClient {
    /// Pull `address` to a fresh loopback listener and wait for readiness. The
    /// identity for this connection is disposable and removed when the handle
    /// drops, so concurrent connects never share a node identity.
    pub fn start(data_dir: &Path, address: &str, derp_map: Option<&str>) -> Result<Self> {
        Self::start_with_adapter(adapter_path()?, data_dir, address, derp_map)
    }

    pub fn start_with_adapter(
        adapter: PathBuf,
        data_dir: &Path,
        address: &str,
        derp_map: Option<&str>,
    ) -> Result<Self> {
        validate_tailcat_address(address)?;
        let state_dir = private_dir(data_dir)?
            .join("clients")
            .join(uuid::Uuid::new_v4().to_string());
        std::fs::create_dir_all(&state_dir)?;
        #[cfg(unix)]
        set_mode(&state_dir, 0o700)?;
        let config = state_dir.join("connect.json");
        write_private(&config, &serde_json::to_vec(&ConnectConfig { address })?)?;
        let state = state_dir.join("tailcat-client.key");
        let mut arguments = vec![
            "connect".to_owned(),
            "--config".to_owned(),
            config.display().to_string(),
            "--listen".to_owned(),
            "127.0.0.1:0".to_owned(),
            "--state".to_owned(),
            state.display().to_string(),
        ];
        push_derp_map(&mut arguments, derp_map);
        let mut child = AdapterChild::spawn(&adapter, "connect", &arguments)?;
        child.cleanup = Some(state_dir.clone());
        let ready: ConnectReadiness = child.read_readiness("connect")?;
        validate_loopback_url(&ready.url)?;
        tracing::info!(
            adapter = %adapter.display(),
            url = %ready.url,
            address = %describe(address),
            "tailcat connect ready"
        );
        Ok(Self {
            url: ready.url,
            _child: child,
        })
    }

    /// Reconnect through an existing client state tree (relay restore after restart).
    pub fn resume_with_adapter(
        adapter: PathBuf,
        client_state_dir: &Path,
        derp_map: Option<&str>,
    ) -> Result<Self> {
        let config = client_state_dir.join("connect.json");
        let state = client_state_dir.join("tailcat-client.key");
        anyhow::ensure!(
            config.is_file(),
            "tailcat connect config missing at {}",
            config.display()
        );
        anyhow::ensure!(
            state.is_file(),
            "tailcat connect state missing at {}",
            state.display()
        );
        let mut arguments = vec![
            "connect".to_owned(),
            "--config".to_owned(),
            config.display().to_string(),
            "--listen".to_owned(),
            "127.0.0.1:0".to_owned(),
            "--state".to_owned(),
            state.display().to_string(),
        ];
        push_derp_map(&mut arguments, derp_map);
        let mut child = AdapterChild::spawn(&adapter, "connect", &arguments)?;
        let ready: ConnectReadiness = child.read_readiness("connect")?;
        validate_loopback_url(&ready.url)?;
        tracing::info!(
            adapter = %adapter.display(),
            url = %ready.url,
            state = %client_state_dir.display(),
            "tailcat connect restored"
        );
        Ok(Self {
            url: ready.url,
            _child: child,
        })
    }

    pub fn url(&self) -> &str {
        &self.url
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ConnectConfig<'a> {
    address: &'a str,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ServeReadiness {
    address: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ConnectReadiness {
    url: String,
}

fn push_derp_map(arguments: &mut Vec<String>, derp_map: Option<&str>) {
    if let Some(map) = derp_map.map(str::trim).filter(|map| !map.is_empty()) {
        arguments.push("--derp-map".to_owned());
        arguments.push(map.to_owned());
    }
}

fn validate_serve_target(target: SocketAddr) -> Result<()> {
    anyhow::ensure!(
        target.is_ipv4() && target.ip().is_loopback(),
        "tailcat publishes one numeric IPv4 loopback target, not {target}"
    );
    Ok(())
}

/// The adapter reports `http://127.0.0.1:<port>` and never anything else.
fn validate_loopback_url(url: &str) -> Result<()> {
    let parsed = reqwest::Url::parse(url).context("tailcat connect reported an unparsable URL")?;
    anyhow::ensure!(
        parsed.scheme() == "http"
            && parsed.host_str() == Some("127.0.0.1")
            && parsed.port().is_some(),
        "tailcat connect must report a numeric loopback URL"
    );
    Ok(())
}

/// The loopback address a `serve` adapter can publish for a listener bound at
/// `address`. An unspecified bind is narrowed to 127.0.0.1; anything that is not
/// loopback has no Tailcat route.
pub fn serve_target(address: SocketAddr) -> Result<SocketAddr> {
    if address.ip().is_unspecified() && address.is_ipv4() {
        return Ok(SocketAddr::new(
            std::net::IpAddr::V4(std::net::Ipv4Addr::LOCALHOST),
            address.port(),
        ));
    }
    validate_serve_target(address)?;
    Ok(address)
}

/// A supervised adapter child process.
struct AdapterChild {
    label: &'static str,
    child: Child,
    /// Holding the write end is the lifetime contract: EOF ends the child.
    pipe: Option<ChildStdin>,
    cleanup: Option<PathBuf>,
}

impl AdapterChild {
    fn spawn(adapter: &Path, role: &'static str, arguments: &[String]) -> Result<Self> {
        let mut command = Command::new(adapter);
        command
            .args(arguments)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit());
        // Ends the adapter even when native app termination or a crash bypasses
        // destructors; the adapter documents this variable.
        command.env("ROBOCO_TAILCAT_PARENT_PIPE", "1");
        // The adapter is a console program: started from the desktop (or the
        // detached CLI engine) it would otherwise open its own console window.
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x08000000); // CREATE_NO_WINDOW
        }
        let mut child = command
            .spawn()
            .with_context(|| format!("cannot start the tailcat {role} adapter at {}", adapter.display()))?;
        let pipe = child.stdin.take();
        Ok(Self {
            label: role,
            child,
            pipe,
            cleanup: None,
        })
    }

    /// Read exactly one readiness line. Failures report the line's size, never its
    /// contents: a malformed line can still carry the secret address.
    fn read_readiness<T: for<'de> Deserialize<'de>>(&mut self, role: &'static str) -> Result<T> {
        let stdout = self
            .child
            .stdout
            .take()
            .context("tailcat adapter has no stdout")?;
        let (sender, receiver) = mpsc::channel::<String>();
        std::thread::spawn(move || {
            let reader = BufReader::new(stdout);
            for line in reader.lines() {
                match line {
                    Ok(line) => {
                        if sender.send(line).is_err() {
                            break;
                        }
                    }
                    Err(_) => break,
                }
            }
        });
        let line = match receiver.recv_timeout(READY_TIMEOUT) {
            Ok(line) => line,
            Err(mpsc::RecvTimeoutError::Timeout) => anyhow::bail!(
                "the tailcat {role} adapter did not report readiness within {}s",
                READY_TIMEOUT.as_secs()
            ),
            Err(mpsc::RecvTimeoutError::Disconnected) => anyhow::bail!(
                "the tailcat {role} adapter exited before reporting readiness{}",
                self.exit_suffix()
            ),
        };
        serde_json::from_str::<T>(&line).with_context(|| {
            format!(
                "the tailcat {role} adapter reported a readiness line this engine cannot read ({} bytes)",
                line.len()
            )
        })
    }

    fn exit_suffix(&mut self) -> String {
        match self.child.try_wait() {
            Ok(Some(status)) => format!(" (exit status {status})"),
            _ => String::new(),
        }
    }

    fn stop(&mut self) {
        self.pipe.take();
        let deadline = Instant::now() + STOP_GRACE;
        loop {
            match self.child.try_wait() {
                Ok(Some(_)) | Err(_) => break,
                Ok(None) if Instant::now() < deadline => {
                    std::thread::sleep(Duration::from_millis(25));
                }
                Ok(None) => {
                    let _ = self.child.kill();
                    let _ = self.child.wait();
                    break;
                }
            }
        }
        if let Some(directory) = self.cleanup.take() {
            let _ = std::fs::remove_dir_all(directory);
        }
    }
}

impl Drop for AdapterChild {
    fn drop(&mut self) {
        self.stop();
        tracing::debug!(role = self.label, "tailcat adapter stopped");
    }
}

/// `<data_dir>/tailcat`, created 0700: adapter state and connect configs live here.
fn private_dir(data_dir: &Path) -> Result<PathBuf> {
    let directory = data_dir.join("tailcat");
    std::fs::create_dir_all(&directory)
        .with_context(|| format!("cannot create {}", directory.display()))?;
    #[cfg(unix)]
    set_mode(&directory, 0o700)?;
    Ok(directory)
}

/// Write a secret file atomically with owner-only access, refusing to leave a
/// readable copy behind if the mode cannot be applied.
pub(crate) fn write_private(path: &Path, bytes: &[u8]) -> Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let temporary = path.with_file_name(format!(
        "{}.{}.tmp",
        path.file_name()
            .map(|name| name.to_string_lossy().into_owned())
            .unwrap_or_else(|| "tailcat".to_owned()),
        uuid::Uuid::new_v4()
    ));
    let written = std::fs::write(&temporary, bytes).and_then(|()| {
        #[cfg(unix)]
        {
            set_mode(&temporary, 0o600)?;
        }
        Ok(())
    });
    if let Err(error) = written {
        let _ = std::fs::remove_file(&temporary);
        return Err(error).with_context(|| format!("cannot write {}", path.display()));
    }
    if let Err(error) = std::fs::rename(&temporary, path) {
        let _ = std::fs::remove_file(&temporary);
        return Err(error).with_context(|| format!("cannot replace {}", path.display()));
    }
    Ok(())
}

/// Supervision tests drive the documented adapter contract with a scripted
/// stand-in: stdout readiness, parent-pipe lifetime, private config files. The
/// real pinned adapter is exercised last when it is installed.
#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use std::io::{Read, Write as _};
    use std::net::TcpListener;

    const ADDRESS: &str = "tcAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    fn script(directory: &Path, name: &str, body: &str) -> PathBuf {
        let path = directory.join(name);
        std::fs::write(&path, format!("#!/bin/sh\n{body}\n")).unwrap();
        set_mode(&path, 0o700).unwrap();
        path
    }

    fn wait_for(path: &Path) -> String {
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            if let Ok(text) = std::fs::read_to_string(path) {
                return text;
            }
            assert!(Instant::now() < deadline, "{} never appeared", path.display());
            std::thread::sleep(Duration::from_millis(20));
        }
    }

    fn file_mode(path: &Path) -> u32 {
        use std::os::unix::fs::PermissionsExt;
        std::fs::metadata(path).unwrap().permissions().mode() & 0o777
    }

    #[test]
    fn recorded_addresses_round_trip_privately() {
        let dir = tempfile::tempdir().unwrap();
        assert!(read_recorded_address(dir.path()).unwrap().is_none());
        record_address(dir.path(), ADDRESS).unwrap();
        let path = dir.path().join(ADDRESS_FILE);
        assert_eq!(file_mode(&path), 0o600);
        assert_eq!(
            read_recorded_address(dir.path()).unwrap().as_deref(),
            Some(ADDRESS)
        );
        std::fs::write(&path, b"{broken").unwrap();
        assert!(read_recorded_address(dir.path()).is_err());
    }

    #[test]
    fn adapter_resolution_and_publish_targets_fail_closed() {
        assert_eq!(
            resolve_adapter(Some(" /tmp/adapter "), None).unwrap(),
            PathBuf::from("/tmp/adapter")
        );
        assert!(resolve_adapter(None, None).is_err());
        assert_eq!(
            resolve_adapter(None, Some(Path::new("/opt/roboco"))).unwrap(),
            Path::new("/opt/roboco").join(adapter_file_name())
        );
        assert_eq!(
            serve_target("0.0.0.0:27655".parse::<SocketAddr>().unwrap()).unwrap(),
            "127.0.0.1:27655".parse::<SocketAddr>().unwrap()
        );
        assert_eq!(
            serve_target("127.0.0.1:27655".parse::<SocketAddr>().unwrap()).unwrap(),
            "127.0.0.1:27655".parse::<SocketAddr>().unwrap()
        );
        assert!(serve_target("10.0.0.5:27655".parse().unwrap()).is_err());
        assert!(serve_target("[::1]:27655".parse().unwrap()).is_err());
    }

    #[test]
    fn serve_publishes_and_ends_with_the_parent_pipe() {
        let dir = tempfile::tempdir().unwrap();
        let marker = dir.path().join("exited");
        let adapter = script(
            dir.path(),
            "adapter",
            &format!(
                "printf '{{\"address\":\"{ADDRESS}\"}}\n'\ncat > /dev/null\nprintf exited > '{}'",
                marker.display()
            ),
        );
        let server = TailcatServer::start_with_adapter(
            adapter,
            dir.path(),
            "127.0.0.1:27655".parse().unwrap(),
            None,
        )
        .unwrap();
        assert_eq!(server.address(), ADDRESS);
        assert_eq!(
            read_recorded_address(dir.path()).unwrap().as_deref(),
            Some(ADDRESS)
        );
        assert_eq!(server.published_address(), ADDRESS);
        // Minting re-reads the record rather than trusting the copy held
        // since startup.
        const REPUBLISHED: &str = "tcBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
        record_address(dir.path(), REPUBLISHED).unwrap();
        assert_eq!(server.published_address(), REPUBLISHED);
        drop(server);
        assert_eq!(wait_for(&marker).trim(), "exited");
    }

    #[test]
    fn connect_writes_a_private_config_and_cleans_up() {
        let dir = tempfile::tempdir().unwrap();
        let marker = dir.path().join("observed");
        let adapter = script(
            dir.path(),
            "adapter",
            &format!(
                "cfg=\"\"\nwhile [ $# -gt 0 ]; do case \"$1\" in --config) cfg=\"$2\";; esac; shift; done\nprintf '%s\\n' \"$(stat -c %a \"$cfg\")\" > '{}'\ncat \"$cfg\" >> '{}'\nprintf '{{\"url\":\"http://127.0.0.1:45678\"}}\n'\ncat > /dev/null",
                marker.display(),
                marker.display()
            ),
        );
        let client =
            TailcatClient::start_with_adapter(adapter, dir.path(), ADDRESS, None).unwrap();
        assert_eq!(client.url(), "http://127.0.0.1:45678");
        let observed = wait_for(&marker);
        assert!(observed.starts_with("600\n"), "config mode was {observed:?}");
        assert!(observed.contains(ADDRESS));
        drop(client);
        assert_eq!(
            std::fs::read_dir(dir.path().join("tailcat").join("clients"))
                .unwrap()
                .count(),
            0,
            "a finished connect left its disposable identity behind"
        );
    }

    #[test]
    fn malformed_readiness_is_refused_without_leaking_the_address() {
        let dir = tempfile::tempdir().unwrap();
        let not_loopback = script(
            dir.path(),
            "not_loopback",
            "printf '{\"url\":\"http://0.0.0.0:1\"}'\nsleep 10",
        );
        assert!(
            TailcatClient::start_with_adapter(not_loopback, dir.path(), ADDRESS, None).is_err()
        );
        let garbage = script(dir.path(), "garbage", "printf 'not json'\nsleep 10");
        let error = TailcatClient::start_with_adapter(garbage, dir.path(), ADDRESS, None)
            .err()
            .expect("a garbage adapter must not produce a client")
            .to_string();
        assert!(error.contains("cannot read"), "{error}");
        assert!(!error.contains(ADDRESS), "{error}");
    }

    #[test]
    fn real_adapter_carries_bytes_over_tailcat() {
        let adapter = match adapter_path() {
            Ok(path) => path,
            Err(error) => {
                eprintln!("skipping the pinned-adapter run: {error}");
                return;
            }
        };
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let origin = listener.local_addr().unwrap();
        std::thread::spawn(move || {
            if let Ok((mut stream, _)) = listener.accept() {
                let mut buffer = [0u8; 1024];
                let _ = stream.read(&mut buffer);
                let _ = stream.write_all(
                    b"HTTP/1.1 200 OK\r\ncontent-length: 13\r\nconnection: close\r\n\r\nhello over tc",
                );
            }
        });
        let dir = tempfile::tempdir().unwrap();
        let server =
            TailcatServer::start_with_adapter(adapter.clone(), dir.path(), origin, None).unwrap();
        let client = TailcatClient::start_with_adapter(
            adapter,
            dir.path(),
            server.address(),
            None,
        )
        .unwrap();
        let mut stream =
            std::net::TcpStream::connect(client.url().trim_start_matches("http://")).unwrap();
        stream
            .set_read_timeout(Some(Duration::from_secs(45)))
            .unwrap();
        stream
            .write_all(b"GET / HTTP/1.0\r\nhost: tailcat\r\n\r\n")
            .unwrap();
        let mut body = String::new();
        stream.read_to_string(&mut body).unwrap();
        assert!(body.contains("hello over tc"), "{body}");
    }
}
#[cfg(unix)]
pub(crate) fn set_mode(path: &Path, mode: u32) -> std::io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode))
}

/// Invites mint from the recorded publication, not the copy held in memory.
#[cfg(test)]
mod publication_tests {
    use super::*;

    const RECORDED: &str = "tcRECORDEDaddress_V_Sqioxl";
    /// [`RECORDED`] with the live engine's single-bit flips (`q`→`i`, `i`→`m`).
    const DRIFTED: &str = "tcRECORDEDaddress_V_Simoxl";

    #[test]
    fn the_record_wins_over_a_drifted_copy_in_memory() {
        let dir = tempfile::tempdir().unwrap();
        record_address(dir.path(), RECORDED).unwrap();
        assert_eq!(current_publication(DRIFTED, Some(dir.path())), RECORDED);
        assert_eq!(current_publication(RECORDED, Some(dir.path())), RECORDED);
    }

    #[test]
    fn the_copy_in_memory_stands_in_when_there_is_no_usable_record() {
        let dir = tempfile::tempdir().unwrap();
        // The record write failed at startup: an older file is not this route's.
        record_address(dir.path(), RECORDED).unwrap();
        assert_eq!(current_publication(DRIFTED, None), DRIFTED);
        // The record vanished or was damaged after startup.
        std::fs::write(dir.path().join(ADDRESS_FILE), b"{broken").unwrap();
        assert_eq!(current_publication(DRIFTED, Some(dir.path())), DRIFTED);
        std::fs::remove_file(dir.path().join(ADDRESS_FILE)).unwrap();
        assert_eq!(current_publication(DRIFTED, Some(dir.path())), DRIFTED);
    }
}

/// The embedded adapter must reach disk on every platform the desktop ships.
#[cfg(test)]
mod embedded_adapter_tests {
    use super::*;

    fn builds(root: &Path) -> Vec<PathBuf> {
        let mut builds: Vec<_> = std::fs::read_dir(root)
            .unwrap()
            .map(|entry| entry.unwrap().path())
            .collect();
        builds.sort();
        builds
    }

    #[test]
    fn unpacks_once_per_build_and_prunes_older_builds() {
        let root = tempfile::tempdir().unwrap();
        let old = unpack_adapter(b"adapter v1", root.path()).unwrap();
        assert_eq!(old.file_name().unwrap(), adapter_file_name());
        assert_eq!(std::fs::read(&old).unwrap(), b"adapter v1");
        assert_eq!(unpack_adapter(b"adapter v1", root.path()).unwrap(), old);

        let new = unpack_adapter(b"adapter v2", root.path()).unwrap();
        assert_ne!(new, old);
        assert_eq!(std::fs::read(&new).unwrap(), b"adapter v2");
        assert_eq!(builds(root.path()), vec![new.parent().unwrap().to_owned()]);
    }

    #[test]
    fn a_truncated_copy_is_rewritten() {
        let root = tempfile::tempdir().unwrap();
        let path = unpack_adapter(b"complete adapter", root.path()).unwrap();
        std::fs::write(&path, b"compl").unwrap();
        assert_eq!(
            unpack_adapter(b"complete adapter", root.path()).unwrap(),
            path
        );
        assert_eq!(std::fs::read(&path).unwrap(), b"complete adapter");
    }

    #[cfg(unix)]
    #[test]
    fn the_unpacked_adapter_is_executable() {
        use std::os::unix::fs::PermissionsExt;
        let root = tempfile::tempdir().unwrap();
        let path = unpack_adapter(b"#!/bin/sh\n", root.path()).unwrap();
        let mode = std::fs::metadata(path).unwrap().permissions().mode();
        assert_eq!(mode & 0o111, 0o111, "{mode:o}");
    }
}
