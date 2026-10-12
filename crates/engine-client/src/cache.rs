//! Disposable client-side history. Keys are engine-scoped; credentials never enter this store.
//! Filesystem writes belong on a background task, not the GPUI event loop.

use std::{
    io,
    io::Write,
    path::{Path, PathBuf},
};

use roboco_doc::SessionMessageEntry;
use roboco_proto::{Chat, Device, Session, Space};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use sha2::{Digest, Sha256};

#[derive(Clone, Default, Serialize, Deserialize)]
pub struct CachedRows {
    pub chats: Vec<Chat>,
    pub spaces: Vec<Space>,
    pub devices: Vec<Device>,
    pub sessions: Vec<Session>,
}

#[derive(Clone)]
pub struct EngineCache {
    root: PathBuf,
}

impl EngineCache {
    pub fn new(data_dir: &Path) -> Self {
        Self {
            root: data_dir.join("engine-cache-v1"),
        }
    }

    fn engine_dir(&self, engine_key: &str) -> PathBuf {
        self.root.join(digest(engine_key))
    }

    pub fn load_rows(&self, engine_key: &str) -> Option<CachedRows> {
        read(&self.engine_dir(engine_key).join("rows.json"))
    }

    pub fn save_rows(&self, engine_key: &str, rows: &CachedRows) -> io::Result<()> {
        write(&self.engine_dir(engine_key).join("rows.json"), rows)
    }

    pub fn load_transcript(
        &self,
        engine_key: &str,
        chat_id: &str,
    ) -> Option<Vec<SessionMessageEntry>> {
        read(&self.transcript_path(engine_key, chat_id))
    }

    pub fn save_transcript(
        &self,
        engine_key: &str,
        chat_id: &str,
        entries: &[SessionMessageEntry],
    ) -> io::Result<()> {
        write(&self.transcript_path(engine_key, chat_id), entries)
    }

    fn transcript_path(&self, engine_key: &str, chat_id: &str) -> PathBuf {
        self.engine_dir(engine_key)
            .join(format!("chat-{}.json", digest(chat_id)))
    }

    pub fn forget_engine(&self, engine_key: &str) -> io::Result<()> {
        match std::fs::remove_dir_all(self.engine_dir(engine_key)) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(error),
        }
    }
}

fn digest(key: &str) -> String {
    format!("{:x}", Sha256::digest(key.as_bytes()))
}

fn read<T: DeserializeOwned>(path: &Path) -> Option<T> {
    // This is a cache, so a partial/obsolete file is a miss. Engine data remains authoritative.
    serde_json::from_slice(&std::fs::read(path).ok()?).ok()
}

fn write<T: Serialize + ?Sized>(path: &Path, value: &T) -> io::Result<()> {
    let parent = path
        .parent()
        .ok_or_else(|| io::Error::other("invalid cache path"))?;
    std::fs::create_dir_all(parent)?;
    let mut temporary = tempfile::NamedTempFile::new_in(parent)?;
    serde_json::to_writer(temporary.as_file_mut(), value)?;
    temporary.flush()?;
    temporary.as_file().sync_all()?;
    temporary.persist(path).map_err(|error| error.error)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use roboco_rpc::methods;
    use serde_json::json;
    use std::{sync::Arc, time::Duration};

    #[tokio::test]
    async fn cached_listener_history_survives_offline_restart_and_is_engine_scoped() {
        let engine_dir = tempfile::tempdir().unwrap();
        let client_dir = tempfile::tempdir().unwrap();
        let core = roboco_engine::EngineCore::assemble_with_profile(
            roboco_engine::EngineProfile::local(engine_dir.path()).unwrap(),
            Arc::new(roboco_engine::HarnessRegistry::new()),
            roboco_proto::HarnessId::Mock,
        )
        .unwrap();
        core.workspace
            .create_chat("same-chat", None, Some(&core.device_id), None, None)
            .unwrap();
        core.doc_host
            .open("same-chat")
            .unwrap()
            .write_user_message("message", "cached history", 123)
            .unwrap();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("ws://{}", listener.local_addr().unwrap());
        let server = tokio::spawn(roboco_engine::listener::serve_listener(
            listener,
            core.rpc_service(),
            roboco_engine::pairing::PairingStore::open(engine_dir.path()).unwrap(),
        ));
        let client = roboco_rpc::connect_ws(&url).await.unwrap();
        let mut chat_stream = client
            .subscribe(methods::WATCH_CHATS, json!({}))
            .await
            .unwrap();
        // The opening frame can predate the workspace's first publish; wait
        // for the frame that actually carries the created chat.
        let chats: Vec<Chat> = tokio::time::timeout(Duration::from_secs(5), async {
            loop {
                let frame = chat_stream.recv().await.unwrap();
                let rows: Vec<Chat> = serde_json::from_value(frame).unwrap();
                if !rows.is_empty() {
                    break rows;
                }
            }
        })
        .await
        .unwrap();
        let mut transcript = client
            .subscribe(methods::WATCH_DOC_MESSAGES, json!({"chatId":"same-chat"}))
            .await
            .unwrap();
        let update: roboco_doc::transcript_delta::TranscriptUpdate = serde_json::from_value(
            tokio::time::timeout(Duration::from_secs(5), transcript.recv())
                .await
                .unwrap()
                .unwrap(),
        )
        .unwrap();
        let roboco_doc::transcript_delta::TranscriptFrame::Reset { reset: entries } = update.frame
        else {
            panic!("initial transcript must reset")
        };
        assert_eq!(entries.len(), 1);
        let cache = EngineCache::new(client_dir.path());
        cache
            .save_rows(
                "first",
                &CachedRows {
                    chats,
                    ..Default::default()
                },
            )
            .unwrap();
        cache
            .save_transcript("first", "same-chat", &entries)
            .unwrap();
        cache.save_transcript("second", "same-chat", &[]).unwrap();
        server.abort();
        drop(client);
        core.shutdown().await;
        drop(core);
        drop(cache);
        let reopened = EngineCache::new(client_dir.path());
        assert_eq!(
            reopened.load_rows("first").unwrap().chats[0].id,
            "same-chat"
        );
        assert_eq!(
            reopened.load_transcript("first", "same-chat").unwrap()[0].id,
            "message"
        );
        assert!(
            reopened
                .load_transcript("second", "same-chat")
                .unwrap()
                .is_empty()
        );
        reopened.forget_engine("first").unwrap();
        assert!(reopened.load_rows("first").is_none());
        assert!(reopened.load_transcript("first", "same-chat").is_none());
        assert!(reopened.load_transcript("second", "same-chat").is_some());
    }
}
