use std::{sync::Arc, time::Duration};

use base64::{Engine as _, engine::general_purpose::STANDARD};
use roboco_engine::{EngineCore, EngineProfile, HarnessRegistry, pairing::PairingStore};
use roboco_rpc::{connect_ws, connect_ws_authenticated, memory_client, methods};
use serde_json::{Value, json};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;

/// The HTTP status a WebSocket upgrade to `url` gets: 101 when it upgrades,
/// otherwise the refusal's status.
async fn upgrade_status(url: &str, bearer: Option<&str>, origin: Option<&str>) -> u16 {
    let mut request = url.into_client_request().unwrap();
    if let Some(bearer) = bearer {
        request
            .headers_mut()
            .insert("authorization", format!("Bearer {bearer}").parse().unwrap());
    }
    if let Some(origin) = origin {
        request
            .headers_mut()
            .insert("origin", origin.parse().unwrap());
    }
    match tokio_tungstenite::connect_async(request).await {
        Ok((_, response)) => response.status().as_u16(),
        Err(tokio_tungstenite::tungstenite::Error::Http(response)) => response.status().as_u16(),
        Err(other) => panic!("upgrade failed below HTTP: {other:?}"),
    }
}

/// Generated media reads the same on the local in-process surface and over a
/// paired peer connection, and with no path jail the agent-owned source path
/// reads over the paired connection too.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn generated_media_reads_match_across_local_and_paired_rpc() {
    let dir = tempfile::tempdir().unwrap();
    let core = EngineCore::assemble_with_profile(
        EngineProfile::local(dir.path()).unwrap(),
        Arc::new(HarnessRegistry::new()),
        roboco_engine::HarnessId::Mock,
    )
    .unwrap();
    let generated_root = dir.path().join("generated_images");
    std::fs::create_dir_all(&generated_root).unwrap();
    let source = generated_root.join("codex.png");
    let payload = b"\x89PNG\r\n\x1a\nremote-generated-image";
    std::fs::write(&source, payload).unwrap();
    let image = core
        .uploads
        .import_generated_image(&source, &generated_root, "chat-remote\0image")
        .unwrap();
    let local = memory_client(core.rpc_service());
    let local_image = local
        .call(
            methods::READ_ATTACHMENT_CHUNK,
            json!({"path": image.path, "offset": 0}),
        )
        .await
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
    let remote_image = rpc
        .call(
            methods::READ_ATTACHMENT_CHUNK,
            json!({"path": image.path, "offset": 0, "targetDeviceId": core.device_id}),
        )
        .await
        .unwrap();
    assert_eq!(remote_image, local_image);
    assert_eq!(remote_image["mimeType"], "image/png");
    assert_eq!(remote_image["done"], true);
    let source_read = rpc
        .call(
            methods::READ_ATTACHMENT_CHUNK,
            json!({"path": source, "offset": 0, "targetDeviceId": core.device_id}),
        )
        .await
        .unwrap();
    assert_eq!(
        STANDARD.decode(source_read["data"].as_str().unwrap()).unwrap(),
        payload
    );
    let image_read = rpc
        .call(
            methods::READ_IMAGE_CHUNK,
            json!({"reference": source, "offset": 0, "targetDeviceId": core.device_id}),
        )
        .await
        .unwrap();
    assert_eq!(image_read["data"], source_read["data"]);
    drop(rpc);
    drop(remote);
    core.shutdown().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn paired_bind_enforces_sessions_and_preserves_rpc_capabilities() {
    let dir = tempfile::tempdir().unwrap();
    let folder = dir.path().join("project");
    std::fs::create_dir(&folder).unwrap();
    std::fs::write(folder.join("note.txt"), "engine-local content").unwrap();
    let core = EngineCore::assemble_with_profile(
        EngineProfile::local(dir.path()).unwrap(),
        Arc::new(HarnessRegistry::new()),
        roboco_engine::HarnessId::Mock,
    )
    .unwrap();
    core.workspace
        .create_space(
            "space",
            &core.device_id,
            &folder.to_string_lossy(),
            None,
            false,
        )
        .unwrap();
    core.workspace
        .create_chat("chat", Some("space"), None, None, None)
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
    assert_eq!(
        http.get(format!("{base}/health"))
            .send()
            .await
            .unwrap()
            .status(),
        401
    );
    // A credential-less dial is refused at the upgrade (ADR 0012: no
    // first-frame fallback).
    assert_eq!(upgrade_status(&url, None, None).await, 401);
    assert!(connect_ws(&url).await.is_err());
    assert!(connect_ws_authenticated(&url, "wrong").await.is_err());
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
    let session_id = grant["session"]["id"].as_str().unwrap();
    assert_eq!(
        http.get(format!("{base}/health"))
            .bearer_auth(credential)
            .send()
            .await
            .unwrap()
            .status(),
        200
    );
    let rpc = connect_ws_authenticated(&url, credential).await.unwrap();
    let info = rpc.call(methods::ENGINE_INFO, json!({})).await.unwrap();
    assert_eq!(info["deviceId"], core.device_id);
    let read = rpc
        .call(
            methods::READ_WORKSPACE_FILE,
            json!({"chatId":"chat", "path":"note.txt"}),
        )
        .await
        .unwrap();
    assert_eq!(read["text"], "engine-local content");
    let mut transcript = rpc
        .subscribe(methods::WATCH_DOC_MESSAGES, json!({"chatId":"chat"}))
        .await
        .unwrap();
    assert!(
        tokio::time::timeout(Duration::from_secs(5), transcript.recv())
            .await
            .unwrap()
            .is_some()
    );
    let mut queue = rpc
        .subscribe(methods::WATCH_QUEUE, json!({"chatId":"chat"}))
        .await
        .unwrap();
    assert!(
        tokio::time::timeout(Duration::from_secs(5), queue.recv())
            .await
            .unwrap()
            .is_some()
    );
    let terminal = rpc
        .call(
            methods::OPEN_TERMINAL,
            json!({"chatId":"chat","cols":80,"rows":24}),
        )
        .await
        .unwrap();
    let terminal_id = terminal["id"].as_str().unwrap();
    rpc.call(
        methods::WRITE_TERMINAL,
        json!({"terminalId":terminal_id, "data":STANDARD.encode("echo remote-listener\r\n")}),
    )
    .await
    .unwrap();
    rpc.call(methods::CLOSE_TERMINAL, json!({"terminalId":terminal_id}))
        .await
        .unwrap();
    assert!(
        rpc.call(
            methods::READ_WORKSPACE_FILE,
            json!({"chatId":"chat","path":"note.txt","targetDeviceId":"another-engine"})
        )
        .await
        .is_err()
    );
    assert!(store.revoke(session_id).unwrap());
    assert!(connect_ws_authenticated(&url, credential).await.is_err());
    assert_eq!(
        http.get(format!("{base}/health"))
            .bearer_auth(credential)
            .send()
            .await
            .unwrap()
            .status(),
        401
    );
    // Disabling a bind also closes already-authorized connections and streams.
    drop(remote);
    assert!(
        tokio::time::timeout(Duration::from_secs(5), transcript.recv())
            .await
            .unwrap()
            .is_none()
    );
    assert!(
        tokio::time::timeout(
            Duration::from_secs(5),
            rpc.call(methods::ENGINE_INFO, json!({}))
        )
        .await
        .unwrap()
        .is_err()
    );
    drop(queue);
    drop(rpc);
    core.shutdown().await;
}

