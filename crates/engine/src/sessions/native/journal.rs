//! One native journal (the root, or one child) projected into one chat doc.
//!
//! Saved entries arrive at checkpoints and replace the provisional live
//! entry; live observations fold into that entry between checkpoints. Tool
//! parts are keyed by invocation id, so a running call's progress lands on the
//! same part before and after its start entry is saved.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use serde_json::Value;

use roboco_doc::{
    DocError, MessagePart, MessageRole, MessageStatus, NativeBatch, SegmentWriter, SessionDoc,
    SessionMessageEntry, SubagentStatus, summarize_tool_output,
};
use roboco_harness::mimir::Runtime;
use roboco_harness::mimir::protocol::{
    BridgeError, DisplayEntry, EntryAnchor, ErrorKind, Event, JournalCut, PageEntry, ThinkingKind,
    ToolFinished, ToolStarted, ViewObservation,
};
use roboco_proto::{
    NativeChildStatus, NativeNotice, NativeToolDetail, NativeToolView, NativeUserRequest, ToolCall,
    UserInputQuestion,
};

use super::projection::{self, Projected, ToolResult};
use crate::doc_host::DocHost;
use crate::{new_id, now_ms};

/// Chunk size of a stored blob series. It bounds reads and the rewrite of
/// the open chunk; nothing a series records is ever dropped.
const SERIES_CHUNK: usize = 256 << 10;

/// A growing record kept whole as numbered engine blobs (`{name}.{index:06}`).
struct Series {
    name: String,
    /// Chunks that are full and final.
    sealed: u32,
    /// The open chunk, rewritten at index `sealed` when flushed.
    open: Vec<u8>,
    bytes: u64,
    records: u64,
    dirty: bool,
}

/// Where a chunk may end at or below `at` without splitting a UTF-8 sequence.
fn utf8_cut(buf: &[u8], at: usize) -> usize {
    let mut cut = at;
    while cut > at.saturating_sub(3) && cut > 0 && buf[cut] & 0xC0 == 0x80 {
        cut -= 1;
    }
    if cut == 0 || buf[cut] & 0xC0 == 0x80 {
        at
    } else {
        cut
    }
}

impl Series {
    fn new(name: String, stored: Option<&roboco_proto::NativeBlobSeries>) -> Self {
        // A series another process left open continues in a fresh chunk.
        Self {
            name,
            sealed: stored.map_or(0, |s| s.chunks),
            open: Vec::new(),
            bytes: stored.map_or(0, |s| s.bytes),
            records: stored.map_or(0, |s| s.records),
            dirty: false,
        }
    }

    fn chunk(&self, index: u32) -> String {
        format!("{}.{index:06}", self.name)
    }

    fn push(&mut self, host: &DocHost, chat_id: &str, data: &[u8]) {
        self.open.extend_from_slice(data);
        self.bytes += data.len() as u64;
        self.records += 1;
        self.dirty = true;
        while self.open.len() > SERIES_CHUNK {
            let rest = self.open.split_off(utf8_cut(&self.open, SERIES_CHUNK));
            host.store_native_blob(chat_id, &self.chunk(self.sealed), &self.open);
            self.sealed += 1;
            self.open = rest;
        }
    }

    fn flush(&mut self, host: &DocHost, chat_id: &str) {
        if self.dirty && !self.open.is_empty() {
            host.store_native_blob(chat_id, &self.chunk(self.sealed), &self.open);
        }
        self.dirty = false;
    }

    fn describe(&self, chat_id: &str) -> Option<roboco_proto::NativeBlobSeries> {
        (self.records > 0).then(|| roboco_proto::NativeBlobSeries {
            blob_ref: format!("{chat_id}/{}", self.name),
            chunks: self.sealed + u32::from(!self.open.is_empty()),
            bytes: self.bytes,
            records: self.records,
        })
    }
}

/// Live knowledge of one tool call that the saved journal does not hold yet.
struct ToolLive {
    detail: NativeToolDetail,
    stream: Series,
    progress: Series,
    last_progress: Option<String>,
    resolved: bool,
    is_error: bool,
    output: Option<String>,
    diff_stats: Option<Vec<roboco_doc::ToolDiffStat>>,
}

impl ToolLive {
    /// Live state for `part_id`, continuing the series its stored detail names.
    fn new(part_id: &str, detail: NativeToolDetail) -> Self {
        Self {
            stream: Series::new(format!("{part_id}.stream"), detail.stream.as_ref()),
            progress: Series::new(format!("{part_id}.progress"), detail.progress.as_ref()),
            last_progress: None,
            resolved: false,
            is_error: false,
            output: None,
            diff_stats: None,
            detail,
        }
    }

    /// Flush both series and point the detail at everything recorded.
    fn sync(&mut self, host: &DocHost, chat_id: &str) {
        self.stream.flush(host, chat_id);
        self.progress.flush(host, chat_id);
        self.detail.stream = self.stream.describe(chat_id);
        self.detail.progress = self.progress.describe(chat_id);
    }
}

/// A child's binding to the parent tool call that spawned it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct ChildLink {
    pub handle: String,
    pub doc_id: String,
    pub status: NativeChildStatus,
}

/// Saved entries read for one checkpoint.
pub(super) struct Read {
    pub entries: Vec<Saved>,
    /// The whole branch was read because the cursor was not on it.
    pub rebuilt: bool,
}

pub(super) enum Saved {
    Entry(Box<DisplayEntry>),
    /// A public entry this engine cannot parse; kept inspectable.
    Unparsed {
        id: String,
        raw: Vec<u8>,
    },
}

impl Saved {
    fn id(&self) -> &str {
        match self {
            Self::Entry(entry) => &entry.id,
            Self::Unparsed { id, .. } => id,
        }
    }
}

