//! The one-command Tailcat setup must start/reuse the real engine and mint only
//! after its managed route is ready. The fake adapter follows the engine contract:
//! one readiness JSON line on stdout, then it stays alive until killed or stdin EOF.
#![cfg(unix)]

use roboco_engine::tailcat::{INVITE_PREFIX, TailcatInvite};
use serde_json::{Value, json};
use std::{
    fs,
    path::{Path, PathBuf},
    process::{Child, Command, Output, Stdio},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

const FAKE_ADDRESS: &str = "tcZmFrZS1yb3V0ZQ";
const DEFAULT_IPC_PORT: u16 = 27654;

fn make_fake_adapter(root: &Path) -> PathBuf {
    use std::os::unix::fs::PermissionsExt;

    let adapter = root.join("roboco-tailcat-fake");
    fs::write(
        &adapter,
        format!(
            "#!/bin/sh\nset -eu\nprintf '%s\\n' \"$$\" >> \"$ROBOCO_TEST_ADAPTER_PIDS\"\nprintf '%s\\n' \"$*\" >> \"$ROBOCO_TEST_ADAPTER_ARGS\"\nprintf '{{\"address\":\"{FAKE_ADDRESS}\"}}\\n'\ncat >/dev/null\n"
        ),
    )
    .expect("write fake Tailcat adapter");
    fs::set_permissions(&adapter, fs::Permissions::from_mode(0o755))
        .expect("make fake adapter executable");
    adapter
}

fn run_invite(
    data_dir: &Path,
    adapter: Option<&Path>,
    adapter_pids: Option<&Path>,
    adapter_args: Option<&Path>,
    arguments: &[&str],
) -> Output {
    run_invite_with_ipc_port(
        data_dir,
        adapter,
        adapter_pids,
        adapter_args,
        Some("0"),
        arguments,
    )
}

fn run_invite_with_ipc_port(
    data_dir: &Path,
    adapter: Option<&Path>,
    adapter_pids: Option<&Path>,
    adapter_args: Option<&Path>,
    ipc_port: Option<&str>,
    arguments: &[&str],
) -> Output {
    let mut command = Command::new(env!("CARGO_BIN_EXE_roboco"));
    command
        .args(["engine", "tailcat", "invite"])
        .args(arguments)
        .env("ROBOCO_DATA_DIR", data_dir)
        .env("ROBOCO_HARNESS", "mock")
        // Deliberately remove any inherited setting; Some("0") keeps the older
        // isolated tests off the developer's ordinary 27654 listener.
        .env_remove("ROBOCO_IPC_PORT")
        // Deliberately conflicting startup overrides: one-command setup must
        // discard them and let the RPC switch the saved settings to Tailcat.
        .env("ROBOCO_NETWORK", "true")
        .env("ROBOCO_NETWORK_TRANSPORT", "tailcat")
        .env_remove("ROBOCO_TAILCAT_DERP_MAP")
        .env_remove("ROBOCO_TAILCAT_ADAPTER")
        .env_remove("ZERON_TAILCAT_ADAPTER")
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    if let Some(port) = ipc_port {
        command.env("ROBOCO_IPC_PORT", port);
    }
    if let Some(adapter) = adapter {
        command.env("ROBOCO_TAILCAT_ADAPTER", adapter);
    }
    if let Some(path) = adapter_pids {
        command.env("ROBOCO_TEST_ADAPTER_PIDS", path);
    }
    if let Some(path) = adapter_args {
        command.env("ROBOCO_TEST_ADAPTER_ARGS", path);
    }
    command.output().expect("run actual roboco CLI")
}

fn successful_invite(output: &Output, ttl_seconds: u64) -> Value {
    assert!(
        output.status.success(),
        "CLI failed (status {}):\nstdout: {}\nstderr: {}",
        output.status,
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let value: Value = serde_json::from_slice(&output.stdout).expect("--json output is JSON");
    let url = value["url"].as_str().expect("invite URL in JSON");
    assert!(url.starts_with(INVITE_PREFIX), "unexpected invite: {url}");
    let invite = TailcatInvite::decode(url).expect("CLI emitted a valid Tailcat invite");
    assert_eq!(invite.address, FAKE_ADDRESS);
    assert!(!invite.token.is_empty());
    let expires_at = value["expiresAt"].as_i64().expect("expiresAt in JSON");
    assert_eq!(invite.expires_at, expires_at);
    assert!(value["id"].as_str().is_some_and(|id| !id.is_empty()));

    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("clock after Unix epoch")
        .as_millis() as i64;
    let remaining_ms = expires_at - now;
    assert!(
        remaining_ms > (ttl_seconds.saturating_sub(15) as i64) * 1000
            && remaining_ms <= ttl_seconds as i64 * 1000,
        "TTL should be {ttl_seconds}s, got {remaining_ms}ms remaining"
    );
    value
}

fn engine_endpoint(data_dir: &Path) -> Value {
    let bytes = fs::read(data_dir.join("engine-ipc.json")).expect("engine IPC endpoint");
    let endpoint: Value = serde_json::from_slice(&bytes).expect("valid engine IPC endpoint JSON");
    assert!(endpoint["port"].as_u64().is_some_and(|port| port > 0));
    assert!(endpoint["pid"].as_u64().is_some_and(|pid| pid > 1));
    endpoint
}

fn adapter_pids(path: &Path) -> Vec<u32> {
    fs::read_to_string(path)
        .expect("fake adapter wrote its pid")
        .lines()
        .map(|line| line.parse().expect("numeric fake adapter pid"))
        .collect()
}

/// Owns only the detached engine and adapter processes created with this test's
/// temporary data directory. SIGTERM lets the engine close the adapter's stdin;
/// SIGKILL is a bounded fallback if a child ignores the shutdown contract.
struct DetachedEngineGuard {
    data_dir: PathBuf,
    adapter_pids: PathBuf,
}

impl DetachedEngineGuard {
    fn new(data_dir: &Path, adapter_pids: &Path) -> Self {
        Self {
            data_dir: data_dir.to_path_buf(),
            adapter_pids: adapter_pids.to_path_buf(),
        }
    }
}

impl Drop for DetachedEngineGuard {
    fn drop(&mut self) {
        if let Ok(bytes) = fs::read(self.data_dir.join("engine-ipc.json"))
            && let Ok(endpoint) = serde_json::from_slice::<Value>(&bytes)
            && let Some(pid) = endpoint["pid"]
                .as_u64()
                .and_then(|pid| u32::try_from(pid).ok())
        {
            terminate_test_process(pid);
        }
        if let Ok(pids) = fs::read_to_string(&self.adapter_pids) {
            for pid in pids.lines().filter_map(|line| line.parse::<u32>().ok()) {
                terminate_test_process(pid);
            }
        }
    }
}

fn terminate_test_process(pid: u32) {
    if pid <= 1 || pid == std::process::id() {
        return;
    }
    unsafe {
        libc::kill(pid as libc::pid_t, libc::SIGTERM);
    }
    let deadline = Instant::now() + Duration::from_secs(3);
    while Instant::now() < deadline && process_exists(pid) {
        thread::sleep(Duration::from_millis(25));
    }
    if process_exists(pid) {
        unsafe {
            libc::kill(pid as libc::pid_t, libc::SIGKILL);
        }
    }
}

fn process_exists(pid: u32) -> bool {
    if unsafe { libc::kill(pid as libc::pid_t, 0) } == 0 {
        // Linux can briefly retain a just-exited detached child as a zombie until
        // the system reaper observes it; a zombie is no longer a running process.
        #[cfg(target_os = "linux")]
        if let Ok(stat) = fs::read_to_string(format!("/proc/{pid}/stat"))
            && stat
                .rsplit_once(") ")
                .is_some_and(|(_, state)| state.starts_with('Z'))
        {
            return false;
        }
        true
    } else {
        std::io::Error::last_os_error().raw_os_error() == Some(libc::EPERM)
    }
}

struct DirectChild(Child);

impl Drop for DirectChild {
    fn drop(&mut self) {
        if self.0.try_wait().ok().flatten().is_none() {
            let _ = self.0.kill();
            let _ = self.0.wait();
        }
    }
}

fn wait_for_endpoint(data_dir: &Path) -> Value {
    let deadline = Instant::now() + Duration::from_secs(30);
    while Instant::now() < deadline {
        if let Ok(bytes) = fs::read(data_dir.join("engine-ipc.json"))
            && let Ok(endpoint) = serde_json::from_slice::<Value>(&bytes)
            && endpoint["port"].as_u64().is_some_and(|port| port > 0)
        {
            return endpoint;
        }
        thread::sleep(Duration::from_millis(25));
    }
    panic!("headless engine did not publish engine-ipc.json");
}

struct DefaultPortOccupancy {
    occupied: bool,
    sentinel_pid: Option<u32>,
    _sentinel: Option<DirectChild>,
    _listener: Option<std::net::TcpListener>,
}

/// If the normal port is free, hold it with our own real engine sentinel when
/// possible. If it was already occupied, leave that process completely alone.
fn occupy_default_port(sentinel_data_dir: &Path) -> DefaultPortOccupancy {
    let address = ("127.0.0.1", DEFAULT_IPC_PORT);
    match std::net::TcpListener::bind(address) {
        Err(error) => DefaultPortOccupancy {
            occupied: error.kind() == std::io::ErrorKind::AddrInUse,
            sentinel_pid: None,
            _sentinel: None,
            _listener: None,
        },
        Ok(listener) => {
            drop(listener);
            if let Some(sentinel) = start_default_port_sentinel(sentinel_data_dir) {
                let sentinel_pid = sentinel.0.id();
                return DefaultPortOccupancy {
                    occupied: true,
                    sentinel_pid: Some(sentinel_pid),
                    _sentinel: Some(sentinel),
                    _listener: None,
                };
            }

            match std::net::TcpListener::bind(address) {
                Ok(listener) => DefaultPortOccupancy {
                    occupied: true,
                    sentinel_pid: None,
                    _sentinel: None,
                    _listener: Some(listener),
                },
                Err(error) => DefaultPortOccupancy {
                    occupied: error.kind() == std::io::ErrorKind::AddrInUse,
                    sentinel_pid: None,
                    _sentinel: None,
                    _listener: None,
                },
            }
        }
    }
}

fn start_default_port_sentinel(data_dir: &Path) -> Option<DirectChild> {
    fs::create_dir_all(data_dir).ok()?;
    let mut sentinel = DirectChild(
        Command::new(env!("CARGO_BIN_EXE_roboco"))
            .arg("headless")
            .env("ROBOCO_DATA_DIR", data_dir)
            .env("ROBOCO_IPC_PORT", DEFAULT_IPC_PORT.to_string())
            .env("ROBOCO_HARNESS", "mock")
            .env_remove("ROBOCO_NETWORK")
            .env_remove("ROBOCO_NETWORK_TRANSPORT")
            .env_remove("ROBOCO_TAILCAT_ADAPTER")
            .env_remove("ZERON_TAILCAT_ADAPTER")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .ok()?,
    );
    let pid = sentinel.0.id();
    let deadline = Instant::now() + Duration::from_secs(30);
    while Instant::now() < deadline {
        if let Ok(bytes) = fs::read(data_dir.join("engine-ipc.json"))
            && let Ok(endpoint) = serde_json::from_slice::<Value>(&bytes)
            && endpoint["port"].as_u64() == Some(u64::from(DEFAULT_IPC_PORT))
            && endpoint["pid"].as_u64() == Some(u64::from(pid))
        {
            return Some(sentinel);
        }
        if sentinel.0.try_wait().ok().flatten().is_some() {
            return None;
        }
        thread::sleep(Duration::from_millis(25));
    }
    None
}

fn kill_fake_adapter(pid: u32) {
    let result = unsafe { libc::kill(pid as libc::pid_t, libc::SIGKILL) };
    assert_eq!(result, 0, "kill test-owned fake adapter pid {pid}");
}

#[test]
fn invite_starts_engine_switches_settings_reuses_healthy_route_and_restarts_dead_route() {
    let temp = tempfile::tempdir().unwrap();
    let data_dir = temp.path().join("vm-data");
    fs::create_dir_all(&data_dir).unwrap();
    let fake_adapter = make_fake_adapter(temp.path());
    let adapter_pids_path = temp.path().join("adapter-pids");
    let adapter_args_path = temp.path().join("adapter-args");
    let _engine = DetachedEngineGuard::new(&data_dir, &adapter_pids_path);

    fs::write(
        data_dir.join("remote-access.json"),
        serde_json::to_vec(&json!({
            "enabled": false,
            "bindAddress": "0.0.0.0:27655",
            "publicUrl": "https://legacy.example",
            "transport": "network"
        }))
        .unwrap(),
    )
    .unwrap();

    let first = successful_invite(
        &run_invite(
            &data_dir,
            Some(&fake_adapter),
            Some(&adapter_pids_path),
            Some(&adapter_args_path),
            &["--ttl-seconds", "47", "--json"],
        ),
        47,
    );
    let endpoint = engine_endpoint(&data_dir);
    let engine_pid = endpoint["pid"].as_u64().unwrap();
    assert!(data_dir.join("device-id").is_file());
    let settings: Value =
        serde_json::from_slice(&fs::read(data_dir.join("remote-access.json")).unwrap()).unwrap();
    assert_eq!(settings["enabled"], true);
    assert_eq!(settings["transport"], "tailcat");
    assert!(
        settings["bindAddress"]
            .as_str()
            .unwrap()
            .starts_with("127.0.0.1:")
    );
    assert_eq!(settings["publicUrl"], Value::Null);
    let first_pids = adapter_pids(&adapter_pids_path);
    assert_eq!(
        first_pids.len(),
        1,
        "one adapter should serve the new route"
    );
    let args = fs::read_to_string(&adapter_args_path).unwrap();
    assert!(
        args.contains("serve --state "),
        "unexpected adapter command: {args}"
    );
    assert!(
        args.contains("--target 127.0.0.1:"),
        "route must publish loopback: {args}"
    );

    let second = successful_invite(
        &run_invite(
            &data_dir,
            Some(&fake_adapter),
            Some(&adapter_pids_path),
            Some(&adapter_args_path),
            &["--ttl-seconds", "93", "--json"],
        ),
        93,
    );
    assert_ne!(
        first["id"], second["id"],
        "each call mints a fresh pairing code"
    );
    assert_eq!(engine_endpoint(&data_dir)["pid"].as_u64(), Some(engine_pid));
    assert_eq!(
        adapter_pids(&adapter_pids_path).len(),
        1,
        "a healthy route must be reused rather than spawning another adapter"
    );

    // A route address that was once ready is not enough: after its adapter dies,
    // the next CLI call must obtain fresh readiness before minting another invite.
    kill_fake_adapter(first_pids[0]);
    let restarted = successful_invite(
        &run_invite(
            &data_dir,
            Some(&fake_adapter),
            Some(&adapter_pids_path),
            Some(&adapter_args_path),
            &["--ttl-seconds", "61", "--json"],
        ),
        61,
    );
    assert_ne!(second["id"], restarted["id"]);
    assert_eq!(engine_endpoint(&data_dir)["pid"].as_u64(), Some(engine_pid));
    let pids_after_restart = adapter_pids(&adapter_pids_path);
    assert_eq!(
        pids_after_restart.len(),
        2,
        "dead route should be restarted"
    );
    assert_ne!(pids_after_restart[0], pids_after_restart[1]);
}

#[test]
fn fresh_invite_uses_ephemeral_ipc_when_default_port_is_occupied() {
    let temp = tempfile::tempdir().unwrap();
    let data_dir = temp.path().join("vm-data");
    fs::create_dir_all(&data_dir).unwrap();
    let adapter = make_fake_adapter(temp.path());
    let adapter_pids_path = temp.path().join("adapter-pids");
    let adapter_args_path = temp.path().join("adapter-args");
    let occupancy = occupy_default_port(&temp.path().join("sentinel-data"));
    // Declared after the sentinel so it shuts down first and releases any live
    // Tailcat child before the port-owning sentinel is stopped.
    let _engine = DetachedEngineGuard::new(&data_dir, &adapter_pids_path);

    let invite = successful_invite(
        &run_invite_with_ipc_port(
            &data_dir,
            Some(&adapter),
            Some(&adapter_pids_path),
            Some(&adapter_args_path),
            None,
            &["--ttl-seconds", "73", "--json"],
        ),
        73,
    );
    assert!(invite["url"].as_str().is_some());
    let endpoint = engine_endpoint(&data_dir);
    if occupancy.occupied {
        assert_ne!(
            endpoint["port"].as_u64(),
            Some(u64::from(DEFAULT_IPC_PORT)),
            "a fresh engine must avoid the occupied standard IPC port"
        );
    }
    if let Some(sentinel_pid) = occupancy.sentinel_pid {
        assert_ne!(endpoint["pid"].as_u64(), Some(u64::from(sentinel_pid)));
    }
    assert_eq!(adapter_pids(&adapter_pids_path).len(), 1);
}

#[test]
fn stale_ipc_and_tailcat_address_without_adapter_never_produce_an_invite() {
    let temp = tempfile::tempdir().unwrap();
    let data_dir = temp.path().join("vm-data");
    fs::create_dir_all(&data_dir).unwrap();
    let adapter_pids_path = temp.path().join("adapter-pids");
    let missing_adapter = temp.path().join("not-installed");
    let stale_endpoint = json!({ "port": 1, "pid": 42_424_242_u32 });
    let stale_address = json!({ "version": 1, "address": FAKE_ADDRESS });
    fs::write(
        data_dir.join("engine-ipc.json"),
        serde_json::to_vec(&stale_endpoint).unwrap(),
    )
    .unwrap();
    fs::write(
        data_dir.join("tailcat-address.json"),
        serde_json::to_vec(&stale_address).unwrap(),
    )
    .unwrap();

    let output = run_invite(
        &data_dir,
        Some(&missing_adapter),
        Some(&adapter_pids_path),
        None,
        &["--ttl-seconds", "60", "--json"],
    );
    assert!(!output.status.success(), "missing adapter must fail setup");
    assert!(
        String::from_utf8_lossy(&output.stderr).contains("tailcat adapter not found"),
        "expected missing adapter error, got: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(
        !String::from_utf8_lossy(&output.stdout).contains(INVITE_PREFIX),
        "a stale address must never be printed as a fresh invite"
    );
    assert_eq!(
        serde_json::from_slice::<Value>(&fs::read(data_dir.join("engine-ipc.json")).unwrap())
            .unwrap(),
        stale_endpoint,
        "the CLI must not adopt an endpoint without an owning engine lock"
    );
    assert!(roboco_engine::InstanceLock::holder(&data_dir).is_none());
    assert!(
        !adapter_pids_path.exists(),
        "no adapter should have been spawned"
    );
}

#[test]
fn cli_rejects_ipc_whose_engine_identity_differs_from_the_data_directory() {
    let temp = tempfile::tempdir().unwrap();
    let data_dir = temp.path().join("vm-data");
    fs::create_dir_all(&data_dir).unwrap();
    let engine = DirectChild(
        Command::new(env!("CARGO_BIN_EXE_roboco"))
            .arg("headless")
            .env("ROBOCO_DATA_DIR", &data_dir)
            .env("ROBOCO_IPC_PORT", "0")
            .env("ROBOCO_HARNESS", "mock")
            .env_remove("ROBOCO_NETWORK")
            .env_remove("ROBOCO_NETWORK_TRANSPORT")
            .env_remove("ROBOCO_TAILCAT_ADAPTER")
            .env_remove("ZERON_TAILCAT_ADAPTER")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .expect("start real headless engine"),
    );
    let engine_pid = engine.0.id();
    let endpoint = wait_for_endpoint(&data_dir);
    assert_eq!(endpoint["pid"].as_u64(), Some(u64::from(engine_pid)));
    fs::write(data_dir.join("device-id"), "different-device-identity\n").unwrap();

    let output = run_invite(
        &data_dir,
        None,
        None,
        None,
        &["--ttl-seconds", "60", "--json"],
    );
    assert!(!output.status.success(), "wrong-engine IPC must be refused");
    assert!(
        String::from_utf8_lossy(&output.stderr).contains("different engine"),
        "expected device identity mismatch, got: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(
        !String::from_utf8_lossy(&output.stdout).contains(INVITE_PREFIX),
        "a mismatched engine must never produce an invite"
    );
}
