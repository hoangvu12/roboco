use crate::framing::{Line, LineSplitter};
use crate::limits::{Limits, MAX_PENDING_REQUESTS, PROTOCOL_VERSION};
use crate::methods::{Call, HOST_CONTRACTS, InitializeParams, METHODS, parse_call};
use crate::outbox::Outbox;
use crate::results::{Attached, ChunkResult};
use crate::wire::{
    BridgeError, Id, Incoming, error_response, notification, parse_frame, response_bytes,
};
use futures::{
    StreamExt as _,
    future::LocalBoxFuture,
    stream::{AbortHandle, FuturesUnordered, LocalBoxStream, SelectAll, abortable},
};
use mimir_plugin_sdk::{
    CallContext, Frontend, Plugin,
    raw::session_control::{self as api, SessionError},
    wit_bindgen::{StreamReader, StreamResult, StreamWriter},
};
use serde::{Serialize, ser::SerializeMap};
use serde_json::{Value, json, value::RawValue};
use std::{
    cell::{Cell, RefCell},
    collections::HashMap,
    rc::Rc,
    task::{Context, Poll},
};

const READ_BUFFER: usize = 64 << 10;
const WRITE_BATCH: usize = 256 << 10;
const BURST: usize = 16;
const SPINS: usize = 64;

struct Bridge;

impl Plugin for Bridge {
    async fn activate(_: &CallContext) -> Result<Self, String> {
        Ok(Self)
    }
}

impl Frontend for Bridge {
    async fn run(&self, context: &CallContext) -> Result<(), String> {
        run(context).await
    }
}

mimir_plugin_sdk::export!(Bridge, frontend);

struct Attachment {
    id: String,
    session: api::Session,
    mcp: RefCell<HashMap<String, api::McpAttachment>>,
    closing: Cell<bool>,
}

struct ViewEntry {
    session: String,
    abort: AbortHandle,
}

#[derive(Default)]
struct Shared {
    sessions: RefCell<HashMap<String, Rc<Attachment>>>,
    views: RefCell<HashMap<Rc<str>, ViewEntry>>,
    next_id: Cell<u64>,
    closing_all: Cell<bool>,
}

impl Shared {
    fn fresh(&self, prefix: &str) -> String {
        let n = self.next_id.get() + 1;
        self.next_id.set(n);
        format!("{prefix}{n}")
    }

    fn attachment(&self, id: &str) -> Result<Rc<Attachment>, BridgeError> {
        let found = self.sessions.borrow().get(id).cloned();
        let attachment = found.ok_or_else(|| BridgeError::not_attached(id))?;
        if attachment.closing.get() {
            return Err(SessionError::Closed.into());
        }
        Ok(attachment)
    }

    fn abort_views(&self, session: Option<&str>) {
        self.views.borrow_mut().retain(|_, view| {
            let matches = session.is_none_or(|session| session == view.session);
            if matches {
                view.abort.abort();
            }
            !matches
        });
    }

    fn forget(&self, attachment: &Rc<Attachment>) {
        let mut sessions = self.sessions.borrow_mut();
        if sessions
            .get(&attachment.id)
            .is_some_and(|held| Rc::ptr_eq(held, attachment))
        {
            sessions.remove(&attachment.id);
        }
    }
}

/// Releases everything this bridge holds for one session and awaits it: its
/// views, then each MCP attachment, then the session's own attachment.
async fn close_attachment(
    shared: &Shared,
    ctx: &CallContext,
    attachment: &Attachment,
) -> Result<(), SessionError> {
    attachment.closing.set(true);
    shared.abort_views(Some(&attachment.id));
    let result = release(ctx, attachment).await;
    if result.is_err() {
        attachment.closing.set(false);
    }
    result
}

async fn release(ctx: &CallContext, attachment: &Attachment) -> Result<(), SessionError> {
    loop {
        let next = {
            let mut mcp = attachment.mcp.borrow_mut();
            let key = mcp.keys().next().cloned();
            key.and_then(|key| mcp.remove(&key).map(|held| (key, held)))
        };
        let Some((key, held)) = next else { break };
        if let Err(error) = held.close().await {
            attachment.mcp.borrow_mut().insert(key, held);
            return Err(error);
        }
    }
    attachment.session.close(ctx).await
}