fn page_entry_id(entry: &PageEntry) -> Option<String> {
    match entry {
        PageEntry::Entry(value) => value.get("id").and_then(Value::as_str).map(str::to_owned),
        PageEntry::Oversized(header) => Some(header.id.clone()),
    }
}

async fn materialize(
    runtime: &Runtime,
    session: &str,
    cut: &JournalCut,
    entry: PageEntry,
) -> Result<Saved, BridgeError> {
    let (id, raw) = match entry {
        PageEntry::Entry(value) => {
            let id = value
                .get("id")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_owned();
            match serde_json::from_value::<DisplayEntry>(value.clone()) {
                Ok(parsed) => return Ok(Saved::Entry(Box::new(parsed))),
                Err(_) => (id, serde_json::to_vec(&value).unwrap_or_default()),
            }
        }
        PageEntry::Oversized(header) => {
            let raw = runtime
                .read_entry(session, cut, &header.id, header.bytes)
                .await?;
            match serde_json::from_slice::<DisplayEntry>(&raw) {
                Ok(parsed) => return Ok(Saved::Entry(Box::new(parsed))),
                Err(_) => (header.id, raw),
            }
        }
    };
    Ok(Saved::Unparsed { id, raw })
}

/// Every entry after `leaf` up to `cut`, or the whole branch when `leaf` is
/// absent or no longer on it. Pages stay pinned to `cut`.
pub(super) async fn read_since(
    runtime: &Runtime,
    session: &str,
    cut: &JournalCut,
    leaf: Option<&str>,
) -> Result<Read, BridgeError> {
    if cut.leaf.as_deref() == leaf {
        return Ok(Read {
            entries: Vec::new(),
            rebuilt: false,
        });
    }
    if let Some(leaf) = leaf {
        let mut entries = Vec::new();
        let mut anchor = EntryAnchor::After(leaf.to_owned());
        loop {
            let page = match runtime.read_entries(session, cut, &anchor).await {
                Ok(page) => page,
                Err(error) if error.kind == ErrorKind::NotFound && entries.is_empty() => {
                    return read_all(runtime, session, cut).await;
                }
                Err(error) => return Err(error),
            };
            let last = page.entries.last().and_then(page_entry_id);
            let newer = page.newer;
            for entry in page.entries {
                entries.push(materialize(runtime, session, cut, entry).await?);
            }
            match last {
                Some(last) if newer => anchor = EntryAnchor::After(last),
                _ => break,
            }
        }
        return Ok(Read {
            entries,
            rebuilt: false,
        });
    }
    read_all(runtime, session, cut).await
}

async fn read_all(runtime: &Runtime, session: &str, cut: &JournalCut) -> Result<Read, BridgeError> {
    let mut pages = Vec::new();
    let mut anchor = EntryAnchor::Latest;
    loop {
        let page = runtime.read_entries(session, cut, &anchor).await?;
        let first = page.entries.first().and_then(page_entry_id);
        let older = page.older;
        pages.push(page.entries);
        match first {
            Some(first) if older => anchor = EntryAnchor::Before(first),
            _ => break,
        }
    }
    let mut entries = Vec::new();
    for page in pages.into_iter().rev() {
        for entry in page {
            entries.push(materialize(runtime, session, cut, entry).await?);
        }
    }
    Ok(Read {
        entries,
        rebuilt: true,
    })
}

/// The provisional entry folding live observations since the last checkpoint.
struct Live {
    entry_id: String,
    parts: Vec<MessagePart>,
    written: Vec<MessagePart>,
    exists: bool,
    dirty: bool,
    started_at: i64,
}

impl Live {
    fn fresh() -> Self {
        Self {
            entry_id: format!("live-{}", new_id()),
            parts: Vec::new(),
            written: Vec::new(),
            exists: false,
            dirty: false,
            started_at: now_ms(),
        }
    }

    fn mark(&mut self) {
        self.dirty = true;
    }
}

pub(super) struct Journal {
    /// The chat that owns the blobs this journal's details are stored under.
    pub chat_id: String,
    pub doc: Arc<SessionDoc>,
    pub device_id: String,
    pub child: Option<String>,
    host: DocHost,
    live: Live,
    /// Inputs the host accepted since the last checkpoint, in order, with
    /// the engine echo each one answers when the engine wrote one.
    accepted: Vec<Option<String>>,
    tools: HashMap<String, ToolLive>,
    /// Provider call id → part id of its latest call; progress names calls by provider id.
    calls: HashMap<String, String>,
    /// Tool parts already in saved entries: running calls keep updating them.
    saved_tools: HashSet<String>,
    /// A question or approval still pending when a checkpoint retired the live entry.
    pending_input: Option<NativeUserRequest>,
}

/// The engine echoes a projection must respect.
#[derive(Default)]
pub(super) struct Echoes {
    /// Echo ids the host has not accepted yet; they stay last in the transcript.
    pub held: HashSet<String>,
    /// After a lost view: admitted echoes whose inputs the host accepted but
    /// whose saved entries were never projected, oldest first. The engine
    /// owned the conversation from the projected leaf until the loss, so
    /// they are the first user inputs after that leaf.
    pub pending: Vec<String>,
}

/// Which saved tool calls still run at a cut.
#[derive(Clone, Copy)]
pub(super) enum Running<'a> {
    /// A checkpoint's own list, which is authoritative.
    Listed(&'a [String]),
    /// A cut that carries no list (a child announcement): every saved call
    /// whose result is not saved still runs, and no live state is dropped
    /// that a saved result has not replaced.
    Inferred,
}

