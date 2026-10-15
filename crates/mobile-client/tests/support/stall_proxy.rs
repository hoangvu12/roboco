//! A TCP forwarder that can freeze mid-stream: `pause()` stops forwarding
//! in both directions (bytes wait in the kernel buffers), `resume()`
//! restarts it. It carries no protocol logic — it is the transport-level
//! way to reproduce a stalled engine link and a frozen transcript
//! connection with real timeouts.

use std::net::SocketAddr;
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::watch;

pub struct StallProxy {
    pub address: SocketAddr,
    paused: watch::Sender<bool>,
    task: tokio::task::JoinHandle<()>,
}

impl StallProxy {
    /// Forward `127.0.0.1:0` → `target`.
    pub async fn start(target: SocketAddr) -> Self {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let (paused, paused_rx) = watch::channel(false);
        let task = tokio::spawn(async move {
            loop {
                let Ok((client, _)) = listener.accept().await else {
                    break;
                };
                let Ok(engine) = tokio::net::TcpStream::connect(target).await else {
                    continue;
                };
                let (client_read, client_write) = client.into_split();
                let (engine_read, engine_write) = engine.into_split();
                let to_engine = paused_rx.clone();
                let to_client = paused_rx.clone();
                tokio::spawn(async move {
                    copy(client_read, engine_write, to_engine).await;
                });
                tokio::spawn(async move {
                    copy(engine_read, client_write, to_client).await;
                });
            }
        });
        Self {
            address,
            paused,
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
}

impl Drop for StallProxy {
    fn drop(&mut self) {
        self.task.abort();
    }
}

async fn copy<R, W>(mut from: R, mut to: W, mut paused: watch::Receiver<bool>)
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let mut buf = vec![0u8; 8 * 1024];
    loop {
        // While paused, read nothing: bytes wait in the kernel buffers on
        // both sides and the peer's sockets stay open.
        while *paused.borrow_and_update() {
            if paused.changed().await.is_err() {
                return;
            }
        }
        match tokio::time::timeout(Duration::from_secs(1), from.read(&mut buf)).await {
            Err(_) => continue, // Re-check the pause flag each second.
            Ok(Err(_)) | Ok(Ok(0)) => return,
            Ok(Ok(n)) => {
                // A read already in flight when the pause began must not
                // leak through: hold its bytes until resumed. Without this
                // the first chunk each way after `pause()` still crossed.
                while *paused.borrow_and_update() {
                    if paused.changed().await.is_err() {
                        return;
                    }
                }
                if to.write_all(&buf[..n]).await.is_err() {
                    return;
                }
            }
        }
    }
}