struct NewView {
    id: Rc<str>,
    session: String,
    reader: StreamReader<api::ViewEvent>,
}

struct Reply {
    body: Vec<u8>,
    view: Option<NewView>,
}

impl Reply {
    fn json<T: Serialize + ?Sized>(value: &T) -> Result<Self, BridgeError> {
        let body = serde_json::to_vec(value).map_err(|error| {
            BridgeError::internal(format!("result could not be serialized: {error}"))
        })?;
        Ok(Self { body, view: None })
    }
}

struct One<'a, T: ?Sized>(&'static str, &'a T);

impl<T: Serialize + ?Sized> Serialize for One<'_, T> {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        let mut map = serializer.serialize_map(Some(1))?;
        map.serialize_entry(self.0, self.1)?;
        map.end()
    }
}

async fn adopt(
    shared: &Shared,
    ctx: &CallContext,
    session: api::Session,
) -> Result<Reply, BridgeError> {
    let state = match session.state(ctx).await {
        Ok(state) => state,
        Err(error) => {
            let _ = session.close(ctx).await;
            return Err(error.into());
        }
    };
    let id = state.info.id.clone();
    let existing = shared.sessions.borrow().get(&id).cloned();
    if shared.closing_all.get() || existing.is_some() {
        session.close(ctx).await?;
        if shared.closing_all.get() {
            return Err(BridgeError::shutting_down());
        }
        if existing.is_some_and(|held| held.closing.get()) {
            return Err(SessionError::Closed.into());
        }
        return Reply::json(&Attached { session: &id, state: &state, reused: true });
    }
    shared.sessions.borrow_mut().insert(
        id.clone(),
        Rc::new(Attachment {
            id: id.clone(),
            session,
            mcp: RefCell::default(),
            closing: Cell::new(false),
        }),
    );
    Reply::json(&Attached { session: &id, state: &state, reused: false })
}