fn input_part(request: &NativeUserRequest) -> MessagePart {
    MessagePart::Input {
        id: format!("in-{}", request.id),
        request_id: request.id.clone(),
        questions: request
            .questions
            .iter()
            .map(|q| UserInputQuestion {
                id: q.id.clone(),
                header: String::new(),
                question: q.prompt.clone(),
                options: q.options.iter().map(|o| o.label.clone()).collect(),
                multi_select: q.allow_multiple,
            })
            .collect(),
        resolved: false,
    }
}

fn view_of(part: &MessagePart) -> Option<&NativeToolView> {
    match part {
        MessagePart::Tool {
            call: ToolCall::Native { view },
            ..
        } => Some(view),
        _ => None,
    }
}

fn view_of_mut(part: &mut MessagePart) -> Option<&mut NativeToolView> {
    match part {
        MessagePart::Tool {
            call: ToolCall::Native { view },
            ..
        } => Some(view),
        _ => None,
    }
}

pub(super) fn subagent_status(status: NativeChildStatus) -> SubagentStatus {
    match status {
        NativeChildStatus::Queued | NativeChildStatus::Running => SubagentStatus::Running,
        NativeChildStatus::Completed | NativeChildStatus::Stopped => SubagentStatus::Done,
        NativeChildStatus::Failed
        | NativeChildStatus::Deadline
        | NativeChildStatus::Interrupted => SubagentStatus::Failed,
    }
}

/// Insertion point for new entries: ahead of trailing echoes the host has
/// not accepted, so saved history never lands below a queued message.
fn insertion_point(batch: &NativeBatch<'_>, held: &HashSet<String>, live: Option<&str>) -> usize {
    let ids = batch.ids();
    let mut at = ids.len();
    while at > 0 && (held.contains(&ids[at - 1]) || Some(ids[at - 1].as_str()) == live) {
        at -= 1;
    }
    at
}

impl Journal {
    pub fn new(
        chat_id: &str,
        doc: Arc<SessionDoc>,
        device_id: &str,
        child: Option<String>,
        host: DocHost,
    ) -> Self {
        Self {
            chat_id: chat_id.to_owned(),
            doc,
            device_id: device_id.to_owned(),
            child,
            host,
            live: Live::fresh(),
            accepted: Vec::new(),
            tools: HashMap::new(),
            calls: HashMap::new(),
            saved_tools: HashSet::new(),
            pending_input: None,
        }
    }

    pub fn leaf(&self) -> Option<String> {
        self.doc.native_projection().leaf
    }

    pub fn dirty(&self) -> bool {
        self.live.dirty
            || self
                .tools
                .values()
                .any(|live| live.stream.dirty || live.progress.dirty)
    }

    fn store_detail(&self, part_id: &str, detail: &NativeToolDetail) -> Option<u64> {
        let bytes = serde_json::to_vec(detail).ok()?;
        self.host
            .store_native_blob(&self.chat_id, &format!("{part_id}.native"), &bytes);
        Some(bytes.len() as u64)
    }

    fn store_live(&mut self, part_id: &str) -> Option<u64> {
        let live = self.tools.get_mut(part_id)?;
        live.sync(&self.host, &self.chat_id);
        let detail = live.detail.clone();
        self.store_detail(part_id, &detail)
    }

    fn stored_detail(&self, part_id: &str) -> Option<NativeToolDetail> {
        let text = self
            .host
            .load_native_blob(&projection::detail_ref(&self.chat_id, part_id))?;
        serde_json::from_slice(&text).ok()
    }

    fn store_output(&self, part_id: &str, output: &str) {
        self.host
            .store_native_blob(&self.chat_id, part_id, output.as_bytes());
    }

    // ── live observations ───────────────────────────────────────────────

    /// The part a provider call id names: the live part, or a saved one.
    fn part_for_call(&self, call_id: &str) -> Option<String> {
        self.calls.get(call_id).cloned()
    }

    fn with_part(&mut self, part_id: &str, update: impl FnOnce(&mut MessagePart)) {
        if let Some(part) = self.live.parts.iter_mut().find(|p| p.id() == part_id) {
            update(part);
            self.live.mark();
            return;
        }
        let mut batch = self.doc.native_batch();
        if let Some((_, mut part)) = batch.part(part_id) {
            update(&mut part);
            if batch.replace_part(part_id, &part).is_ok() {
                let _ = batch.commit();
            }
        }
    }

