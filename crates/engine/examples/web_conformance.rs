//! Conformance engine for the `@roboco/engine-client` test suite: the real
//! remote listener on an ephemeral port plus a fresh pair code, announced on
//! stdout as one `CONFORMANCE {json}` line and kept alive until the test
//! teardown kills the process.
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
        "127.0.0.1:0".parse().unwrap(),
        core.rpc_service(),
        dir.path(),
    )
    .await
    .unwrap();
    let code = PairingStore::open(dir.path())
        .unwrap()
        .create_code("conformance", 3600)
        .unwrap();
    println!(
        "CONFORMANCE {}",
        serde_json::json!({
            "endpoint": format!("http://{}", remote.address),
            "pairCode": code.credential,
            "deviceId": core.device_id,
        })
    );
    std::future::pending::<()>().await;
    #[allow(unreachable_code)]
    {
        drop(remote);
        core.shutdown().await;
    }
}
