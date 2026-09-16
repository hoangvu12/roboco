//! Throwaway smoke server for manual browser verification of the web client
//! path: real remote listener + a fresh pair code, printed as a pairing URL.
use std::sync::Arc;

use roboco_engine::{EngineCore, EngineProfile, HarnessRegistry, pairing::PairingStore};

#[tokio::main(flavor = "multi_thread", worker_threads = 2)]
async fn main() {
    let dir = tempfile::tempdir().unwrap();
    let core = EngineCore::assemble_with_profile(
        EngineProfile::local(dir.path()).unwrap(),
        Arc::new(HarnessRegistry::new()),
        roboco_engine::HarnessId::Mock,
    )
    .unwrap();
    let remote = roboco_engine::serve_engine_remote(
        "127.0.0.1:27699".parse().unwrap(),
        core.rpc_service(),
        dir.path(),
    )
    .await
    .unwrap();
    let code = PairingStore::open(dir.path())
        .unwrap()
        .create_code("browser smoke", 3600)
        .unwrap();
    let url = roboco_engine::pairing::pairing_url("http://127.0.0.1:27699", &code.credential)
        .unwrap();
    println!("SMOKE READY {url}");
    std::future::pending::<()>().await;
    #[allow(unreachable_code)]
    {
        drop(remote);
        core.shutdown().await;
    }
}