    pub fn observe(&mut self, observation: &ViewObservation) {
        match &observation.observation.event {
            Event::TextDelta(delta) => {
                if delta.value.is_empty() {
                    return;
                }
                if let Some(MessagePart::Text { text, .. }) = self.live.parts.last_mut() {
                    text.push_str(&delta.value);
                } else {
                    let id = format!("t{}", self.live.parts.len());
                    self.live.parts.push(MessagePart::Text {
                        id,
                        text: delta.value.clone(),
                    });
                }
                self.live.mark();
            }
            Event::ThinkingDelta(delta) if delta.kind == ThinkingKind::Summary => {
                if delta.value.is_empty() {
                    return;
                }
                if let Some(MessagePart::Reasoning { text, .. }) = self.live.parts.last_mut() {
                    text.push_str(&delta.value);
                } else {
                    let id = format!("r{}", self.live.parts.len());
                    self.live.parts.push(MessagePart::Reasoning {
                        id,
                        text: delta.value.clone(),
                    });
                }
                self.live.mark();
            }
            Event::ToolStarted(started) => self.tool_started(started),
            Event::ToolProgress(progress) => {
                let Some(part_id) = self.part_for_call(&progress.id) else {
                    return;
                };
                if let Some(live) = self.tools.get_mut(&part_id) {
                    let mut line = serde_json::to_vec(&progress.text).unwrap_or_default();
                    line.push(b'\n');
                    live.progress.push(&self.host, &self.chat_id, &line);
                    live.last_progress = Some(progress.text.clone());
                }
                let text = progress.text.clone();
                self.with_part(&part_id, |part| {
                    if let Some(view) = view_of_mut(part) {
                        view.progress = Some(text);
                    }
                });
            }
            Event::ToolOutputDelta(output) => {
                let Some(part_id) = self.part_for_call(&output.tool_call_id) else {
                    return;
                };
                if let Some(live) = self.tools.get_mut(&part_id) {
                    live.stream.push(&self.host, &self.chat_id, &output.bytes);
                }
            }
            Event::ToolStyled(styled) => {
                let Some(part_id) = self.part_for_call(&styled.id) else {
                    return;
                };
                let style = styled.style.clone();
                self.with_part(&part_id, |part| {
                    if let Some(view) = view_of_mut(part) {
                        if style.title.is_some() {
                            view.running_title = style.title.clone();
                        }
                        view.quiet = style.quiet;
                        view.group = style.group.as_ref().map(|g| g.native());
                    }
                });
            }
            Event::ToolResultCompacted(compaction) => {
                let part_id = compaction
                    .get("tool_call_id")
                    .and_then(Value::as_str)
                    .and_then(|call| self.part_for_call(call));
                if let Some(live) = part_id.and_then(|id| self.tools.get_mut(&id)) {
                    live.detail.compactions.push(compaction.clone());
                }
            }
            Event::ToolFinished(finished) => self.tool_finished(finished),
            Event::UserRequestReady(ready) => self.ask(ready.request.native()),
            Event::ToolApprovalRequested(approval) => self.ask(approval.native()),
            Event::ToolApprovalSettled(id) => self.settle_input(id),
            Event::RunError(error) => {
                let id = format!("e{}", self.live.parts.len());
                self.live.parts.push(MessagePart::Error {
                    id,
                    message: error.text.clone(),
                });
                self.live.mark();
            }
            Event::ModelRetryScheduled(retry) => self.notice(NativeNotice::Status {
                text: format!(
                    "Retrying the model request ({}/{}): {}",
                    retry.attempt, retry.max_attempts, retry.reason
                ),
            }),
            Event::InputAccepted(_) => {}
            Event::ContextSnapshot(snapshot)
                if self.child.is_none() && snapshot.context_window > 0 =>
            {
                let _ = self.doc.update_context_usage(
                    Some(snapshot.used_prompt_tokens),
                    Some(snapshot.context_window),
                );
            }
            _ => {}
        }
    }

    /// The host accepted an input; `echo` is the engine message it answers.
    pub fn accept(&mut self, echo: Option<String>) {
        self.accepted.push(echo);
    }

    pub fn notice(&mut self, notice: NativeNotice) {
        let id = format!("n{}", self.live.parts.len());
        self.live.parts.push(MessagePart::Notice { id, notice });
        self.live.mark();
    }

    /// A question the host reports pending (a view start after reconnect).
    pub fn ask_pending(&mut self, request: NativeUserRequest) {
        self.ask(request);
    }

    fn ask(&mut self, request: NativeUserRequest) {
        let part = input_part(&request);
        if !self.live.parts.iter().any(|p| p.id() == part.id()) {
            self.live.parts.push(part);
            self.live.mark();
        }
        self.pending_input = Some(request);
    }

    /// The host no longer has `request_id` pending.
    pub fn settle_input(&mut self, request_id: &str) {
        if self
            .pending_input
            .as_ref()
            .is_some_and(|pending| pending.id == request_id)
        {
            self.pending_input = None;
        }
        for part in &mut self.live.parts {
            if let MessagePart::Input {
                request_id: rid,
                resolved,
                ..
            } = part
                && rid == request_id
                && !*resolved
            {
                *resolved = true;
                self.live.dirty = true;
            }
        }
    }

    pub fn pending_input(&self) -> Option<&NativeUserRequest> {
        self.pending_input.as_ref()
    }

    fn tool_started(&mut self, started: &ToolStarted) {
        let part_id = started.invocation_id.clone();
        self.calls.insert(started.id.clone(), part_id.clone());
        let mut view = projection::tool_view(
            &started.name,
            &started.id,
            Some(&started.invocation_id),
            &started.presentation,
        );
        view.detail_ref = Some(projection::detail_ref(&self.chat_id, &part_id));
        let mut detail = projection::empty_detail(view.clone());
        detail.input = serde_json::from_str(&started.input_json).ok();
        if detail.input.is_none() {
            detail.raw_input = Some(started.input_json.clone());
        }
        match self.tools.get_mut(&part_id) {
            Some(live) => {
                detail.stream = live.detail.stream.take();
                detail.progress = live.detail.progress.take();
                live.detail = detail;
            }
            None => {
                self.tools
                    .insert(part_id.clone(), ToolLive::new(&part_id, detail));
            }
        }
        view.detail_bytes = self.store_live(&part_id);
        let existing = self.live.parts.iter().any(|p| p.id() == part_id)
            || self.saved_tools.contains(&part_id);
        if existing {
            self.with_part(&part_id, |part| {
                if let Some(current) = view_of_mut(part) {
                    let child = current.child.take();
                    *current = view;
                    current.child = child;
                }
            });
        } else {
            self.live.parts.push(projection::tool_part(part_id, view));
            self.live.mark();
        }
    }