/// The paired bind serves no web pages (ADR 0012): `/` and `/pair` are 404
/// like any unknown path, with or without a session, and `/health` keeps its
/// session gate.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn paired_bind_serves_no_pages() {
    let dir = tempfile::tempdir().unwrap();
    let core = EngineCore::assemble_with_profile(
        EngineProfile::local(dir.path()).unwrap(),
        Arc::new(HarnessRegistry::new()),
        roboco_engine::HarnessId::Mock,
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
    let http = reqwest::Client::new();
    for path in ["/", "/pair", "/index.html", "/assets/app.js", "/chats/abc"] {
        assert_eq!(
            http.get(format!("{base}{path}")).send().await.unwrap().status(),
            404,
            "{path}"
        );
    }
    assert_eq!(
        http.get(format!("{base}/health")).send().await.unwrap().status(),
        401
    );
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
    for path in ["/", "/pair"] {
        assert_eq!(
            http.get(format!("{base}{path}"))
                .bearer_auth(credential)
                .send()
                .await
                .unwrap()
                .status(),
            404,
            "{path} with a session"
        );
    }
    assert_eq!(
        http.get(format!("{base}/health"))
            .bearer_auth(credential)
            .send()
            .await
            .unwrap()
            .status(),
        200
    );
    drop(remote);
    core.shutdown().await;
}

