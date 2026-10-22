//! `Client::read_image` against a real paired engine: Markdown image
//! references the phone hands to the engine as written (`ReadImageChunk`).

mod support;

use std::sync::Arc;
use std::time::Duration;

use roboco_mobile_client::EngineState;
use support::{Recorder, core as engine_core, pairing_url, serve, test_client, wait_for_view};

const PNG: &[u8] = b"\x89PNG\r\n\x1a\nphone-image";

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn read_image_resolves_references_on_the_engine() {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let project = tempfile::tempdir().unwrap();
    std::fs::create_dir_all(project.path().join("shots")).unwrap();
    std::fs::write(project.path().join("shots").join("a b.png"), PNG).unwrap();
    let core = engine_core(engine_dir.path());
    core.workspace
        .create_chat(
            "image-chat",
            None,
            Some(&core.device_id),
            None,
            Some(project.path().to_string_lossy().into_owned()),
        )
        .unwrap();
    let listener = serve(&core, engine_dir.path()).await;
    let client = test_client(data_dir.path(), Vec::new(), Arc::new(Recorder::default()));
    let record = tokio::time::timeout(
        Duration::from_secs(10),
        client.pair_engine(&pairing_url(&listener, engine_dir.path(), None), None),
    )
    .await
    .expect("pairing completes")
    .expect("pairing succeeds");
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    let chat = client.scope(&record.engine_id, "image-chat");

    // Relative to the chat's cwd, in two spellings.
    for reference in ["shots/a b.png", "./shots/a%20b.png"] {
        let bytes = client
            .read_image(&record.engine_id, reference, Some(&chat))
            .await
            .unwrap_or_else(|e| panic!("{reference}: {e:?}"));
        assert_eq!(bytes, PNG, "{reference}");
    }
    // An absolute path outside every upload dir needs no chat.
    let absolute = project.path().join("shots").join("a b.png");
    let bytes = client
        .read_image(&record.engine_id, &absolute.to_string_lossy(), None)
        .await
        .unwrap();
    assert_eq!(bytes, PNG);
    // A missing file is an error, not empty bytes.
    assert!(
        client
            .read_image(&record.engine_id, "shots/missing.png", Some(&chat))
            .await
            .is_err()
    );
    drop(listener);
    core.shutdown().await;
}
