//! Offline Mimir native client fixture. Run on a dedicated display with
//! `cargo run -p roboco-ui --example native-fixture --features native-fixture`.
//! `ROBOCO_NATIVE_SCENE` picks plan (default), input, goal or recovery.
use gpui::{AppContext, Bounds, WindowBounds, WindowOptions, px, size};
use roboco_ui::*;
use serde_json::json;

fn native_state(scene: &str) -> roboco_proto::NativeChatState {
    let mut state = json!({
        "conversation": { "id": "conv-1", "cwd": "/tmp/fieldnotes" },
        "link": { "state": "attached" },
        "recovering": false,
        "configuration": { "provider": "anthropic", "model": "claude-sonnet", "reasoning": "high", "mode": "build" },
        "activeRequest": null, "requests": [], "plan": null, "goal": null,
        "userRequest": null, "submissions": [],
        "children": [{
            "handle": "agent-1", "attempt": 2, "profile": "explore",
            "description": "Map the auth module", "model": "anthropic/claude-sonnet",
            "status": "running", "background": true, "spawnedBy": "inv-1",
            "completionPending": false, "presentation": null,
            "docId": "native-child-doc", "oversized": false,
            "attempts": [
                { "attempt": 1, "status": "failed", "presentation": null, "outcomeRef": null },
                { "attempt": 2, "status": "running", "presentation": null, "outcomeRef": null }
            ]
        }]
    });
    match scene {
        "input" => {
            state["activeRequest"] = json!("req-1");
            state["userRequest"] = json!({ "id": "ur-7", "questions": [{
                "id": "q-db", "prompt": "Which databases should the retries cover?", "allowMultiple": true,
                "options": [
                    { "label": "Postgres", "description": "Retries wrap each transaction." },
                    { "label": "SQLite", "description": "Retries only on SQLITE_BUSY." }
                ]
            }]});
        }
        "goal" => {
            state["goal"] = json!({
                "id": "g1", "objective": "Ship the retry migration", "phase": "blocked",
                "cause": "reviewGap", "reason": "Needs a staging key",
                "reviewGap": "No rollback test exists", "workTurns": 4,
                "startedAt": "2026-10-01T00:00:00Z", "completion": null
            });
        }
        "recovery" => {
            state["recovering"] = json!(true);
            state["activeRequest"] = json!("req-1");
            state["link"] =
                json!({ "state": "interrupted", "message": "The Mimir process exited" });
            state["submissions"] = json!([
                { "messageId": "m-steer", "kind": "steer", "delivery": { "state": "unknown", "message": "The connection dropped." }, "retryable": false },
                { "messageId": "m-prompt", "kind": "prompt", "delivery": { "state": "refused", "message": "Mimir is busy." }, "retryable": true }
            ]);
        }
        _ => {
            state["plan"] =
                json!({ "id": "plan-3", "name": "Refactor auth", "status": "reviewPending" })
        }
    }
    serde_json::from_value(state).expect("fixture state matches the wire type")
}

fn transcript() -> Vec<roboco_doc::SessionMessageEntry> {
    vec![
                serde_json::from_value(json!({"id":"u1","role":"user","createdAt":1000,"deviceId":"d","status":null,"parts":[{"kind":"text","id":"u1t","text":"Refactor the auth module."}]})).unwrap(),
                serde_json::from_value(json!({"id":"a1","role":"assistant","createdAt":2000,"deviceId":"d","status":null,"parts":[
                    {"kind":"text","id":"a1t","text":"I mapped the entry points and drafted a plan."},
                    {"kind":"notice","id":"n1","notice":{"notice":"planLifecycle","planId":"plan-3","name":"Refactor auth","status":"reviewPending"}},
                    {"kind":"notice","id":"n2","notice":{"notice":"status","text":"Retrying in 4 s"}}
                ]})).unwrap(),
            ]
}

fn main() -> anyhow::Result<()> {
    let runtime = tokio::runtime::Runtime::new()?;
    let _guard = runtime.enter();
    tracing_subscriber::fmt().with_env_filter("warn").init();
    let temp = tempfile::tempdir()?;
    let data = temp.path().to_path_buf();
    let scene = std::env::var("ROBOCO_NATIVE_SCENE").unwrap_or_default();
    gpui_platform::application().with_assets(icons::Assets).run(move |cx| {
        gpui_tokio::init(cx); gpui_base::init(cx);
        let settings = settings::UiSettings::default();
        settings.save(&data).unwrap();
        settings::init(settings.clone(), data.clone(), cx);
        let fonts = typography::register_fonts(cx);
        typography::init(settings.ui_font_family.clone(), settings.ui_font_size, settings.terminal_font_family.clone(), settings.terminal_font_size, settings.code_font_family.clone(), settings.code_font_size, fonts, cx);
        theme_library::init(data.clone(), cx);
        appearance::init(appearance::AppearanceMode::Dark, settings.theme_selection, settings.accent, settings.surface, cx);
        history::init(settings.git_history_columns, settings.git_history_column_widths,
            settings.git_history_column_order, settings.git_history_author_display, cx);
        composer::init(cx, settings.composer_send_behavior); terminal::panel::init(cx); app_menus::init(cx);
        let state = cx.new(|_| {
            let mut s = state::AppState::new();
            s.connection = roboco_proto::view::ConnectionStatus::Ready;
            s.workspace_scope = Some(roboco_proto::WorkspaceScope::Local);
            s.local_device_id = Some("local".into());
            s.devices = vec![serde_json::from_value(json!({"id":"local","name":"This device","platform":std::env::consts::OS,"lastSeenAt":null})).unwrap()];
            s.selected_chat = Some("native-fixture".into()); s.selected_space = Some("project".into());
            s.auto_selected = true; s.chats_synced = true; s.spaces_synced = true;
            s.spaces = vec![serde_json::from_value(json!({"id":"project","deviceId":"local","path":"/tmp/fieldnotes","createdAt":"2026-09-08T00:00:00Z"})).unwrap()];
            s.chats = vec![serde_json::from_value(json!({"id":"native-fixture","deviceId":"local","spaceId":"project","title":"Refactor auth with Mimir","archived":false,"createdAt":"2026-09-08T00:00:00Z","config":{"harness":"mimir","model":null,"reasoning":null,"sandbox":"workspace-write"}})).unwrap()];
            s.transcript = transcript();
            s.native = Some(native_state(&scene));
            s
        });
        let boot = EngineBootConfig { data_dir: data, ipc_port: 0, default_harness: HarnessId::ClaudeCode };
        let _window = cx.open_window(WindowOptions {
            window_bounds: Some(WindowBounds::Windowed(Bounds::new(gpui::point(px(12.), px(30.)), size(px(1100.), px(800.))))),
            ..Default::default()
        }, |_, cx| cx.new(|cx| shell::Shell::new(state.clone(), boot, cx))).unwrap();
        state.update(cx, |_, cx| cx.notify());
        cx.activate(true);
    });
    Ok(())
}

#[cfg(test)]
mod tests {
    #[test]
    fn every_scene_parses_into_the_wire_state() {
        for scene in ["plan", "input", "goal", "recovery"] {
            let state = super::native_state(scene);
            assert_eq!(state.children.len(), 1, "{scene}");
            assert_eq!(super::transcript().len(), 2);
        }
    }
}
