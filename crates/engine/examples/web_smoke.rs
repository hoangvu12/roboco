//! Throwaway smoke server for manual browser verification of the web client
//! path: real remote listener + a fresh pair code, printed as a pairing URL.
//! Seeds one chat with unseen activity so the chat list has a row to show.
use std::sync::Arc;

use roboco_engine::{EngineCore, EngineProfile, HarnessRegistry, pairing::PairingStore};
use roboco_rpc::RpcService;

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
    seed_smoke_chat(core.rpc_service()).await;
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

async fn seed_smoke_chat(service: Arc<dyn RpcService>) {
    let device = match service
        .handle(roboco_rpc::methods::LOCAL_DEVICE, serde_json::json!({}))
        .await
    {
        Ok(roboco_rpc::RpcReply::Value(value)) => value["deviceId"].as_str().unwrap().to_string(),
        Ok(_) => panic!("LocalDevice returned a stream"),
        Err(error) => panic!("LocalDevice failed: {error}"),
    };
    for params in [
        serde_json::json!({"op": "createChat", "chatId": "smoke-chat", "deviceId": device}),
        serde_json::json!({"op": "renameChat", "chatId": "smoke-chat", "title": "Browser smoke chat"}),
        serde_json::json!({"op": "setChatActivity", "chatId": "smoke-chat", "lastMessageAt": now_ms() - 5 * 60_000, "createdAt": now_ms() - 3_600_000}),
    ] {
        service
            .handle(roboco_rpc::methods::MUTATE, params)
            .await
            .expect("seed mutate");
    }
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64
}
