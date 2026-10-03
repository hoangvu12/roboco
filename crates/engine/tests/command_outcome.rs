use std::sync::Arc;

use roboco_doc::{SessionCommandPayload, SessionCommandStatus};
use roboco_engine::doc_host::{DocHost, DocHostConfig};
use roboco_proto::{HarnessId, NativeControl, NativeControlOutcome, NativeErrorKind};

#[tokio::test]
async fn reading_a_command_preserves_its_exact_status_and_chat_scope() {
    let dir = tempfile::tempdir().unwrap();
    let host = DocHost::new(
        Arc::new(roboco_sync::DocsStore::open(dir.path()).unwrap()),
        DocHostConfig {
            device_id: "test-device".into(),
            default_harness: HarnessId::Mimir,
        },
    );
    let id = host
        .queue_command(
            "first",
            SessionCommandPayload::Native {
                control: NativeControl::Reconnect,
            },
        )
        .unwrap();
    assert_eq!(
        host.command("first", &id).unwrap().unwrap().status,
        SessionCommandStatus::Pending
    );
    assert!(host.command("second", &id).unwrap().is_none());
    assert!(host.command("first", "absent").unwrap().is_none());

    let outcome = NativeControlOutcome::Refused {
        kind: NativeErrorKind::Busy,
        message: "The TUI owns this conversation.".into(),
    };
    host.open("first")
        .unwrap()
        .doc_arc()
        .set_command_outcome(
            &id,
            SessionCommandStatus::Rejected,
            Some("Busy"),
            Some(&outcome),
        )
        .unwrap();
    for _ in 0..2 {
        let read = host.command("first", &id).unwrap().unwrap();
        assert_eq!(read.status, SessionCommandStatus::Rejected);
        assert_eq!(read.outcome, Some(outcome.clone()));
        assert_eq!(read.resolution.as_deref(), Some("Busy"));
    }
    host.open("first")
        .unwrap()
        .doc_arc()
        .set_command_outcome(&id, SessionCommandStatus::Unknown, Some("Reply lost"), None)
        .unwrap();
    let read = host.command("first", &id).unwrap().unwrap();
    assert_eq!(read.status, SessionCommandStatus::Unknown);
    assert_eq!(read.resolution.as_deref(), Some("Reply lost"));
    assert_eq!(
        host.open("first")
            .unwrap()
            .doc_arc()
            .read_commands()
            .unwrap()
            .len(),
        1
    );
}