    fn tool_finished(&mut self, finished: &ToolFinished) {
        let part_id = finished.invocation_id.clone();
        self.calls.insert(finished.id.clone(), part_id.clone());
        let mut view = projection::tool_view(
            &finished.name,
            &finished.id,
            Some(&finished.invocation_id),
            &finished.presentation,
        );
        view.duration_ms = Some(finished.duration_ms);
        view.detail_ref = Some(projection::detail_ref(&self.chat_id, &part_id));
        let output = projection::result_output(
            &finished.display_content,
            &roboco_harness::mimir::protocol::MessageContent::Text(finished.output.clone()),
        );
        let diff_stats = projection::diff_stats(&finished.output_profile);
        if !self.tools.contains_key(&part_id) {
            // A call that was already running when this view started:
            // continue from what was stored for it.
            let detail = self
                .stored_detail(&part_id)
                .unwrap_or_else(|| projection::empty_detail(view.clone()));
            self.tools
                .insert(part_id.clone(), ToolLive::new(&part_id, detail));
        }
        let live = self.tools.get_mut(&part_id).expect("inserted above");
        live.resolved = true;
        live.is_error = finished.is_error;
        live.output = Some(output.clone());
        live.diff_stats = diff_stats.clone();
        live.detail.is_error = Some(finished.is_error);
        live.detail.output = Some(output.clone());
        live.detail.display_content = finished.display_content.clone();
        live.detail.details = serde_json::from_str(&finished.details_json)
            .ok()
            .filter(|v: &Value| !v.is_null());
        live.detail.output_profile = Some(finished.output_profile.clone());
        if live.detail.input.is_none() {
            live.detail.input = serde_json::from_str(&finished.input_json).ok();
        }
        live.detail.view = view.clone();
        view.detail_bytes = self.store_live(&part_id);
        let detail = self.tools[&part_id].detail.clone();
        self.store_output(&part_id, &output);
        let output_bytes = output.len() as u64;
        let summary = summarize_tool_output(&output);
        let output_ref = projection::output_ref(&self.chat_id, &part_id);
        let failed = finished.is_error;
        let finish = move |part: &mut MessagePart| {
            if let MessagePart::Tool {
                call,
                is_error,
                resolved,
                output: summary_slot,
                output_ref: ref_slot,
                output_bytes: bytes_slot,
                diff_stats: stats_slot,
                ..
            } = part
            {
                let child = match call {
                    ToolCall::Native { view } => view.child.clone(),
                    _ => None,
                };
                let mut view = view.clone();
                view.child = child;
                *call = ToolCall::Native {
                    view: Box::new(view),
                };
                *is_error = failed;
                *resolved = true;
                *summary_slot = summary.clone();
                *ref_slot = Some(output_ref.clone());
                *bytes_slot = Some(output_bytes);
                if diff_stats.is_some() {
                    *stats_slot = diff_stats.clone();
                }
            }
        };
        let exists = self.live.parts.iter().any(|p| p.id() == part_id)
            || self.saved_tools.contains(&part_id);
        if exists {
            self.with_part(&part_id, finish);
        } else {
            let mut part = projection::tool_part(part_id.clone(), detail.view.clone());
            finish(&mut part);
            self.live.parts.push(part);
            self.live.mark();
        }
    }

    // ── checkpoints ─────────────────────────────────────────────────────

    /// Remove the live entry and any provisional entry a previous engine left.
    fn retire_live(&mut self, batch: &mut NativeBatch<'_>) -> Result<(), DocError> {
        for id in batch.ids() {
            if id.starts_with("live-") {
                batch.remove(&id)?;
            }
        }
        self.live = Live::fresh();
        Ok(())
    }

