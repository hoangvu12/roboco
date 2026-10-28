//! The paired bind refuses every `Origin`-carrying request (ADR 0012). The
//! native stacks send none, so each of them still passes against a real
//! engine: the mobile client's redeem and Bearer RPC, the desktop's Rust RPC
//! dial (`roboco_rpc::connect_ws_authenticated`) and the preview-relay
//! dialer (`roboco_preview_relay::client::dial_relay`). The desktop
//! registry's own pairing runs in `roboco-engine-client`'s suites.

mod support;

use std::sync::Arc;
use std::time::Duration;

use roboco_mobile_client::EngineState;
use support::{Recorder, pairing_url, test_client};

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn native_clients_pass_the_paired_bind_origin_rule() {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = support::core(engine_dir.path());
    let listener = roboco_engine::serve_engine_remote_with_previews(
        "127.0.0.1:0".parse().unwrap(),
        core.rpc_service(),
        Some(core.previews.clone()),
        engine_dir.path(),
    )
    .await
    .unwrap();
    let base = format!("http://{}", listener.address);
    let ws = format!("ws://{}", listener.address);

    // The rule is live: a browser-shaped request is refused.
    let http = reqwest::Client::new();
    assert_eq!(
        http.get(format!("{base}/health"))
            .header("origin", "https://browser.example")
            .send()
            .await
            .unwrap()
            .status(),
        403
    );

    // The mobile client's redeem (POST /pairing/redeem) and its Bearer RPC.
    let client = test_client(data_dir.path(), Vec::new(), Arc::new(Recorder::default()));
    let url = pairing_url(&listener, engine_dir.path(), None);
    let record = tokio::time::timeout(Duration::from_secs(10), client.pair_engine(&url, None))
        .await
        .expect("pairing completes")
        .expect("the mobile redeem passes the origin rule");
    tokio::time::timeout(Duration::from_secs(5), async {
        while !client
            .engine(&record.engine_id)
            .is_some_and(|view| view.state == EngineState::Connected)
        {
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .expect("the mobile client connects over Bearer RPC");

    // The desktop's Rust RPC client, with the same session.
    let rpc = roboco_rpc::connect_ws_authenticated(&ws, &record.credential)
        .await
        .expect("the Bearer RPC dial passes the origin rule");
    let info = rpc
        .call(roboco_rpc::methods::ENGINE_INFO, serde_json::json!({}))
        .await
        .unwrap();
    assert_eq!(info["deviceId"], core.device_id);
    drop(rpc);

    // The preview-relay dialer: the upgrade itself is the check (the
    // service resolves only when a stream opens).
    let relay = roboco_preview_relay::client::dial_relay(
        &ws,
        Some(&record.credential),
        "same-chat",
        "no-such-service",
    )
    .await;
    assert!(
        relay.is_ok(),
        "the preview-relay dial passes the origin rule: {:?}",
        relay.err()
    );
    drop(relay);

    client.shutdown();
    drop(listener);
    core.shutdown().await;
}