async fn handle(shared: Rc<Shared>, ctx: &CallContext, call: Call) -> Result<Reply, BridgeError> {
    match call {
        Call::Initialize(_) | Call::Status | Call::Shutdown => {
            Err(BridgeError::internal("connection-level call reached the session handler"))
        }
        Call::Catalog { cwd } => Reply::json(&One("providers", &api::catalog(ctx, cwd).await?)),
        Call::List { cwd, cursor } => Reply::json(&api::list(ctx, cwd, cursor).await?),
        Call::Create { cwd, configuration } => {
            let session = api::create(ctx, cwd, configuration).await?;
            adopt(&shared, ctx, session).await
        }
        Call::Open { session } => {
            let held = shared.sessions.borrow().get(&session).cloned();
            if let Some(held) = held {
                if held.closing.get() {
                    return Err(SessionError::Closed.into());
                }
                let state = held.session.state(ctx).await?;
                return Reply::json(&Attached { session: &session, state: &state, reused: true });
            }
            let opened = api::open(ctx, session).await?;
            adopt(&shared, ctx, opened).await
        }
        Call::Close { session } => {
            let held = shared.sessions.borrow().get(&session).cloned();
            let Some(held) = held else {
                return Reply::json(&json!({ "released": false }));
            };
            close_attachment(&shared, ctx, &held).await?;
            shared.forget(&held);
            Reply::json(&json!({ "released": true }))
        }
        Call::State { session } => {
            let held = shared.attachment(&session)?;
            Reply::json(&held.session.state(ctx).await?)
        }
        Call::OpenView { session, detail } => {
            let held = shared.attachment(&session)?;
            let reader = held.session.open_view(detail)?;
            let id: Rc<str> = shared.fresh("v").into();
            let mut reply = Reply::json(&json!({ "view": &*id, "session": session }))?;
            reply.view = Some(NewView { id, session, reader });
            Ok(reply)
        }
        Call::CloseView { view } => {
            let removed = shared.views.borrow_mut().remove(view.as_str());
            let Some(removed) = removed else {
                return Err(BridgeError::unknown_view(&view));
            };
            removed.abort.abort();
            Reply::json(&json!({ "closed": true }))
        }
        Call::ReadEntries { session, selection } => {
            let held = shared.attachment(&session)?;
            Reply::json(&held.session.read_entries(ctx, selection).await?)
        }
        Call::ReadEntryChunk { session, selection } => {
            let held = shared.attachment(&session)?;
            let chunk = held.session.read_entry_chunk(ctx, selection).await?;
            Reply::json(&ChunkResult::from(chunk))
        }
        Call::Commands { session } => {
            let held = shared.attachment(&session)?;
            Reply::json(&One("commands", &held.session.commands()?))
        }
        Call::Command { session, name, tail } => {
            let held = shared.attachment(&session)?;
            Reply::json(&One("result", &held.session.command(ctx, name, tail).await?))
        }
        Call::Skills { session } => {
            let held = shared.attachment(&session)?;
            Reply::json(&One("skills", &held.session.skills()?))
        }
        Call::Plan { session } => {
            let held = shared.attachment(&session)?;
            Reply::json(&One("plan", &held.session.plan(ctx).await?))
        }
        Call::Children { session } => {
            let held = shared.attachment(&session)?;
            Reply::json(&One("children", &held.session.children(ctx).await?))
        }
        Call::ChildOutcome { session, handle, attempt } => {
            let held = shared.attachment(&session)?;
            Reply::json(&One("outcome", &held.session.child_outcome(&handle, attempt)?))
        }
        Call::SteerChild { session, handle, attempt, text } => {
            let held = shared.attachment(&session)?;
            Reply::json(&One("control", &held.session.steer_child(&handle, attempt, &text)?))
        }
        Call::StopChild { session, handle, attempt } => {
            let held = shared.attachment(&session)?;
            Reply::json(&One("control", &held.session.stop_child(&handle, attempt)?))
        }
        Call::Prompt { session, input, delivery, submission_key } => {
            let held = shared.attachment(&session)?;
            let request_id = held.session.prompt(ctx, input, delivery, submission_key).await?;
            Reply::json(&json!({ "request_id": request_id }))
        }
        Call::Steer { session, text } => {
            shared.attachment(&session)?.session.steer(&text)?;
            Reply::json(&json!({}))
        }
        Call::Configure { session, change } => {
            let held = shared.attachment(&session)?;
            Reply::json(&One("configuration", &held.session.configure(ctx, change).await?))
        }
        Call::DecidePlan { session, plan_id, decision } => {
            let held = shared.attachment(&session)?;
            let request_id = held.session.decide_plan(ctx, plan_id, decision).await?;
            Reply::json(&json!({ "request_id": request_id }))
        }
        Call::ChangeGoal { session, change } => {
            let held = shared.attachment(&session)?;
            let request_id = held.session.change_goal(ctx, change).await?;
            Reply::json(&json!({ "request_id": request_id }))
        }
        Call::Answer { session, request_id, answers } => {
            shared.attachment(&session)?.session.answer(&request_id, &answers)?;
            Reply::json(&json!({}))
        }
        Call::CancelRequest { session, request_id } => {
            let held = shared.attachment(&session)?;
            let cancelled = held.session.cancel_request(ctx, request_id).await?;
            Reply::json(&json!({ "cancelled": cancelled }))
        }
        Call::CancelAll { session } => {
            shared.attachment(&session)?.session.cancel_all();
            Reply::json(&json!({}))
        }
        Call::AttachMcp { session, servers } => {
            let held = shared.attachment(&session)?;
            let attachment = held.session.attach_mcp(ctx, servers).await?;
            if held.closing.get() || shared.closing_all.get() {
                attachment.close().await?;
                return Err(SessionError::Closed.into());
            }
            let id = shared.fresh("m");
            held.mcp.borrow_mut().insert(id.clone(), attachment);
            Reply::json(&json!({ "attachment": id, "session": session }))
        }
        Call::DetachMcp { session, attachment } => {
            let held = shared.attachment(&session)?;
            let found = held.mcp.borrow_mut().remove(&attachment);
            let Some(found) = found else {
                return Err(BridgeError::unknown_attachment(&attachment));
            };
            if let Err(error) = found.close().await {
                held.mcp.borrow_mut().insert(attachment, found);
                return Err(error.into());
            }
            Reply::json(&json!({ "closed": true }))
        }
    }
}

enum HubItem {
    Event(Rc<str>, api::ViewEvent),
    Ended(Rc<str>),
}

type HubStream = LocalBoxStream<'static, HubItem>;