    /// Apply saved entries through `cut_leaf`. Calls that are `running` keep
    /// their live state: their start is saved, their result not yet.
    ///
    /// A forward read extends what is projected. A rebuilt read is the exact
    /// branch: whatever it lacks (later results, their details, entries on
    /// another branch) leaves the projection, and nothing is inherited from
    /// what was shown before except the bindings the host never re-sends.
    pub fn apply(
        &mut self,
        read: Read,
        cut_leaf: Option<String>,
        running: Running<'_>,
        echoes: &Echoes,
    ) -> Result<(), DocError> {
        let doc = self.doc.clone();
        let mut batch = doc.native_batch();
        self.retire_live(&mut batch)?;
        let projected: Vec<(String, Projected)> = read
            .entries
            .iter()
            .map(|saved| {
                let id = saved.id().to_owned();
                let projected = match saved {
                    Saved::Entry(entry) => {
                        projection::project(&self.chat_id, &self.device_id, entry)
                    }
                    Saved::Unparsed { id, raw } => {
                        let name = format!("{id}.unsupported");
                        self.host.store_native_blob(&self.chat_id, &name, raw);
                        Projected::Entry {
                            native_id: id.clone(),
                            entry: SessionMessageEntry {
                                id: roboco_doc::native_entry_id(id),
                                role: MessageRole::System,
                                parts: vec![MessagePart::Notice {
                                    id: "n0".into(),
                                    notice: NativeNotice::Unsupported {
                                        item: "display entry".into(),
                                        detail_ref: Some(format!("{}/{name}", self.chat_id)),
                                    },
                                }],
                                created_at: now_ms(),
                                device_id: self.device_id.clone(),
                                status: Some(MessageStatus::Complete),
                                continuation_of: None,
                                duration_ms: None,
                            },
                            user_text: false,
                            details: Vec::new(),
                            images: Vec::new(),
                        }
                    }
                };
                (id, projected)
            })
            .collect();

        let exact = read.rebuilt;
        if exact {
            let keep: HashSet<String> = projected
                .iter()
                .filter(|(_, p)| matches!(p, Projected::Entry { .. }))
                .map(|(id, _)| batch.projection.doc_id(id))
                .collect();
            for id in batch.ids() {
                if batch.projection.projects(&id) && !keep.contains(&id) {
                    // An echo leaves with its branch and returns with it.
                    let native = batch
                        .projection
                        .echoes
                        .iter()
                        .find(|(_, echo)| **echo == id)
                        .map(|(native, _)| native.clone());
                    if let Some(native) = native
                        && let Some(entry) = batch.entry(&id)
                    {
                        batch.projection.parked.insert(native, entry);
                    }
                    batch.remove(&id)?;
                }
            }
        }

        // Pair the batch's public user entries with the inputs the host
        // accepted since the last checkpoint, in order. Anything that does
        // not line up keeps its native id; nothing is matched by text.
        let users: Vec<&str> = projected
            .iter()
            .filter(|(id, p)| {
                matches!(
                    p,
                    Projected::Entry {
                        user_text: true,
                        ..
                    }
                ) && !batch.projection.echoes.contains_key(id.as_str())
            })
            .map(|(id, _)| id.as_str())
            .collect();
        let mut pairs: Vec<(String, String)> = Vec::new();
        if self.child.is_none() {
            if !users.is_empty() && users.len() == self.accepted.len() {
                for (native, echo) in users.iter().zip(self.accepted.iter()) {
                    if let Some(echo) = echo
                        && batch.contains(echo)
                    {
                        pairs.push(((*native).to_owned(), echo.clone()));
                    }
                }
            } else if self.accepted.is_empty() {
                let pending = echoes.pending.iter().filter(|echo| {
                    batch.contains(echo) && !batch.projection.echoes.values().any(|m| m == *echo)
                });
                for (native, echo) in users.iter().zip(pending) {
                    pairs.push(((*native).to_owned(), echo.clone()));
                }
            }
        }
        for (native, echo) in pairs {
            batch.projection.echoes.insert(native, echo);
        }

        let held = &echoes.held;
        let mut resolved = HashSet::new();
        for (native, projected) in projected {
            match projected {
                Projected::Entry {
                    mut entry,
                    details,
                    images,
                    ..
                } => {
                    let doc_id = batch.projection.doc_id(&native);
                    if doc_id != entry.id {
                        // The engine's echo shows this message.
                        if batch.contains(&doc_id) {
                            continue;
                        }
                        // Back on a branch a rewind left: the echo returns as
                        // it was, under the user's own message id.
                        match batch.projection.parked.remove(&native) {
                            Some(parked) => entry = parked,
                            None => entry.id = doc_id,
                        }
                        let at = insertion_point(&batch, held, None);
                        batch.upsert(&entry, at)?;
                        continue;
                    }
                    for (blob, image) in images {
                        if let Some(name) = blob.strip_prefix(&format!("{}/", self.chat_id)) {
                            self.host.store_native_blob(
                                &self.chat_id,
                                name,
                                &serde_json::to_vec(&image).unwrap_or_default(),
                            );
                        }
                    }
                    for detail in details {
                        let part_id = projection::tool_part_id(
                            detail.invocation_id.as_deref(),
                            &native,
                            &detail.tool_call_id,
                        );
                        self.calls
                            .insert(detail.tool_call_id.clone(), part_id.clone());
                        let bytes = if self.tools.contains_key(&part_id) {
                            self.store_live(&part_id)
                        } else if exact {
                            self.store_detail(&part_id, &detail)
                        } else {
                            match self.stored_detail(&part_id) {
                                Some(stored) if stored.output.is_some() => {
                                    Some(serde_json::to_vec(&stored).map_or(0, |b| b.len() as u64))
                                }
                                _ => self.store_detail(&part_id, &detail),
                            }
                        };
                        if let Some(part) = entry.parts.iter_mut().find(|p| p.id() == part_id)
                            && let Some(view) = view_of_mut(part)
                        {
                            view.detail_bytes = bytes;
                        }
                    }
                    // A running call keeps what the live stream already showed.
                    for part in &mut entry.parts {
                        if let Some(live) = self.tools.get(part.id()) {
                            overlay_live(part, live, &self.chat_id);
                        }
                    }
                    if let Some(existing) = batch.entry(&doc_id) {
                        entry.created_at = existing.created_at;
                        merge_kept(&mut entry, &existing, exact);
                    } else if entry.created_at == 0 {
                        entry.created_at = now_ms();
                    }
                    let at = insertion_point(&batch, held, None);
                    batch.upsert(&entry, at)?;
                }
                Projected::ToolResult(result) => {
                    if let Some(part_id) = self.apply_result(&mut batch, &native, &result)? {
                        resolved.insert(part_id);
                    }
                }
                Projected::Hidden => {}
            }
        }
        batch.projection.leaf = cut_leaf;
        batch.commit()?;
        self.accepted.clear();
        let running: HashSet<String> = match running {
            Running::Listed(ids) => {
                let ids: HashSet<String> = ids.iter().cloned().collect();
                self.tools.retain(|id, _| ids.contains(id));
                ids
            }
            Running::Inferred => {
                self.tools.retain(|id, _| !resolved.contains(id));
                self.unresolved_calls()
            }
        };
        // Calls already running when this view started: what they stream
        // from here on lands on their stored detail.
        for id in &running {
            if !self.tools.contains_key(id)
                && let Some(detail) = self.stored_detail(id)
            {
                self.calls.insert(detail.tool_call_id.clone(), id.clone());
                self.tools.insert(id.clone(), ToolLive::new(id, detail));
            }
        }
        self.saved_tools = running;
        if let Some(request) = self.pending_input.clone() {
            self.live.parts.push(input_part(&request));
            self.live.mark();
        }
        Ok(())
    }

    /// Saved calls whose results are not saved yet.
    fn unresolved_calls(&self) -> HashSet<String> {
        self.doc
            .read_entries()
            .unwrap_or_default()
            .into_iter()
            .flat_map(|entry| entry.parts)
            .filter_map(|part| match part {
                MessagePart::Tool {
                    id,
                    call: ToolCall::Native { .. },
                    resolved: false,
                    ..
                } => Some(id),
                _ => None,
            })
            .collect()
    }

