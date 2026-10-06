//! Settings controls the real remote bind; every assertion goes through HTTP/RPC.
use roboco_engine::{
    EngineCore, EngineProfile, HarnessId, HarnessRegistry,
    remote_access::{NetworkOptions, NetworkTransport, RemoteAccessSettings},
};
use roboco_rpc::methods;
use serde_json::{Value, json};
use std::sync::Arc;

async fn engine(
    directory: &std::path::Path,
    options: NetworkOptions,
) -> (
    EngineCore,
    roboco_rpc::RpcClient,
    tokio::task::JoinHandle<()>,
) {
    let core = EngineCore::assemble_with_profile(
        EngineProfile::local(directory).unwrap(),
        Arc::new(HarnessRegistry::new()),
        HarnessId::Mock,
    )
    .unwrap();
    let service = core.rpc_service();
    core.remote_access
        .initialize(service.clone(), options)
        .await;
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server = tokio::spawn(roboco_rpc::serve_ws_listener(listener, service));
    let rpc = roboco_rpc::connect_ws(&format!("ws://{address}"))
        .await
        .unwrap();
    (core, rpc, server)
}

#[tokio::test]
async fn tailcat_invite_rejects_invalid_ttl_before_changing_settings() {
    let dir = tempfile::tempdir().unwrap();
    let (core, rpc, server) = engine(dir.path(), NetworkOptions::default()).await;
    for ttl in [0, 3601] {
        let error = rpc.call(methods::CREATE_TAILCAT_INVITE, json!({"ttlSeconds": ttl})).await.unwrap_err();
        assert!(error.to_string().contains("from 1 to 3600"), "{error}");
    }
    assert!(!dir.path().join("remote-access.json").exists());
    assert_eq!(rpc.call(methods::GET_REMOTE_ACCESS, json!({})).await.unwrap()["status"]["enabled"], false);
    server.abort();
    core.shutdown().await;
}