fn hub_stream(view: Rc<str>, reader: StreamReader<api::ViewEvent>) -> (HubStream, AbortHandle) {
    let tag = view.clone();
    let events = futures::stream::unfold(reader, |mut reader| async move {
        reader.next().await.map(|event| (event, reader))
    })
    .map(move |event| HubItem::Event(tag.clone(), event))
        .chain(futures::stream::once(async move { HubItem::Ended(view) }));
    let (stream, handle) = abortable(events);
    (stream.boxed_local(), handle)
}

#[derive(Serialize)]
struct ViewEventParams<'a> {
    view: &'a str,
    position: u64,
    item: &'a api::ViewItem,
}

type Done = (Id, Result<Reply, BridgeError>);
type Closed = (String, Result<(), SessionError>);
type ReadDone = (StreamReader<u8>, StreamResult, Vec<u8>);
type WriteDone = (StreamWriter<u8>, Vec<u8>);

async fn read_once(mut stream: StreamReader<u8>) -> ReadDone {
    let (status, bytes) = stream.read(Vec::with_capacity(READ_BUFFER)).await;
    (stream, status, bytes)
}

async fn write_once(mut stream: StreamWriter<u8>, bytes: Vec<u8>) -> WriteDone {
    let remaining = stream.write_all(bytes).await;
    (stream, remaining)
}

enum Reader {
    Idle(StreamReader<u8>),
    Busy(LocalBoxFuture<'static, ReadDone>),
    Eof,
    Detached,
}

enum Writer {
    Idle(StreamWriter<u8>),
    Busy(LocalBoxFuture<'static, WriteDone>),
    Closed,
    Detached,
}

enum Phase {
    Running,
    Shutdown { id: Id, replied: bool },
    Ending,
}

struct Server<'a> {
    ctx: &'a CallContext,
    shared: Rc<Shared>,
    limits: Limits,
    initialized: bool,
    splitter: LineSplitter,
    outbox: Outbox,
    reader: Reader,
    writer: Writer,
    pending: FuturesUnordered<LocalBoxFuture<'a, Done>>,
    closers: FuturesUnordered<LocalBoxFuture<'a, Closed>>,
    hub: SelectAll<HubStream>,
    phase: Phase,
    eof: bool,
    closed_sessions: usize,
    failures: Vec<String>,
}

impl<'a> Server<'a> {
    fn new(ctx: &'a CallContext, input: StreamReader<u8>, output: StreamWriter<u8>) -> Self {
        let limits = Limits::default();
        Self {
            ctx,
            shared: Rc::new(Shared::default()),
            limits,
            initialized: false,
            splitter: LineSplitter::new(limits.max_frame_bytes),
            outbox: Outbox::new(limits),
            reader: Reader::Idle(input),
            writer: Writer::Idle(output),
            pending: FuturesUnordered::new(),
            closers: FuturesUnordered::new(),
            hub: SelectAll::new(),
            phase: Phase::Running,
            eof: false,
            closed_sessions: 0,
            failures: Vec::new(),
        }
    }

    fn send_control(&mut self, frame: Vec<u8>) {
        if !matches!(self.writer, Writer::Closed) {
            self.outbox.push_control(frame);
        }
    }

    fn send_event(&mut self, frame: Vec<u8>) {
        if !matches!(self.writer, Writer::Closed) {
            self.outbox.push_event(frame);
        }
    }

    fn reply_error(&mut self, id: Option<&Id>, error: &BridgeError) {
        self.send_control(error_response(id, error));
    }

    fn can_read(&self) -> bool {
        matches!(self.phase, Phase::Running | Phase::Shutdown { .. })
            && !self.outbox.control_saturated()
    }

    fn settled(&self) -> bool {
        self.pending.is_empty() && self.closers.is_empty()
    }

    fn drained(&self) -> bool {
        match self.writer {
            Writer::Closed => true,
            Writer::Idle(_) => self.outbox.is_empty(),
            Writer::Busy(_) | Writer::Detached => false,
        }
    }

    fn finished(&self) -> bool {
        match &self.phase {
            Phase::Running => false,
            Phase::Shutdown { replied, .. } => *replied && self.drained(),
            Phase::Ending => self.settled() && self.drained(),
        }
    }