    /// Complete call part `part_id` with the saved result entry `native`,
    /// returning the part it completed. A result applied before restores the
    /// detail it finished with: the entry never changes, and the live public
    /// output, stream and progress this engine recorded exist nowhere else.
    fn apply_result(
        &mut self,
        batch: &mut NativeBatch<'_>,
        native: &str,
        result: &ToolResult,
    ) -> Result<Option<String>, DocError> {
        let part_id = match &result.invocation_id {
            Some(id) => id.clone(),
            None => match self.part_for_call(&result.tool_call_id) {
                Some(id) => id,
                None => return Ok(None),
            },
        };
        let Some((_, mut part)) = batch.part(&part_id) else {
            tracing::debug!(target: "roboco_engine::mimir", part = %part_id, entry = %native, "tool result without a projected call");
            return Ok(None);
        };
        let retained = if self.tools.contains_key(&part_id) {
            None
        } else {
            self.finished_detail(&part_id, native)
        };
        let detail = match retained {
            Some(detail) => detail,
            None => self.finish_detail(&part_id, &part, result),
        };
        let output = detail.output.clone().unwrap_or_default();
        let stats = detail
            .output_profile
            .as_ref()
            .and_then(projection::diff_stats);
        if let MessagePart::Tool {
            call,
            is_error,
            resolved,
            output: summary,
            output_ref,
            output_bytes,
            diff_stats,
            ..
        } = &mut part
        {
            if let ToolCall::Native { view } = call {
                let child = view.child.take().or_else(|| detail.view.child.clone());
                **view = detail.view.clone();
                view.child = child;
                view.detail_bytes = self.store_detail(&part_id, &detail);
            }
            *is_error = result.is_error;
            *resolved = true;
            *summary = summarize_tool_output(&output);
            *output_ref = Some(projection::output_ref(&self.chat_id, &part_id));
            *output_bytes = Some(output.len() as u64);
            if stats.is_some() {
                *diff_stats = stats;
            }
        }
        self.store_finished(&part_id, native, &detail);
        self.store_output(&part_id, &output);
        batch.replace_part(&part_id, &part)?;
        Ok(Some(part_id))
    }

    /// The detail a saved result first completes its call with.
    fn finish_detail(
        &mut self,
        part_id: &str,
        part: &MessagePart,
        result: &ToolResult,
    ) -> NativeToolDetail {
        let mut view = view_of(part).cloned().unwrap_or_else(|| {
            projection::bare_view(
                &result.tool_name,
                &result.tool_call_id,
                result.invocation_id.as_deref(),
            )
        });
        let mut detail = self
            .tools
            .get(part_id)
            .map(|live| live.detail.clone())
            .or_else(|| self.stored_detail(part_id))
            .unwrap_or_else(|| projection::empty_detail(view.clone()));
        // With no public display list the live public output is the full
        // form of what the saved model-facing content may only bound.
        let output = match self.tools.get_mut(part_id) {
            Some(live) => {
                live.sync(&self.host, &self.chat_id);
                detail.stream = live.detail.stream.clone();
                detail.progress = live.detail.progress.clone();
                match &live.output {
                    Some(output) if live.resolved && result.display_content.is_empty() => {
                        output.clone()
                    }
                    _ => result.output.clone(),
                }
            }
            None => result.output.clone(),
        };
        detail.is_error = Some(result.is_error);
        detail.output = Some(output);
        detail.display_content = result.display_content.clone();
        if result.details.is_some() {
            detail.details = result.details.clone();
        }
        if let Some(final_view) = &result.presentation {
            let child = view.child.take();
            let duration = view.duration_ms;
            view = final_view.clone();
            view.child = child;
            view.duration_ms = duration;
        }
        view.progress = None;
        view.detail_ref = Some(projection::detail_ref(&self.chat_id, part_id));
        detail.view = view;
        detail
    }

    /// The detail saved result entry `result_entry` finished `part_id` with.
    fn finished_detail(&self, part_id: &str, result_entry: &str) -> Option<NativeToolDetail> {
        let name = projection::finished_name(part_id, result_entry);
        let bytes = self
            .host
            .load_native_blob(&format!("{}/{name}", self.chat_id))?;
        serde_json::from_slice(&bytes).ok()
    }

    fn store_finished(&self, part_id: &str, result_entry: &str, detail: &NativeToolDetail) {
        if let Ok(bytes) = serde_json::to_vec(detail) {
            self.host.store_native_blob(
                &self.chat_id,
                &projection::finished_name(part_id, result_entry),
                &bytes,
            );
        }
    }

    /// Bind delegated children to the parent tool calls that spawned them.
    pub fn bind_children(&mut self, links: &HashMap<String, ChildLink>) {
        if links.is_empty() {
            return;
        }
        let mut batch = self.doc.native_batch();
        let mut changed = false;
        for (invocation, link) in links {
            let status = subagent_status(link.status);
            let bind = |part: &mut MessagePart| -> bool {
                let MessagePart::Tool {
                    call: ToolCall::Native { view },
                    subagent_ref,
                    subagent_status: slot,
                    ..
                } = part
                else {
                    return false;
                };
                let before = (subagent_ref.clone(), *slot, view.child.clone());
                *subagent_ref = Some(link.doc_id.clone());
                *slot = Some(status);
                view.child = Some(link.handle.clone());
                before != (subagent_ref.clone(), *slot, view.child.clone())
            };
            if let Some(part) = self.live.parts.iter_mut().find(|p| p.id() == invocation) {
                if bind(part) {
                    self.live.dirty = true;
                }
                continue;
            }
            if let Some((_, mut part)) = batch.part(invocation)
                && bind(&mut part)
                && batch.replace_part(invocation, &part).is_ok()
            {
                changed = true;
            }
        }
        if changed {
            let _ = batch.commit();
        }
    }

