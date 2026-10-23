//! A TCP forwarder that can freeze mid-stream: `pause()` stops forwarding
//! in both directions (bytes wait in the kernel buffers), `resume()`
//! restarts it, and `sever()` kills every open connection silently (no
//! FIN, no RST — the sockets simply stop carrying bytes, as after a phone's
//! frozen process thaws) while new connections forward normally. It carries
//! no protocol logic — it is the transport-level way to reproduce a stalled
//! engine link and a frozen transcript connection with real timeouts.

use std::net::SocketAddr;
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::watch;

pub struct StallProxy {
    pub address: SocketAddr,
    paused: watch::Sender<bool>,
    /// Bumped by `sever`: a connection accepted under an older epoch stops
    /// forwarding for good.
    epoch: watch::Sender<u64>,
    task: tokio::task::JoinHandle<()>,
}

impl StallProxy {
    /// Forward `127.0.0.1:0` → `target`.
    pub async fn start(target: SocketAddr) -> Self {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let (paused, paused_rx) = watch::channel(false);
        let (epoch, epoch_rx) = watch::channel(0u64);
        let task = tokio::spawn(async move {
            loop {
                let Ok((client, _)) = listener.accept().await else {
                    break;
                };
                let Ok(engine) = tokio::net::TcpStream::connect(target).await else {
                    continue;
                };
                let born = *epoch_rx.borrow();
                let (client_read, client_write) = client.into_split();
                let (engine_read, engine_write) = engine.into_split();
                let gate = Gate {
                    paused: paused_rx.clone(),
                    epoch: epoch_rx.clone(),
                    born,
                };
                let back = gate.clone();
                tokio::spawn(async move {
                    copy(client_read, engine_write, gate).await;
                });
                tokio::spawn(async move {
                    copy(engine_read, client_write, back).await;
                });
            }
        });
        Self {
            address,
            paused,
            epoch,
            task,
        }
    }

    /// Stop forwarding in both directions.
    pub fn pause(&self) {
        self.paused.send_replace(true);
    }

    pub fn resume(&self) {
        self.paused.send_replace(false);
    }

    /// Every connection open now goes silent forever, without closing; new
    /// connections forward normally.
    pub fn sever(&self) {
        self.epoch.send_modify(|epoch| *epoch += 1);
    }
}

impl Drop for StallProxy {
    fn drop(&mut self) {
        self.task.abort();
    }
}

#[derive(Clone)]
struct Gate {
    paused: watch::Receiver<bool>,
    epoch: watch::Receiver<u64>,
    born: u64,
}

impl Gate {
    /// Wait while forwarding is held; `false` once the connection is
    /// severed (it then holds its sockets open and silent until dropped).
    async fn open(&mut self) -> bool {
        loop {
            if *self.epoch.borrow_and_update() != self.born {
                return false;
            }
            if !*self.paused.borrow_and_update() {
                return true;
            }
            tokio::select! {
                changed = self.paused.changed() => if changed.is_err() { return false },
                changed = self.epoch.changed() => if changed.is_err() { return false },
            }
        }
    }
}

async fn copy<R, W>(mut from: R, mut to: W, mut gate: Gate)
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let mut buf = vec![0u8; 8 * 1024];
    loop {
        // While paused, read nothing: bytes wait in the kernel buffers on
        // both sides and the peer's sockets stay open.
        if !gate.open().await {
            return hold(from, to).await;
        }
        match tokio::time::timeout(Duration::from_secs(1), from.read(&mut buf)).await {
            Err(_) => continue, // Re-check the gate each second.
            Ok(Err(_)) | Ok(Ok(0)) => return,
            Ok(Ok(n)) => {
                // A read already in flight when the pause began must not
                // leak through: hold its bytes until resumed. Without this
                // the first chunk each way after `pause()` still crossed.
                if !gate.open().await {
                    return hold(from, to).await;
                }
                if to.write_all(&buf[..n]).await.is_err() {
                    return;
                }
            }
        }
    }
}

/// A severed connection: keep both halves open and carry nothing, so
/// neither peer sees a close — only silence.
async fn hold<R, W>(from: R, to: W) {
    let _keep = (from, to);
    std::future::pending::<()>().await
}