#[tokio::test]
async fn settings_toggle_pairing_and_revocation_control_live_listener() {
    let dir = tempfile::tempdir().unwrap();
    roboco_engine::remote_access::save(
        dir.path(),
        &RemoteAccessSettings {
            bind_address: "127.0.0.1:0".parse().unwrap(),
            ..Default::default()
        },
    )
    .unwrap();
    let (core, rpc, server) = engine(dir.path(), NetworkOptions::default()).await;
    let before = rpc
        .call(methods::GET_REMOTE_ACCESS, json!({}))
        .await
        .unwrap();
    assert_eq!(before["status"]["enabled"], false);
    let enabled = rpc
        .call(methods::SET_REMOTE_ACCESS, json!({"enabled":true}))
        .await
        .unwrap();
    let address = enabled["status"]["address"].as_str().unwrap();
    assert_eq!(enabled["status"]["enabled"], true);
    let http = reqwest::Client::new();
    assert_eq!(
        http.get(format!("http://{address}/health"))
            .send()
            .await
            .unwrap()
            .status(),
        401
    );
    let link = rpc
        .call(methods::CREATE_PAIRING_LINK, json!({}))
        .await
        .unwrap();
    let url = reqwest::Url::parse(link["url"].as_str().unwrap()).unwrap();
    let credential = url.fragment().unwrap().strip_prefix("token=").unwrap();
    let grant: Value = http
        .post(format!("http://{address}/pairing/redeem"))
        .bearer_auth(credential)
        .json(&json!({"label":"Tablet"}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let token = grant["credential"].as_str().unwrap();
    let remote = roboco_rpc::connect_ws_authenticated(&format!("ws://{address}"), token)
        .await
        .unwrap();
    let snapshot = rpc
        .call(methods::GET_REMOTE_ACCESS, json!({}))
        .await
        .unwrap();
    assert_eq!(snapshot["sessions"][0]["label"], "Tablet");
    rpc.call(
        methods::REVOKE_PAIRING_SESSION,
        json!({"sessionId":grant["session"]["id"]}),
    )
    .await
    .unwrap();
    assert!(
        roboco_rpc::connect_ws_authenticated(&format!("ws://{address}"), token)
            .await
            .is_err()
    );
    // The caller can disable its own remote connection; saved/effective state
    // must be correct even when cancellation prevents its reply from arriving.
    let _ = tokio::time::timeout(
        std::time::Duration::from_secs(3),
        remote.call(methods::SET_REMOTE_ACCESS, json!({"enabled":false})),
    )
    .await;
    let disabled = rpc
        .call(methods::GET_REMOTE_ACCESS, json!({}))
        .await
        .unwrap();
    assert_eq!(disabled["status"]["enabled"], false);
    assert!(tokio::net::TcpStream::connect(address).await.is_err());
    assert!(remote.call(methods::ENGINE_INFO, json!({})).await.is_err());
    // A quick re-enable can bind again without a restart or port-release race.
    assert_eq!(
        rpc.call(methods::SET_REMOTE_ACCESS, json!({"enabled":true}))
            .await
            .unwrap()["status"]["enabled"],
        true
    );
    server.abort();
    core.shutdown().await;
}

#[test]
fn path_prefixed_public_url_is_refused_at_save() {
    let dir = tempfile::tempdir().unwrap();
    let settings = RemoteAccessSettings {
        public_url: Some("https://tunnel.example/roboco".into()),
        ..Default::default()
    };
    assert!(roboco_engine::remote_access::save(dir.path(), &settings).is_err());
    assert!(!dir.path().join("remote-access.json").exists());
}

#[tokio::test]
async fn conflicting_and_malformed_configuration_stays_local() {
    for invalid_file in [false, true] {
        let dir = tempfile::tempdir().unwrap();
        if invalid_file {
            std::fs::write(dir.path().join("remote-access.json"), "{broken").unwrap();
        } else {
            roboco_engine::remote_access::save(dir.path(), &RemoteAccessSettings::default())
                .unwrap();
        }
        let (core, rpc, server) = engine(
            dir.path(),
            NetworkOptions {
                flag: Some(true),
                bind_address: Some("127.0.0.1:0".parse().unwrap()),
                ..Default::default()
            },
        )
        .await;
        let snapshot = rpc
            .call(methods::GET_REMOTE_ACCESS, json!({}))
            .await
            .unwrap();
        assert_eq!(snapshot["status"]["enabled"], false);
        assert!(snapshot["status"]["address"].is_null());
        assert!(snapshot["status"]["error"].as_str().is_some());
        assert!(
            rpc.call(methods::CREATE_PAIRING_LINK, json!({}))
                .await
                .is_err()
        );
        server.abort();
        core.shutdown().await;
    }
}

#[test]
fn tailcat_transport_round_trips_and_legacy_files_default_to_network() {
    let dir = tempfile::tempdir().unwrap();
    roboco_engine::remote_access::save(
        dir.path(),
        &RemoteAccessSettings {
            transport: NetworkTransport::Tailcat,
            ..Default::default()
        },
    )
    .unwrap();
    assert_eq!(
        roboco_engine::remote_access::load(dir.path())
            .settings
            .transport,
        NetworkTransport::Tailcat
    );
    // A settings file written before transports existed loads as the network
    // listener it was written for.
    std::fs::write(
        dir.path().join("remote-access.json"),
        r#"{"enabled":true,"bindAddress":"127.0.0.1:27655","publicUrl":null}"#,
    )
    .unwrap();
    let legacy = roboco_engine::remote_access::load(dir.path());
    assert!(legacy.error.is_none(), "{:?}", legacy.error);
    assert_eq!(legacy.settings.transport, NetworkTransport::Network);
}

#[tokio::test]
async fn conflicting_transport_settings_stay_local() {
    let dir = tempfile::tempdir().unwrap();
    roboco_engine::remote_access::save(
        dir.path(),
        &RemoteAccessSettings {
            enabled: true,
            bind_address: "127.0.0.1:0".parse().unwrap(),
            transport: NetworkTransport::Tailcat,
            ..Default::default()
        },
    )
    .unwrap();
    let (core, rpc, server) = engine(
        dir.path(),
        NetworkOptions {
            flag: Some(true),
            transport: Some(NetworkTransport::Network),
            ..Default::default()
        },
    )
    .await;
    let snapshot = rpc
        .call(methods::GET_REMOTE_ACCESS, json!({}))
        .await
        .unwrap();
    assert_eq!(snapshot["status"]["enabled"], false);
    assert!(snapshot["status"]["address"].is_null());
    assert!(
        snapshot["status"]["error"]
            .as_str()
            .is_some_and(|error| error.contains("transport")),
        "{}",
        snapshot["status"]["error"]
    );
    server.abort();
    core.shutdown().await;
}

#[tokio::test]
async fn tailcat_transport_without_an_adapter_stays_local() {
    // No adapter sits beside the test binary, so the route cannot come up: the
    // engine must refuse to serve instead of leaving an unpublishable listener.
    let dir = tempfile::tempdir().unwrap();
    roboco_engine::remote_access::save(
        dir.path(),
        &RemoteAccessSettings {
            enabled: true,
            bind_address: "127.0.0.1:0".parse().unwrap(),
            transport: NetworkTransport::Tailcat,
            ..Default::default()
        },
    )
    .unwrap();
    let (core, rpc, server) = engine(dir.path(), NetworkOptions::default()).await;
    let snapshot = rpc
        .call(methods::GET_REMOTE_ACCESS, json!({}))
        .await
        .unwrap();
    assert_eq!(snapshot["status"]["enabled"], false);
    assert!(snapshot["status"]["address"].is_null());
    assert!(
        snapshot["status"]["error"]
            .as_str()
            .is_some_and(|error| error.starts_with("Tailcat route unavailable")),
        "{}",
        snapshot["status"]["error"]
    );
    server.abort();
    core.shutdown().await;
}