    fn poll_step(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), String>> {
        for _ in 0..SPINS {
            let mut progress = false;
            progress |= self.poll_writer(cx);
            progress |= self.poll_reader(cx);
            progress |= self.poll_pending(cx);
            progress |= self.poll_closers(cx);
            progress |= self.poll_hub(cx);
            progress |= self.maybe_reply_shutdown();
            if self.finished() {
                return Poll::Ready(match self.failures.is_empty() {
                    true => Ok(()),
                    false => Err(self.failures.join("; ")),
                });
            }
            if !progress {
                return Poll::Pending;
            }
        }
        cx.waker().wake_by_ref();
        Poll::Pending
    }

    fn poll_writer(&mut self, cx: &mut Context<'_>) -> bool {
        let mut progress = false;
        loop {
            match std::mem::replace(&mut self.writer, Writer::Detached) {
                Writer::Idle(stream) => {
                    if self.outbox.is_empty() {
                        self.writer = Writer::Idle(stream);
                        return progress;
                    }
                    let bytes = self.outbox.next_write(WRITE_BATCH);
                    self.writer = Writer::Busy(Box::pin(write_once(stream, bytes)));
                }
                Writer::Busy(mut write) => match write.as_mut().poll(cx) {
                    Poll::Pending => {
                        self.writer = Writer::Busy(write);
                        return progress;
                    }
                    Poll::Ready((stream, remaining)) => {
                        progress = true;
                        if remaining.is_empty() {
                            self.writer = Writer::Idle(stream);
                        } else {
                            self.writer = Writer::Closed;
                            self.outbox = Outbox::new(self.limits);
                            self.end(Some("stdout was closed by the host".into()));
                            return true;
                        }
                    }
                },
                other => {
                    self.writer = other;
                    return progress;
                }
            }
        }
    }

    fn poll_reader(&mut self, cx: &mut Context<'_>) -> bool {
        let mut progress = false;
        loop {
            match std::mem::replace(&mut self.reader, Reader::Detached) {
                Reader::Idle(stream) => {
                    if !self.can_read() {
                        self.reader = Reader::Idle(stream);
                        return progress;
                    }
                    self.reader = Reader::Busy(Box::pin(read_once(stream)));
                }
                Reader::Busy(mut read) => match read.as_mut().poll(cx) {
                    Poll::Pending => {
                        self.reader = Reader::Busy(read);
                        return progress;
                    }
                    Poll::Ready((stream, status, bytes)) => {
                        progress = true;
                        let mut lines = Vec::new();
                        self.splitter.push(&bytes, &mut lines);
                        if status == StreamResult::Dropped {
                            self.splitter.finish(&mut lines);
                            self.eof = true;
                            self.reader = Reader::Eof;
                            drop(stream);
                            self.handle_lines(lines);
                            self.end(None);
                            return true;
                        }
                        self.reader = Reader::Idle(stream);
                        self.handle_lines(lines);
                    }
                },
                other => {
                    self.reader = other;
                    return progress;
                }
            }
        }
    }

    fn poll_pending(&mut self, cx: &mut Context<'_>) -> bool {
        let mut progress = false;
        for _ in 0..BURST {
            if self.outbox.control_saturated() {
                break;
            }
            match self.pending.poll_next_unpin(cx) {
                Poll::Ready(Some((id, result))) => {
                    progress = true;
                    self.finish_request(id, result);
                }
                _ => break,
            }
        }
        progress
    }

    fn poll_closers(&mut self, cx: &mut Context<'_>) -> bool {
        let mut progress = false;
        for _ in 0..BURST {
            match self.closers.poll_next_unpin(cx) {
                Poll::Ready(Some((session, result))) => {
                    progress = true;
                    match result {
                        Ok(()) => self.closed_sessions += 1,
                        Err(error) => {
                            let message = format!("closing session {session}: {}", error.message());
                            eprintln!("roboco-bridge: {message}");
                            self.failures.push(message);
                        }
                    }
                }
                _ => break,
            }
        }
        progress
    }

    fn poll_hub(&mut self, cx: &mut Context<'_>) -> bool {
        let mut progress = false;
        for _ in 0..BURST {
            if self.outbox.events_saturated() {
                break;
            }
            match self.hub.poll_next_unpin(cx) {
                Poll::Ready(Some(item)) => {
                    progress = true;
                    self.hub_item(item);
                }
                _ => break,
            }
        }
        progress
    }

