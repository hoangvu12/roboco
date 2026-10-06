//! One-command Tailcat setup. The engine owns the route; the CLI only ensures
//! a background engine exists and asks it to enable Tailcat and issue an invite.
use anyhow::{Context, Result};
use roboco_engine::InstanceLock;
use roboco_rpc::{RpcClient, methods};
use serde_json::{Value, json};
use std::{
    fs::OpenOptions,
    path::Path,
    process::{Child, Command, Stdio},
    time::Duration,
};

const START_TIMEOUT: Duration = Duration::from_secs(60);

pub fn invite(data_dir: &Path, ttl_seconds: u64) -> Result<Value> {
    anyhow::ensure!(
        (1..=3600).contains(&ttl_seconds),
        "--ttl-seconds must be from 1 to 3600"
    );
    std::fs::create_dir_all(data_dir)?;
    // Serialize setup commands without taking the engine's lifetime lock.
    let setup_lock = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .open(data_dir.join("tailcat-setup.lock"))?;
    setup_lock.lock()?;
    let runtime = tokio::runtime::Runtime::new()?;
    runtime.block_on(async {
        let mut started = None;
        if InstanceLock::holder(data_dir).is_none() {
            roboco_engine::tailcat::adapter_path()?;
            started = Some(start_engine(data_dir)?);
            eprintln!("Starting Roboco engine in the background…");
        }
        let result = async {
            let client = wait_for_engine(data_dir, &mut started).await?;
            tokio::time::timeout(START_TIMEOUT, client.call(methods::CREATE_TAILCAT_INVITE, json!({"ttlSeconds": ttl_seconds})))
                .await.context("Timed out enabling the Tailcat route")?
                .context("Could not create a Tailcat invite; the engine must support one-command Tailcat setup")
        }.await;
        if result.is_err() {
            if let Some(child) = started.as_mut() {
                let _ = child.kill();
                let _ = child.wait();
            }
        }
        result
    })
}

fn configured_port() -> Result<u16> {
    match std::env::var("ROBOCO_IPC_PORT") {
        Ok(value) => value
            .parse()
            .context("ROBOCO_IPC_PORT must be a port number"),
        Err(std::env::VarError::NotPresent) => Ok(27654),
        Err(error) => Err(error.into()),
    }
}

fn start_engine(data_dir: &Path) -> Result<Child> {
    let data_dir = std::fs::canonicalize(data_dir)?;
    let port = configured_port()?;
    // Prefer the standard port, but independent VM/data-dir engines may share
    // a host. Give a new engine its own port rather than connect to a neighbour.
    let socket = match std::net::TcpListener::bind(("127.0.0.1", port)) {
        Ok(socket) => socket,
        Err(_) if std::env::var_os("ROBOCO_IPC_PORT").is_none() => {
            std::net::TcpListener::bind("127.0.0.1:0")?
        }
        Err(error) => return Err(error).context("The requested Roboco IPC port is unavailable"),
    };
    let port = socket.local_addr()?.port();
    drop(socket);
    let log_dir = data_dir.join("logs");
    std::fs::create_dir_all(&log_dir)?;
    let mut log_options = OpenOptions::new();
    log_options.create(true).append(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        log_options.mode(0o600);
    }
    let log = log_options.open(log_dir.join("tailcat-engine.log"))?;
    let mut command = Command::new(std::env::current_exe()?);
    command
        .arg("headless")
        .env("ROBOCO_DATA_DIR", &data_dir)
        .env("ROBOCO_IPC_PORT", port.to_string())
        .env_remove("ROBOCO_NETWORK")
        .env_remove("ROBOCO_NETWORK_TRANSPORT")
        .stdin(Stdio::null())
        .stdout(log.try_clone()?)
        .stderr(log);
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        // SAFETY: setsid is async-signal-safe and accesses no Rust state.
        unsafe {
            command.pre_exec(|| {
                if libc::setsid() == -1 {
                    return Err(std::io::Error::last_os_error());
                }
                Ok(())
            });
        }
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // Do not let main() reattach this detached engine to the CLI's console.
        command.env("ROBOCO_BACKGROUND_ENGINE", "1");
        command.creation_flags(0x00000008 | 0x00000200); // DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
    }
    command
        .spawn()
        .context("Could not start the background Roboco engine")
}

async fn wait_for_engine(data_dir: &Path, started: &mut Option<Child>) -> Result<RpcClient> {
    let deadline = tokio::time::Instant::now() + START_TIMEOUT;
    loop {
        if let Some(child) = started.as_mut() {
            if let Some(status) = child.try_wait()? {
                // A desktop or another setup process may have won the lifetime
                // lock; reuse that owner, but never hide an unowned startup failure.
                if InstanceLock::holder(data_dir).is_none() {
                    anyhow::bail!(
                        "Roboco engine exited ({status}); see {}",
                        data_dir.join("logs/tailcat-engine.log").display()
                    );
                }
            }
        }
        if let Some(holder) = InstanceLock::holder(data_dir) {
            let endpoint = std::fs::read(data_dir.join("engine-ipc.json"))
                .ok()
                .and_then(|bytes| serde_json::from_slice::<Value>(&bytes).ok());
            // A restarted engine may hold the lock before replacing an old
            // endpoint file. Never dial that stale port during its startup.
            let port = match endpoint {
                Some(endpoint)
                    if endpoint["pid"]
                        .as_u64()
                        .map(|pid| pid.to_string())
                        .as_deref()
                        == Some(holder.as_str()) =>
                {
                    endpoint["port"]
                        .as_u64()
                        .and_then(|port| u16::try_from(port).ok())
                }
                Some(_) => None,
                // A child we launched always publishes its actual IPC port.
                // Its lock can become live before that file exists; the default
                // port may belong to another engine, so wait for publication.
                None if started.is_some() => None,
                None => Some(configured_port()?),
            };
            if let Some(port) = port {
                if let Ok(Ok(client)) = tokio::time::timeout(
                    Duration::from_secs(2),
                    roboco_rpc::connect_ws(&format!("ws://127.0.0.1:{port}")),
                )
                .await
                {
                    if let Ok(Ok(info)) = tokio::time::timeout(
                        Duration::from_secs(2),
                        client.call(methods::ENGINE_INFO, json!({})),
                    )
                    .await
                    {
                        let expected = std::fs::read_to_string(data_dir.join("device-id"))
                            .context("Could not read this engine's device identity")?;
                        anyhow::ensure!(
                            info["deviceId"].as_str() == Some(expected.trim()),
                            "The IPC endpoint belongs to a different engine; refusing to change its remote settings"
                        );
                        return Ok(client);
                    }
                }
            }
        }
        anyhow::ensure!(
            tokio::time::Instant::now() < deadline,
            "Timed out waiting for this engine's local IPC; see {}",
            data_dir.join("logs/tailcat-engine.log").display()
        );
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}
