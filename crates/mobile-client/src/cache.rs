//! The mobile offline cache: `rows.json`, `sidebar.json` and transcripts
//! per engine, written by one FIFO writer task with a per-engine debounce,
//! atomically, with credentials never on disk (they only ever live in
//! memory — the engine's ids and rows are the only bytes written).
//!
//! Layout and atomicy come from ticket 04's
//! `roboco_engine_client::cache::EngineCache` (not forked); this module adds
//! the writer, the debounce, the flush barrier and the mobile-only startup
//! prune (keep the 100 most recently modified transcripts; the desktop has
//! no bound).

use std::collections::VecDeque;
use std::time::Duration;

use roboco_doc::SessionMessageEntry;
use roboco_engine_client::cache::{CachedRows, CachedTranscript, EngineCache};
use roboco_proto::SidebarStateSnapshot;
use tokio::sync::{mpsc, oneshot};

/// Keep this many `chat-*.json` files per engine at startup; the rest are
/// deleted. A mobile-only bound.
pub const KEEP_TRANSCRIPTS: usize = 100;

/// One engine's cache, written through the client's FIFO writer. Cloning
/// hands out another handle to the same engine's files.
#[derive(Clone)]
pub struct EngineCacheHandle {
    engine_id: String,
    cache: EngineCache,
    tx: mpsc::UnboundedSender<CacheCommand>,
}

/// A save command's coalescing identity: the newest payload per key wins
/// the earlier slot's position, so the writer's FIFO order still holds.
#[derive(Clone, PartialEq)]
enum SaveKey {
    Rows(String),
    Sidebar(String),
    Transcript(String, String),
}

pub(crate) enum CacheCommand {
    SaveRows {
        engine: String,
        rows: CachedRows,
    },
    SaveSidebar {
        engine: String,
        sidebar: SidebarStateSnapshot,
    },
    SaveTranscript {
        engine: String,
        chat: String,
        /// Entries plus their ticket-12 digests (empty before the capability).
        state: CachedTranscript,
    },
    /// Prove every write queued before this point hit disk.
    Flush(oneshot::Sender<()>),
}

impl CacheCommand {
    fn key(&self) -> SaveKey {
        match self {
            CacheCommand::SaveRows { engine, .. } => SaveKey::Rows(engine.clone()),
            CacheCommand::SaveSidebar { engine, .. } => SaveKey::Sidebar(engine.clone()),
            CacheCommand::SaveTranscript { engine, chat, .. } => {
                SaveKey::Transcript(engine.clone(), chat.clone())
            }
            CacheCommand::Flush(_) => SaveKey::Rows(String::new()),
        }
    }
}

impl EngineCacheHandle {
    pub(crate) fn new(
        engine_id: String,
        cache: EngineCache,
        tx: mpsc::UnboundedSender<CacheCommand>,
    ) -> Self {
        Self {
            engine_id,
            cache,
            tx,
        }
    }

    /// Rows straight off disk (a cache miss reads as `None`).
    pub fn load_rows(&self) -> Option<CachedRows> {
        self.cache.load_rows(&self.engine_id)
    }

    pub fn load_sidebar(&self) -> Option<SidebarStateSnapshot> {
        self.cache.load_sidebar(&self.engine_id)
    }

    pub fn load_transcript(&self, chat_id: &str) -> Option<Vec<SessionMessageEntry>> {
        self.cache.load_transcript(&self.engine_id, chat_id)
    }

    /// Queue a rows write; the writer debounces bursts per engine (the
    /// newest frame wins) and writes atomically.
    pub fn save_rows(&self, rows: CachedRows) {
        let _ = self.tx.send(CacheCommand::SaveRows {
            engine: self.engine_id.clone(),
            rows,
        });
    }

    pub fn save_sidebar(&self, sidebar: SidebarStateSnapshot) {
        let _ = self.tx.send(CacheCommand::SaveSidebar {
            engine: self.engine_id.clone(),
            sidebar,
        });
    }

    /// Load a cached transcript: entries plus digests (either file format).
    pub fn load_transcript_state(&self, chat_id: &str) -> Option<CachedTranscript> {
        self.cache.load_transcript_state(&self.engine_id, chat_id)
    }

    /// The engine's cache directory on disk (the catalog's cache files live
    /// in `catalog/` under it — ticket 08).
    pub fn engine_dir(&self) -> std::path::PathBuf {
        self.cache.engine_directory(&self.engine_id)
    }

