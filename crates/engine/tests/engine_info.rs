//! `EngineInfo` as the compatibility handshake: version, protocol generation
//! and capabilities on every serving path (the authenticated remote listener
//! and the in-process memory client), assessed as `Compatible` against this
//! build.

use std::sync::Arc;

use roboco_engine::pairing::PairingStore;
use roboco_engine::{EngineCore, EngineProfile, HarnessId, HarnessRegistry};
use roboco_proto::compat::{self, ClientBuild, Compatibility};
use roboco_proto::{EngineInfo, MIN_CLIENT_PROTOCOL_VERSION, PROTOCOL_VERSION};
use roboco_rpc::{connect_ws_authenticated, memory_client, methods};
use serde_json::{Value, json};

fn this_build() -> ClientBuild<'static> {
    ClientBuild {
        version: env!("CARGO_PKG_VERSION"),
        protocol_version: PROTOCOL_VERSION,
        min_engine_protocol_version: 1,
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn engine_info_serves_version_and_protocol_over_the_authenticated_listener() {
    let dir = tempfile::tempdir().unwrap();
    let core = EngineCore::assemble_with_profile(
        EngineProfile::local(dir.path()).unwrap(),
        Arc::new(HarnessRegistry::new()),
        HarnessId::Mock,
    )
    .unwrap();
    let store = PairingStore::open(dir.path()).unwrap();
    let remote = roboco_engine::serve_engine_remote(
        "127.0.0.1:0".parse().unwrap(),
        core.rpc_service(),
        dir.path(),
    )
    .await
    .unwrap();
    let base = format!("http://{}", remote.address);
    let url = format!("ws://{}", remote.address);
    let http = reqwest::Client::new();
    let code = store.create_code("remote", 300).unwrap();
    let grant: Value = http
        .post(format!("{base}/pairing/redeem"))
        .bearer_auth(&code.credential)
        .json(&json!({"label":"Laptop"}))
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap();
    let credential = grant["credential"].as_str().unwrap();
    let rpc = connect_ws_authenticated(&url, credential).await.unwrap();

    // Pin the wire shape first: the new fields are present and camelCase.
    let raw = rpc.call(methods::ENGINE_INFO, json!({})).await.unwrap();
    assert_eq!(raw["deviceId"], core.device_id);
    assert_eq!(raw["version"], env!("CARGO_PKG_VERSION"));
    assert_eq!(raw["protocolVersion"], PROTOCOL_VERSION);
    assert_eq!(raw["minClientProtocolVersion"], MIN_CLIENT_PROTOCOL_VERSION);
    assert_eq!(
        raw["capabilities"],
        serde_json::json!(roboco_proto::capabilities::current())
    );

    // Then decode: the reply is the current build's identity and this build
    // assesses itself as Compatible.
    let info: EngineInfo = serde_json::from_value(raw).unwrap();
    assert_eq!(info.version.as_deref(), Some(env!("CARGO_PKG_VERSION")));
    assert_eq!(info.protocol_version, Some(PROTOCOL_VERSION));
    assert_eq!(
        info.min_client_protocol_version,
        Some(MIN_CLIENT_PROTOCOL_VERSION)
    );
    assert_eq!(info.capabilities, roboco_proto::capabilities::current());
    assert_eq!(
        compat::assess(&info, &this_build()),
        Compatibility::Compatible
    );

    // The in-process memory client sees the identical identity.
    let memory: EngineInfo = memory_client(core.rpc_service())
        .call_as(methods::ENGINE_INFO, json!({}))
        .await
        .unwrap();
    assert_eq!(memory, info);

    drop(rpc);
    drop(remote);
    core.shutdown().await;
}