/// Browser-shaped requests are refused on the paired bind (ADR 0012): any
/// `Origin` is a 403 on every route, redeem and room presentation included;
/// `OPTIONS` is not special and carries no CORS headers; the removed browser
/// routes are 404; a Bearer-less upgrade is a 401. The Local bind keeps its
/// own Origin rule unchanged.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn paired_bind_refuses_browser_shaped_requests() {
    let dir = tempfile::tempdir().unwrap();
    let core = EngineCore::assemble_with_profile(
        EngineProfile::local(dir.path()).unwrap(),
        Arc::new(HarnessRegistry::new()),
        roboco_engine::HarnessId::Mock,
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
    let origin = "https://browser.example";

    // Origin on the redeem-style routes: refused before the code is spent.
    let code = store.create_code("browser", 300).unwrap();
    let refused = http
        .post(format!("{base}/pairing/redeem"))
        .header("origin", origin)
        .bearer_auth(&code.credential)
        .json(&json!({"label":"Browser"}))
        .send()
        .await
        .unwrap();
    assert_eq!(refused.status(), 403);
    assert!(!refused.headers().contains_key("access-control-allow-origin"));
    assert_eq!(
        http.post(format!("{base}/room/present"))
            .header("origin", origin)
            .json(&json!({"secret":"x","label":"Browser"}))
            .send()
            .await
            .unwrap()
            .status(),
        403
    );
    // The same code still redeems natively: the refusal did not consume it.
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

    // Origin with a valid session: still refused, HTTP and upgrade alike.
    assert_eq!(
        http.get(format!("{base}/health"))
            .header("origin", origin)
            .bearer_auth(credential)
            .send()
            .await
            .unwrap()
            .status(),
        403
    );
    assert_eq!(upgrade_status(&url, Some(credential), Some(origin)).await, 403);
    assert_eq!(upgrade_status(&url, Some(credential), None).await, 101);

    // Preflights: with an Origin they are refused like anything else;
    // without one OPTIONS is just an unknown route. Neither carries CORS.
    for path in ["/pairing/redeem", "/room/present"] {
        let preflight = http
            .request(reqwest::Method::OPTIONS, format!("{base}{path}"))
            .header("origin", origin)
            .header("access-control-request-method", "POST")
            .send()
            .await
            .unwrap();
        assert_eq!(preflight.status(), 403, "{path}");
        assert!(!preflight.headers().contains_key("access-control-allow-origin"));
        let bare = http
            .request(reqwest::Method::OPTIONS, format!("{base}{path}"))
            .send()
            .await
            .unwrap();
        assert_eq!(bare.status(), 404, "{path}");
        assert!(!bare.headers().contains_key("access-control-allow-origin"));
    }

    // The browser-only routes are gone.
    for path in ["/pairing/tailcat-redeem", "/pairing/room-redeem"] {
        assert_eq!(
            http.post(format!("{base}{path}"))
                .json(&json!({}))
                .send()
                .await
                .unwrap()
                .status(),
            404,
            "{path}"
        );
    }
    assert_eq!(
        http.get(format!("{base}/tailcat-relay/x"))
            .bearer_auth(credential)
            .send()
            .await
            .unwrap()
            .status(),
        404
    );
    assert_eq!(upgrade_status(&format!("{url}/tailcat-relay/x"), None, None).await, 404);

    // A Bearer-less upgrade is a 401, before any upgrade.
    assert_eq!(upgrade_status(&url, None, None).await, 401);
    assert_eq!(
        upgrade_status(&format!("{url}/preview-relay/chat/service"), None, None).await,
        401
    );
    drop(remote);
    core.shutdown().await;
}