    /// Transcripts are written on eviction/quiet, not debounced per burst.
    pub fn save_transcript_state(&self, chat_id: &str, state: CachedTranscript) {
        let _ = self.tx.send(CacheCommand::SaveTranscript {
            engine: self.engine_id.clone(),
            chat: chat_id.to_owned(),
            state,
        });
    }

    /// Wait until everything queued before this call is on disk.
    pub async fn flush(&self) {
        let (done, receipt) = oneshot::channel();
        if self.tx.send(CacheCommand::Flush(done)).is_ok() {
            let _ = receipt.await;
        }
    }

    /// Remove this engine's whole cache directory. Only valid after the
    /// engine's tasks are stopped and [`Self::flush`] returned: an
    /// in-flight write would otherwise recreate the directory being
    /// removed (the desktop registry's ordering).
    pub(crate) fn forget(&self) -> std::io::Result<()> {
        self.cache.forget_engine(&self.engine_id)
    }
}

/// The process-wide writer: one FIFO queue, per-key newest-wins coalescing
/// inside a `debounce` window, atomic writes off the async runtime.
pub(crate) struct CacheWriter {
    cache: EngineCache,
    debounce: Duration,
}

impl CacheWriter {
    pub(crate) fn start(
        cache: EngineCache,
        debounce: Duration,
    ) -> mpsc::UnboundedSender<CacheCommand> {
        let (tx, rx) = mpsc::unbounded_channel();
        let writer = Self { cache, debounce };
        crate::runtime::shared().spawn(writer.drive(rx));
        tx
    }

    async fn drive(self, mut rx: mpsc::UnboundedReceiver<CacheCommand>) {
        while let Some(first) = rx.recv().await {
            let mut queue = VecDeque::from([first]);
            let debounce = self.debounce;
            let window = tokio::time::sleep(debounce);
            tokio::pin!(window);
            loop {
                tokio::select! {
                    command = rx.recv() => {
                        match command {
                            None => break,
                            Some(command @ CacheCommand::Flush(_)) => {
                                self.flush(&mut queue, Some(command)).await;
                                window.as_mut().reset(tokio::time::Instant::now() + debounce);
                            }
                            Some(command) => self.coalesce(&mut queue, command),
                        }
                    }
                    _ = &mut window => break,
                }
            }
            self.flush(&mut queue, None).await;
        }
    }

    /// Newest payload per key keeps the earlier position: saves coalesce,
    /// flush order is preserved.
    fn coalesce(&self, queue: &mut VecDeque<CacheCommand>, command: CacheCommand) {
        let key = command.key();
        for slot in queue.iter_mut() {
            if matches!(slot, CacheCommand::Flush(_)) {
                continue;
            }
            if slot.key() == key {
                *slot = command;
                return;
            }
        }
        queue.push_back(command);
    }

    async fn flush(&self, queue: &mut VecDeque<CacheCommand>, barrier: Option<CacheCommand>) {
        while let Some(command) = queue.pop_front() {
            self.write(command).await;
        }
        if let Some(CacheCommand::Flush(ack)) = barrier {
            let _ = ack.send(());
        }
    }

    async fn write(&self, command: CacheCommand) {
        let cache = self.cache.clone();
        let result = tokio::task::spawn_blocking(move || match command {
            CacheCommand::SaveRows { engine, rows } => cache.save_rows(&engine, &rows),
            CacheCommand::SaveSidebar { engine, sidebar } => cache.save_sidebar(&engine, &sidebar),
            CacheCommand::SaveTranscript {
                engine,
                chat,
                state,
            } => cache.save_transcript_state(&engine, &chat, &state),
            CacheCommand::Flush(_) => Ok(()),
        })
        .await;
        match result {
            Ok(Ok(())) => {}
            Ok(Err(error)) => {
                tracing::warn!(%error, "mobile client: cache write failed")
            }
            Err(error) => {
                tracing::warn!(%error, "mobile client: cache write task failed")
            }
        }
    }
}

