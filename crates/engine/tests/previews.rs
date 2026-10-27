use futures::StreamExt;
use roboco_engine::{EngineCore, HarnessRegistry};
use roboco_proto::{HarnessId, PreviewService};
use roboco_rpc::{RpcReply, RpcService, methods};
use std::{sync::Arc, time::Duration};

fn service(device: &str, cwd: &std::path::Path, id: &str) -> PreviewService {
    PreviewService {
        id: id.into(),
        project_id: "project".into(),
        project_name: "project".into(),
        project_cwd: cwd.to_string_lossy().into_owned(),
        device_id: device.into(),
        device_name: device.into(),
        hostname: format!("{device}.{id}.localhost"),
        name: "Vite".into(),
        port: 5173,
        pid: 123,
        cwd: cwd.to_string_lossy().into_owned(),
        started_at: 1,
        roboco_owned: true,
        chat_id: None,
    }
}
#[tokio::test]
async fn preview_watch_follows_the_session_checkout_and_owning_device() {
    let temp = tempfile::tempdir().unwrap();
    let a = temp.path().join("a");
    let b = temp.path().join("b");
    std::fs::create_dir(&a).unwrap();
    std::fs::create_dir(&b).unwrap();
    let core = EngineCore::assemble(
        &temp.path().join("engine"),
        Arc::new(HarnessRegistry::new()),
        HarnessId::Mock,
    )
    .unwrap();
    // Roboco's engine-local routing: the watch rides the caller's own engine
    // connection, so the chat is one THIS engine hosts (upstream's version
    // watched a peer device's chat through a `targetDeviceId` forward, which
    // the routing guard rejects here).
    core.workspace
        .create_chat(
            "chat",
            None,
            Some(core.device_id.as_str()),
            None,
            Some(a.to_string_lossy().into_owned()),
        )
        .unwrap();
    let catalog = core.previews.catalog();
    // What this engine's discovery observed in checkout `a` (attributed by
    // cwd), and what another device advertised: the owning-device filter
    // keeps the peer's services out of this chat's list.
    catalog
        .replace_local(vec![(a.clone(), listener(&a, "a"))])
        .unwrap();
    catalog
        .set_remote(
            "peer",
            vec![service("peer", &a, "peer-a"), service("peer", &b, "peer-b")],
        )
        .unwrap();
    let rpc = core.rpc_service();
    let RpcReply::Stream(mut stream) = rpc
        .handle(
            methods::WATCH_PREVIEWS,
            serde_json::json!({"chatId":"chat"}),
        )
        .await
        .unwrap()
    else {
        panic!("expected preview watch")
    };
    // The catalog assigns its own persisted service id; the checkout is the
    // stable thing the watch follows.
    let first = stream.next().await.unwrap();
    assert_eq!(first["services"].as_array().unwrap().len(), 1);
    assert_eq!(
        first["services"][0]["cwd"].as_str().unwrap(),
        a.to_string_lossy()
    );
    assert_eq!(first["remote"], false);
    // Following the checkout: moving the chat to `b` drops `a`'s server.
    core.workspace
        .set_chat_cwd("chat", &b.to_string_lossy())
        .unwrap();
    let next = tokio::time::timeout(Duration::from_secs(2), stream.next())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(next["services"], serde_json::json!([]));
    catalog.replace_local(vec![(b.clone(), listener(&b, "b"))]).unwrap();
    let next = tokio::time::timeout(Duration::from_secs(2), stream.next())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(next["services"].as_array().unwrap().len(), 1);
    assert_eq!(next["services"][0]["cwd"].as_str().unwrap(), b.to_string_lossy());
    catalog.replace_local(Vec::new()).unwrap();
    let next = tokio::time::timeout(Duration::from_secs(2), stream.next())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(next["services"], serde_json::json!([]));
    drop(stream);
    core.shutdown().await;
}

/// One row of what discovery would hand the catalog for a server in `cwd`:
/// Roboco-started (upstream #1023's hard attribution), attributed by its
/// checkout because the marker named no hosted chat.
fn listener(cwd: &std::path::Path, id: &str) -> roboco_preview::discovery::Listener {
    roboco_preview::discovery::Listener {
        pid: 1,
        parent: 1,
        cwd: cwd.to_owned(),
        args: vec!["node".into(), "vite".into()],
        started_at: 1,
        address: ([127, 0, 0, 1], 5173).into(),
        roboco_owned: true,
        origin: None,
        chat_id: None,
    }
}
