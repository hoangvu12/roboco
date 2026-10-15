//! The actual CLI can mutate pairing rows while an engine owns its instance lock.
use roboco_engine::{EngineCore, EngineProfile, HarnessId, HarnessRegistry};
use serde_json::{Value, json};
use std::sync::Arc;

fn cli(dir: &std::path::Path, arguments: &[&str]) -> std::process::Output {
    let output = std::process::Command::new(env!("CARGO_BIN_EXE_roboco"))
        .env("ROBOCO_DATA_DIR", dir)
        .args(["engine", "pairing"])
        .args(arguments)
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "CLI failed: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    output
}

#[tokio::test]
async fn second_process_mints_lists_and_revokes_against_running_engine() {
    let dir = tempfile::tempdir().unwrap();
    let core = EngineCore::assemble_with_profile(
        EngineProfile::local(dir.path()).unwrap(),
        Arc::new(HarnessRegistry::new()),
        HarnessId::Mock,
    )
    .unwrap();
    let mut remote = roboco_engine::serve_engine_remote(
        "127.0.0.1:0".parse().unwrap(),
        core.rpc_service(),
        dir.path(),
    )
    .await
    .unwrap();
    let base = format!("http://{}", remote.address);
    let created: Value = serde_json::from_slice(
        &cli(
            dir.path(),
            &[
                "create",
                "--base-url",
                &base,
                "--label",
                "Desktop",
                "--json",
            ],
        )
        .stdout,
    )
    .unwrap();
    let url = reqwest::Url::parse(created["url"].as_str().unwrap()).unwrap();
    let code = url.fragment().unwrap().strip_prefix("token=").unwrap();
    let http = reqwest::Client::new();
    let response = http
        .post(format!("{base}/pairing/redeem"))
        .bearer_auth(code)
        .json(&json!({}))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
    let grant: Value = response.json().await.unwrap();
    let id = grant["session"]["id"].as_str().unwrap();
    let credential = grant["credential"].as_str().unwrap();
    let listed = cli(dir.path(), &["list", "--json"]);
    let rows: Value = serde_json::from_slice(&listed.stdout).unwrap();
    assert_eq!(rows[0]["id"], id);
    assert_eq!(rows[0]["label"], "Desktop");
    let listed = String::from_utf8(listed.stdout).unwrap();
    assert!(!listed.contains(code) && !listed.contains(credential));
    // A minted session authenticates over the paired WebSocket boundary.
    let ws = base.replacen("http", "ws", 1);
    let rpc = roboco_rpc::connect_ws_authenticated(&ws, credential)
        .await
        .unwrap();
    rpc.call(roboco_rpc::methods::ENGINE_INFO, json!({}))
        .await
        .unwrap();
    drop(rpc);
    cli(dir.path(), &["revoke", id]);
    assert!(
        roboco_rpc::connect_ws_authenticated(&ws, credential)
            .await
            .is_err()
    );
    remote.stop().await;
    core.shutdown().await;
}

#[test]
fn pairing_create_qr_prints_under_the_url_and_plain_output_stays_byte_stable() {
    let dir = tempfile::tempdir().unwrap();
    // Without --qr the output is exactly what it always was: the URL line
    // and the expiry line.
    let plain = String::from_utf8(
        cli(dir.path(), &["create", "--base-url", "http://127.0.0.1:27655"]).stdout,
    )
    .unwrap();
    let lines: Vec<&str> = plain.lines().collect();
    assert_eq!(lines.len(), 2, "two lines, nothing more: {plain:?}");
    assert!(lines[0].starts_with("http://127.0.0.1:27655/pair#token="));
    assert!(lines[1].starts_with("Expires at "));
    assert!(lines[1].ends_with("Use this link once to pair a client."));

    // With --qr the terminal QR sits between the URL and the expiry line.
    let with_qr = String::from_utf8(
        cli(dir.path(), &["create", "--base-url", "http://127.0.0.1:27655", "--qr"]).stdout,
    )
    .unwrap();
    let lines: Vec<&str> = with_qr.lines().collect();
    assert!(lines[0].starts_with("http://127.0.0.1:27655/pair#token="));
    let qr_lines = &lines[1..lines.len() - 1];
    assert!(qr_lines.len() > 8, "a glyph row per two modules: {with_qr:?}");
    assert!(
        qr_lines
            .iter()
            .all(|line| line.chars().all(|ch| matches!(ch, '█' | '▀' | '▄' | ' '))),
        "half-block QR only: {with_qr:?}"
    );
    assert!(lines[lines.len() - 1].starts_with("Expires at "));
}

#[test]
fn pairing_create_json_gains_qrsvg_only_with_qr() {
    let dir = tempfile::tempdir().unwrap();
    let plain: Value = serde_json::from_slice(
        &cli(dir.path(), &["create", "--base-url", "http://127.0.0.1:27655", "--json"]).stdout,
    )
    .unwrap();
    let mut keys: Vec<&str> = plain.as_object().unwrap().keys().map(|k| k.as_str()).collect();
    keys.sort_unstable();
    assert_eq!(keys, vec!["expiresAt", "id", "url"], "JSON stays stable without --qr");

    let with_qr: Value = serde_json::from_slice(
        &cli(
            dir.path(),
            &["create", "--base-url", "http://127.0.0.1:27655", "--json", "--qr"],
        )
        .stdout,
    )
    .unwrap();
    let qr_svg = with_qr["qrSvg"].as_str().expect("qrSvg with --qr");
    assert!(qr_svg.contains("shape-rendering=\"crispEdges\""));
    assert!(qr_svg.contains("<svg"));
}