/// Delete all but the [`KEEP_TRANSCRIPTS`] most recently modified
/// `chat-*.json` files of one engine. Run once at client startup, per
/// engine (best-effort: a failure to read a stat is a skip, never fatal).
pub(crate) fn prune_transcripts(cache: &EngineCache, engine_id: &str) {
    let dir = cache.engine_directory(engine_id);
    let Ok(entries) = std::fs::read_dir(&dir) else {
        return;
    };
    let mut files: Vec<(std::time::SystemTime, std::path::PathBuf)> = entries
        .filter_map(|entry| entry.ok())
        .filter(|entry| {
            entry.file_type().is_ok_and(|t| t.is_file())
                && entry
                    .file_name()
                    .to_str()
                    .is_some_and(|name| name.starts_with("chat-") && name.ends_with(".json"))
        })
        .filter_map(|entry| {
            let modified = entry.metadata().ok()?.modified().ok()?;
            Some((modified, entry.path()))
        })
        .collect();
    if files.len() <= KEEP_TRANSCRIPTS {
        return;
    }
    files.sort_by(|a, b| b.0.cmp(&a.0));
    for (_, path) in files.into_iter().skip(KEEP_TRANSCRIPTS) {
        if let Err(error) = std::fs::remove_file(&path) {
            tracing::warn!(%error, path = %path.display(), "mobile client: transcript prune failed");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn stub_chat(id: &str) -> roboco_proto::Chat {
        serde_json::from_value(serde_json::json!({
            "id": id,
            "deviceId": "dev",
            "title": null,
            "archived": false,
            "cwd": null,
            "branch": null,
            "checkoutId": null,
            "lastMessagePreview": null,
            "lastMessageAt": null,
            "createdAt": "2026-01-01T00:00:00Z",
            "spaceId": null,
        }))
        .unwrap()
    }

    #[tokio::test]
    async fn writes_debounce_coalesce_and_flush_in_order() {
        let dir = tempfile::tempdir().unwrap();
        let cache = EngineCache::new(dir.path());
        let tx = CacheWriter::start(cache.clone(), Duration::from_millis(50));
        let handle = EngineCacheHandle::new("engine-a".into(), cache, tx);
        // A burst of frames inside the debounce window: one file write.
        for i in 0..5 {
            handle.save_rows(CachedRows {
                chats: vec![stub_chat(&format!("c{i}"))],
                ..Default::default()
            });
        }
        handle.flush().await;
        let rows = handle.load_rows().unwrap();
        assert_eq!(rows.chats.len(), 1);
        assert_eq!(rows.chats[0].id, "c4", "the newest frame wins");
        // Sidebars and transcripts ride the same FIFO; newest per key wins.
        handle.save_sidebar(SidebarStateSnapshot::default());
        handle.save_sidebar(SidebarStateSnapshot {
            pins_by_profile: [("p".into(), vec!["s1".into()])].into_iter().collect(),
            ..Default::default()
        });
        handle.save_transcript_state(
            "chat",
            CachedTranscript {
                entries: Vec::new(),
                digests: std::iter::once(("m0".to_owned(), "d0".to_owned())).collect(),
            },
        );
        handle.flush().await;
        assert!(
            handle
                .load_sidebar()
                .unwrap()
                .pins_by_profile
                .contains_key("p")
        );
        // The current format is entries + digests in one file; the legacy
        // entries-only reader misses it by design (it reads bare arrays).
        assert!(handle.load_transcript("chat").is_none());
        let state = handle.load_transcript_state("chat").unwrap();
        assert!(state.entries.is_empty());
        assert_eq!(state.digests.get("m0").map(String::as_str), Some("d0"));
    }

    #[tokio::test]
    async fn transcript_prune_keeps_the_hundred_newest() {
        let dir = tempfile::tempdir().unwrap();
        let cache = EngineCache::new(dir.path());
        for i in 0..102 {
            cache
                .save_transcript(
                    "engine",
                    &format!("chat-{i}"),
                    &[SessionMessageEntry {
                        origin_chat_id: None,
                        id: format!("m{i}"),
                        role: roboco_doc::MessageRole::User,
                        parts: vec![],
                        created_at: i,
                        device_id: String::new(),
                        status: None,
                        continuation_of: None,
                        provider_turn: None,
                        duration_ms: None,
                    }],
                )
                .unwrap();
            // Distinct mtimes: same-timestamp pruning would be arbitrary.
            std::thread::sleep(Duration::from_millis(15));
        }
        prune_transcripts(&cache, "engine");
        let remaining = cache.load_rows("engine"); // dir still readable
        assert!(remaining.is_none());
        let engine_dir = cache.engine_directory("engine");
        let count = std::fs::read_dir(&engine_dir)
            .unwrap()
            .filter(|e| {
                e.as_ref()
                    .ok()
                    .and_then(|e| e.file_name().into_string().ok())
                    .is_some_and(|n| n.starts_with("chat-"))
            })
            .count();
        assert_eq!(count, KEEP_TRANSCRIPTS);
        // The newest survive: chat-101 was written last.
        assert!(cache.load_transcript("engine", "chat-101").is_some());
        assert!(cache.load_transcript("engine", "chat-0").is_none());
    }
}