    fn hub_item(&mut self, item: HubItem) {
        let frame = match item {
            HubItem::Event(view, event) => notification(
                "view.event",
                &ViewEventParams { view: &view, position: event.position, item: &event.item },
            )
            .or_else(|error| {
                notification(
                    "view.event_error",
                    &json!({
                        "view": &*view,
                        "position": event.position,
                        "message": format!("event could not be serialized: {error}"),
                    }),
                )
            }),
            HubItem::Ended(view) => {
                self.shared.views.borrow_mut().remove(&*view);
                notification("view.ended", &json!({ "view": &*view, "reason": "host_stream_ended" }))
            }
        };
        match frame {
            Ok(frame) => self.send_event(frame),
            Err(error) => eprintln!("roboco-bridge: dropped an unserializable view notice: {error}"),
        }
    }

    fn finish_request(&mut self, id: Id, result: Result<Reply, BridgeError>) {
        match result {
            Ok(reply) => {
                self.send_control(response_bytes(&id, &reply.body));
                if let Some(view) = reply.view {
                    self.register_view(view);
                }
            }
            Err(error) => self.reply_error(Some(&id), &error),
        }
    }

    fn register_view(&mut self, view: NewView) {
        if self.shared.closing_all.get() {
            return;
        }
        let (stream, abort) = hub_stream(view.id.clone(), view.reader);
        self.shared
            .views
            .borrow_mut()
            .insert(view.id, ViewEntry { session: view.session, abort });
        self.hub.push(stream);
    }

    fn handle_lines(&mut self, lines: Vec<Line>) {
        for line in lines {
            match line {
                Line::Oversized => {
                    let error = BridgeError::frame_too_large(self.limits.max_frame_bytes);
                    self.reply_error(None, &error);
                }
                Line::Frame(bytes) => match parse_frame(&bytes) {
                    Incoming::Invalid { id, error } => self.reply_error(id.as_ref(), &error),
                    Incoming::Response | Incoming::Notification { .. } => {}
                    Incoming::Request { id, method, params } => self.dispatch(id, &method, params),
                },
            }
        }
    }

    fn dispatch(&mut self, id: Id, method: &str, params: Option<Box<RawValue>>) {
        let call = match parse_call(method, params.as_deref(), &self.limits) {
            Ok(call) => call,
            Err(error) => return self.reply_error(Some(&id), &error),
        };
        if !matches!(self.phase, Phase::Running) {
            return self.reply_error(Some(&id), &BridgeError::shutting_down());
        }
        match call {
            Call::Initialize(params) => match self.initialize(params) {
                Ok(body) => self.send_control(response_bytes(&id, &body)),
                Err(error) => self.reply_error(Some(&id), &error),
            },
            _ if !self.initialized => self.reply_error(Some(&id), &BridgeError::not_initialized()),
            Call::Status => {
                let body = self.status();
                self.send_control(response_bytes(&id, &body));
            }
            Call::Shutdown => {
                self.phase = Phase::Shutdown { id, replied: false };
                self.close_everything();
            }
            call => {
                if self.pending.len() >= MAX_PENDING_REQUESTS {
                    let error = BridgeError::overloaded(MAX_PENDING_REQUESTS);
                    return self.reply_error(Some(&id), &error);
                }
                let shared = self.shared.clone();
                let ctx = self.ctx;
                self.pending.push(Box::pin(async move {
                    let result = handle(shared, ctx, call).await;
                    (id, result)
                }));
            }
        }
    }