    /// Write the live entry and running calls' details (coalesced by the
    /// caller's tick).
    pub fn flush(&mut self, held: &HashSet<String>) -> Result<(), DocError> {
        let streaming: Vec<String> = self
            .tools
            .iter()
            .filter(|(_, live)| live.stream.dirty || live.progress.dirty)
            .map(|(id, _)| id.clone())
            .collect();
        for id in streaming {
            self.store_live(&id);
        }
        if !self.live.dirty {
            return Ok(());
        }
        self.live.dirty = false;
        if self.live.parts.is_empty() {
            return Ok(());
        }
        let parts = self.live.parts.clone();
        let doc: &SessionDoc = &self.doc;
        let mut writer = if self.live.exists {
            let batch = doc.native_batch();
            let ids = batch.ids();
            let Some(index) = ids.iter().rposition(|id| *id == self.live.entry_id) else {
                self.live.exists = false;
                self.live.written.clear();
                self.live.dirty = true;
                return self.flush(held);
            };
            SegmentWriter::resume(doc, index, std::mem::take(&mut self.live.written))
        } else {
            let at = insertion_point(&doc.native_batch(), held, None);
            self.live.exists = true;
            SegmentWriter::begin_at(
                doc,
                &self.live.entry_id,
                &self.device_id,
                self.live.started_at,
                at,
            )?
        };
        let result = writer.sync(&parts);
        let (_, written) = writer.into_state();
        self.live.written = written;
        result
    }

    /// The live entry ends without a checkpoint: a lost bridge or engine
    /// restart. Provisional output is not the saved transcript, so it leaves
    /// the transcript for an interruption notice that keeps it inspectable;
    /// whatever the host did save arrives as ordinary entries on reconnect.
    pub fn abort_live(&mut self, message: &str) -> Result<(), DocError> {
        let mut batch = self.doc.native_batch();
        if self.live.exists
            && let Some(entry) = batch.entry(&self.live.entry_id)
        {
            let index = batch
                .ids()
                .iter()
                .rposition(|id| *id == entry.id)
                .unwrap_or(0);
            batch.remove(&entry.id)?;
            for live in self.tools.values_mut() {
                live.sync(&self.host, &self.chat_id);
            }
            let id = format!("interrupted-{}", new_id());
            let parts: Vec<MessagePart> = entry
                .parts
                .into_iter()
                .filter(|p| !matches!(p, MessagePart::Input { .. }))
                .collect();
            let detail_ref = (!parts.is_empty()).then(|| {
                let name = format!("{id}.provisional");
                self.host.store_native_blob(
                    &self.chat_id,
                    &name,
                    &serde_json::to_vec(&parts).unwrap_or_default(),
                );
                format!("{}/{name}", self.chat_id)
            });
            let notice = SessionMessageEntry {
                id,
                role: MessageRole::System,
                parts: vec![MessagePart::Notice {
                    id: "n0".into(),
                    notice: NativeNotice::Interrupted {
                        message: message.to_owned(),
                        detail_ref,
                    },
                }],
                created_at: entry.created_at,
                device_id: self.device_id.clone(),
                status: Some(MessageStatus::Complete),
                continuation_of: None,
                duration_ms: None,
            };
            batch.upsert(&notice, index)?;
        }
        batch.commit()?;
        self.live = Live::fresh();
        self.tools.clear();
        self.accepted.clear();
        self.pending_input = None;
        Ok(())
    }
}

/// What a saved, still-running call keeps from its live stream.
fn overlay_live(part: &mut MessagePart, live: &ToolLive, chat_id: &str) {
    let MessagePart::Tool {
        id,
        call: ToolCall::Native { view },
        is_error,
        resolved,
        output,
        output_ref,
        output_bytes,
        diff_stats,
        ..
    } = part
    else {
        return;
    };
    view.progress = live.last_progress.clone();
    if live.resolved {
        *resolved = true;
        *is_error = live.is_error;
        if let Some(text) = &live.output {
            *output = summarize_tool_output(text);
            *output_ref = Some(projection::output_ref(chat_id, id));
            *output_bytes = Some(text.len() as u64);
        }
        if live.diff_stats.is_some() {
            *diff_stats = live.diff_stats.clone();
        }
    }
}

/// Fields a re-projection must not lose: child bindings, and in a forward
/// read the results and live-only stats a later entry supplied. An exact
/// rebuild keeps only the bindings: its branch is the whole truth.
fn merge_kept(entry: &mut SessionMessageEntry, existing: &SessionMessageEntry, exact: bool) {
    for part in &mut entry.parts {
        let Some(old) = existing.parts.iter().find(|p| p.id() == part.id()) else {
            continue;
        };
        if let (
            MessagePart::Tool {
                call: ToolCall::Native { view },
                subagent_ref,
                subagent_status,
                resolved,
                is_error,
                output,
                output_ref,
                output_bytes,
                diff_stats,
                ..
            },
            MessagePart::Tool {
                call: old_call,
                subagent_ref: old_ref,
                subagent_status: old_status,
                resolved: old_resolved,
                is_error: old_error,
                output: old_output,
                output_ref: old_output_ref,
                output_bytes: old_bytes,
                diff_stats: old_stats,
                ..
            },
        ) = (part, old)
        {
            if subagent_ref.is_none() {
                *subagent_ref = old_ref.clone();
                *subagent_status = *old_status;
            }
            if let ToolCall::Native { view: old_view } = old_call {
                if view.child.is_none() {
                    view.child = old_view.child.clone();
                }
                if exact {
                    continue;
                }
                if !*resolved && *old_resolved {
                    // The saved result is in a later entry: keep the completed call.
                    **view = (**old_view).clone();
                }
            }
            if !*resolved && *old_resolved {
                *resolved = true;
                *is_error = *old_error;
                *output = old_output.clone();
                *output_ref = old_output_ref.clone();
                *output_bytes = *old_bytes;
            }
            if diff_stats.is_none() {
                *diff_stats = old_stats.clone();
            }
        }
    }
}