    fn initialize(&mut self, params: InitializeParams) -> Result<Vec<u8>, BridgeError> {
        if self.initialized {
            return Err(BridgeError::already_initialized());
        }
        if !params.protocol_versions.contains(&PROTOCOL_VERSION) {
            return Err(BridgeError::new(
                crate::wire::code::INCOMPATIBLE_PROTOCOL,
                "incompatible_protocol",
                format!("this bridge speaks protocol version {PROTOCOL_VERSION}"),
            )
            .with_data("supported", json!([PROTOCOL_VERSION])));
        }
        let limits = Limits::negotiate(params.limits.unwrap_or_default())?;
        self.limits = limits;
        self.outbox.set_limits(limits);
        self.splitter.set_max(limits.max_frame_bytes);
        self.initialized = true;
        let contracts: serde_json::Map<String, Value> = HOST_CONTRACTS
            .iter()
            .map(|(name, version)| ((*name).to_owned(), json!(version)))
            .collect();
        let body = json!({
            "protocol_version": PROTOCOL_VERSION,
            "bridge": {
                "id": "sh.roboco.bridge",
                "name": "Roboco bridge",
                "version": env!("CARGO_PKG_VERSION"),
            },
            "client": params.client.as_ref().map(|client| json!({ "name": client.name, "version": client.version })),
            "limits": limits,
            "host_contracts": contracts,
            "capabilities": serde_json::to_value(mimir_plugin_sdk::raw::plugin_runtime::capabilities())
                .map_err(|error| BridgeError::internal(error.to_string()))?,
            "methods": METHODS,
            "features": {
                "fragments": crate::outbox::FRAGMENT_METHOD,
                "chunk_encoding": "base64",
                "prompt_image_encoding": "base64",
                "max_pending_requests": MAX_PENDING_REQUESTS,
                "notifications": ["view.event", "view.ended", "view.event_error", crate::outbox::FRAGMENT_METHOD],
            },
            "ready": true,
        });
        serde_json::to_vec(&body).map_err(|error| BridgeError::internal(error.to_string()))
    }

    fn status(&self) -> Vec<u8> {
        let sessions: Vec<Value> = self
            .shared
            .sessions
            .borrow()
            .values()
            .map(|held| {
                json!({
                    "session": held.id,
                    "mcp_attachments": held.mcp.borrow().len(),
                    "closing": held.closing.get(),
                })
            })
            .collect();
        let views: Vec<Value> = self
            .shared
            .views
            .borrow()
            .iter()
            .map(|(view, entry)| json!({ "view": &**view, "session": entry.session }))
            .collect();
        let body = json!({
            "initialized": self.initialized,
            "sessions": sessions,
            "views": views,
            "pending_requests": self.pending.len(),
            "queued_output_bytes": self.outbox.queued_bytes(),
            "limits": self.limits,
        });
        serde_json::to_vec(&body).expect("a status value serializes")
    }

    fn close_everything(&mut self) {
        self.shared.closing_all.set(true);
        self.hub = SelectAll::new();
        self.shared.abort_views(None);
        let held: Vec<Rc<Attachment>> = self.shared.sessions.borrow().values().cloned().collect();
        for attachment in held {
            let shared = self.shared.clone();
            let ctx = self.ctx;
            self.closers.push(Box::pin(async move {
                let result = close_attachment(&shared, ctx, &attachment).await;
                if result.is_ok() {
                    shared.forget(&attachment);
                }
                (attachment.id.clone(), result)
            }));
        }
    }

    fn end(&mut self, failure: Option<String>) {
        if let Some(failure) = failure {
            eprintln!("roboco-bridge: {failure}");
            self.failures.push(failure);
        }
        if matches!(self.phase, Phase::Running) {
            self.phase = Phase::Ending;
        }
        if !self.shared.closing_all.get() {
            self.close_everything();
        }
        if matches!(self.phase, Phase::Ending) {
            self.reader = Reader::Eof;
        }
    }

    fn maybe_reply_shutdown(&mut self) -> bool {
        let Phase::Shutdown { id, replied: false } = &self.phase else {
            return false;
        };
        if !self.settled() {
            return false;
        }
        let id = id.clone();
        let body = serde_json::to_vec(&json!({ "closed_sessions": self.closed_sessions }))
            .expect("a count serializes");
        self.send_control(response_bytes(&id, &body));
        self.phase = Phase::Shutdown { id, replied: true };
        true
    }

    fn into_streams(self) -> bool {
        self.eof
    }
}

async fn run(ctx: &CallContext) -> Result<(), String> {
    let (input, input_done) = wasip3::cli::stdin::read_via_stream();
    let (output, bytes) = wasip3::wit_stream::new();
    let output_done = wasip3::cli::stdout::write_via_stream(bytes);
    let mut server = Server::new(ctx, input, output);
    let outcome = std::future::poll_fn(|cx| server.poll_step(cx)).await;
    let eof = server.into_streams();
    let output_result = output_done.await;
    let input_result = if eof { Some(input_done.await) } else { None };
    outcome?;
    output_result.map_err(|error| format!("stdout: {error:?}"))?;
    if let Some(result) = input_result {
        result.map_err(|error| format!("stdin: {error:?}"))?;
    }
    Ok(())
}
