//! Device-local browser tabs. GPUI owns chrome; the native host owns pages.
#[cfg(target_os = "linux")]
mod linux;
#[cfg(target_os = "macos")]
mod macos;
#[cfg(target_os = "linux")]
use linux as native;
#[cfg(target_os = "macos")]
use macos as native;
pub mod model;
#[cfg(test)]
mod preview_proxy_tests;
mod view;

use crate::composer::{ComposerInput, ComposerInputEvent};
use gpui::{
    App, AppContext, Context, Entity, EventEmitter, FocusHandle, Focusable, Subscription, Window,
};
use model::{PageState, Presentation};
gpui::actions!(
    browser,
    [Reload, FocusAddress, NewTab, CloseTab, Back, Forward]
);

pub(crate) fn bind_keys(cx: &mut App, keymap: &crate::settings::KeymapConfig) {
    use crate::settings::{ShortcutId, platform_combo};
    // Existing customized app chords win. In particular old settings may
    // already assign mod-shift-r to the right pane.
    let available = |combo: &str, own: Option<ShortcutId>| {
        !combo.is_empty()
            && !ShortcutId::ALL.iter().any(|id| {
                Some(*id) != own
                    && gpui::Keystroke::parse(&platform_combo(keymap.get(*id))).ok()
                        == gpui::Keystroke::parse(&platform_combo(combo)).ok()
            })
    };
    macro_rules! bind {
        ($combo:literal, $action:ident) => {
            if available($combo, None) {
                cx.bind_keys([gpui::KeyBinding::new(
                    &platform_combo($combo),
                    $action,
                    Some("Browser"),
                )]);
            }
        };
    }
    bind!("mod-l", FocusAddress);
    bind!("mod-t", NewTab);
    bind!("mod-w", CloseTab);
    bind!("mod-[", Back);
    bind!("mod-]", Forward);
    let reload = keymap.get(ShortcutId::BrowserReload);
    if available(reload, Some(ShortcutId::BrowserReload))
        && gpui::Keystroke::parse(&platform_combo(reload)).is_ok()
    {
        cx.bind_keys([gpui::KeyBinding::new(
            &platform_combo(reload),
            Reload,
            Some("Browser"),
        )]);
    }
}

#[derive(Clone, Debug)]
pub enum BrowserEvent {
    Changed,
    NewTab(Option<String>),
    Close,
}

/// A window/profile's ephemeral website data, allocated on first navigation.
#[derive(Clone, Default)]
pub struct BrowserContext {
    #[cfg(any(target_os = "macos", target_os = "linux"))]
    data: native::BrowserData,
}

impl BrowserContext {
    /// Route a preview's `*.localhost` address through the preview proxy
    /// before a page opens on it (macOS; elsewhere the name resolves as is).
    pub(crate) fn register_preview(&self, url: &str) {
        #[cfg(target_os = "macos")]
        self.data.register_preview(url);
        #[cfg(not(target_os = "macos"))]
        let _ = url;
    }

    /// Point the embedded browser's http/ws traffic for preview hostnames at
    /// the client preview proxy while it runs (Linux's WebKitGTK proxy
    /// configuration — stock resolvers do not synthesize `*.localhost`);
    /// `None` restores the default path. `bypass` is the hostname list that
    /// must stay direct: the local engine's previews keep their unchanged
    /// 7331 path, and loopback dev servers the user typed stay untouched.
    /// https and wss are never proxied.
    pub(crate) fn set_preview_proxy(&self, port: Option<u16>, bypass: &[String]) {
        #[cfg(target_os = "linux")]
        self.data.set_preview_proxy(port, bypass);
        #[cfg(not(target_os = "linux"))]
        let _ = (port, bypass);
    }
}

pub struct BrowserSurface {
    #[cfg(any(target_os = "macos", target_os = "linux"))]
    context: BrowserContext,
    address: Entity<ComposerInput>,
    focus: FocusHandle,
    pub page: PageState,
    pub favicon: Option<std::sync::Arc<gpui::Image>>,
    address_edited: bool,
    validation: Option<String>,
    remote: bool,
    previews: roboco_proto::PreviewSnapshot,
    previews_loading: bool,
    previews_task: Option<gpui::Task<()>>,
    /// The client-side preview proxy manager (ticket 04). Every pane holds
    /// it: a remote pane ingests its engine's snapshot and keeps the 7332
    /// listener alive; a local pane only feeds the bypass list.
    remote_previews: Option<std::sync::Arc<crate::engine_registry::PreviewProxy>>,
    #[cfg(feature = "browser-fixture")]
    fixture_preview_open: std::rc::Rc<std::cell::Cell<Option<gpui::Point<gpui::Pixels>>>>,
    presentation: Presentation,
    #[cfg(target_os = "macos")]
    resize_inset: gpui::Pixels,
    #[cfg(target_os = "macos")]
    right_occlusion: gpui::Pixels,
    _input_sub: Subscription,
    #[cfg(any(target_os = "macos", target_os = "linux"))]
    native: Option<native::NativePage>,
    #[cfg(any(target_os = "macos", target_os = "linux"))]
    native_tx: tokio::sync::mpsc::Sender<native::NativeEvent>,
    #[cfg(any(target_os = "macos", target_os = "linux"))]
    _native_task: gpui::Task<()>,
    #[cfg(target_os = "macos")]
    favicon_task: Option<gpui::Task<()>>,
    #[cfg(target_os = "macos")]
    favicon_generation: u64,
}

impl EventEmitter<BrowserEvent> for BrowserSurface {}
impl Focusable for BrowserSurface {
    fn focus_handle(&self, _: &App) -> FocusHandle {
        self.focus.clone()
    }
}

impl BrowserSurface {
    pub fn new(
        context: BrowserContext,
        remote: bool,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let address = cx.new(|cx| {
            ComposerInput::with_context("Website or localhost:3000", "PaletteSearch", cx)
                .with_text_metrics(11.0, 16.0)
                .with_single_line()
                .with_accessibility_role(gpui::Role::TextInput)
        });
        let input_sub = cx.subscribe(&address, |this, _, event, cx| {
            if matches!(event, ComposerInputEvent::Edited) {
                this.address_edited =
                    this.address.read(cx).text() != this.page.url.as_deref().unwrap_or_default();
                this.validation = None;
                cx.notify();
            }
        });
        #[cfg(any(target_os = "macos", target_os = "linux"))]
        let (native_tx, mut events) = tokio::sync::mpsc::channel(64);
        #[cfg(any(target_os = "macos", target_os = "linux"))]
        let native_task = cx.spawn_in(window, async move |this, cx| {
            while let Some(event) = events.recv().await {
                if this
                    .update_in(cx, |this, window, cx| {
                        this.on_native_event(event, window, cx)
                    })
                    .is_err()
                {
                    break;
                }
            }
        });
        #[cfg(not(any(target_os = "macos", target_os = "linux")))]
        let _ = (window, context);
        Self {
            #[cfg(any(target_os = "macos", target_os = "linux"))]
            context,
            address,
            focus: cx.focus_handle(),
            page: PageState::default(),
            favicon: None,
            address_edited: false,
            validation: None,
            remote,
            previews: roboco_proto::PreviewSnapshot::default(),
            previews_loading: true,
            previews_task: None,
            remote_previews: None,
            #[cfg(feature = "browser-fixture")]
            fixture_preview_open: Default::default(),
            presentation: Presentation::Hidden,
            #[cfg(target_os = "macos")]
            resize_inset: gpui::px(0.0),
            #[cfg(target_os = "macos")]
            right_occlusion: gpui::px(0.0),
            _input_sub: input_sub,
            #[cfg(any(target_os = "macos", target_os = "linux"))]
            native: None,
            #[cfg(any(target_os = "macos", target_os = "linux"))]
            native_tx,
            #[cfg(any(target_os = "macos", target_os = "linux"))]
            _native_task: native_task,
            #[cfg(target_os = "macos")]
            favicon_task: None,
            #[cfg(target_os = "macos")]
            favicon_generation: 0,
        }
    }

    pub fn title(&self) -> gpui::SharedString {
        self.page.label().into()
    }
    pub fn set_remote(&mut self, remote: bool) {
        self.remote = remote;
    }
    pub fn set_shortcuts(&mut self, keymap: &crate::settings::KeymapConfig) {
        #[cfg(target_os = "macos")]
        if let Some(native) = &self.native {
            native.set_shortcuts(
                crate::settings::ShortcutId::ALL
                    .iter()
                    .filter(|id| **id != crate::settings::ShortcutId::SaveFile)
                    .map(|id| crate::settings::platform_combo(keymap.get(*id)))
                    .collect(),
            );
        }
        #[cfg(not(target_os = "macos"))]
        let _ = keymap;
    }

    pub fn focus_address(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        #[cfg(target_os = "macos")]
        if let Some(native) = &self.native {
            native.focus_chrome();
        }
        window.focus(&self.address.focus_handle(cx), cx);
        window.dispatch_action(Box::new(crate::composer::SelectAll), cx);
    }

    /// Reserve the overlapping part of the shell divider for GPUI hit testing.
    #[cfg(target_os = "macos")]
    pub fn set_resize_inset(&mut self, inset: gpui::Pixels, cx: &mut Context<Self>) {
        if self.resize_inset != inset {
            self.resize_inset = inset;
            cx.notify();
        }
    }

    /// Crop a GPUI overlay out of both native painting and native hit testing.
    #[cfg(target_os = "macos")]
    pub fn set_right_occlusion(&mut self, width: gpui::Pixels, cx: &mut Context<Self>) {
        if self.right_occlusion != width {
            self.right_occlusion = width;
            cx.notify();
        }
    }

    pub fn set_presentation(&mut self, presentation: Presentation, cx: &mut Context<Self>) {
        if self.presentation == presentation {
            return;
        }
        self.presentation = presentation;
        #[cfg(any(target_os = "macos", target_os = "linux"))]
        if let Some(native) = &mut self.native {
            native.present(presentation);
        }
        cx.notify();
    }

    #[cfg(feature = "browser-fixture")]
    pub fn fixture_previews(&self) -> roboco_proto::PreviewSnapshot {
        self.previews.clone()
    }
    #[cfg(feature = "browser-fixture")]
    pub fn fixture_preview_open_position(&self) -> Option<gpui::Point<gpui::Pixels>> {
        self.fixture_preview_open.get()
    }

    /// The daemon resolves the session's current cwd for every update, so a
    /// checkout change cannot leave this tab discovering the previous project.
    ///
    /// A remote engine's snapshot is *rewritten* here (ticket 04): the
    /// services are ingested into the client preview proxy (the dormant
    /// `set_remote` semantics, driven from `WatchPreviews`), the pane keeps
    /// the returned effective hostnames, and `service.url()` renders against
    /// the client proxy's port instead of the engine's 7331. The old
    /// "Open this engine preview through a tunnel URL" degradation is gone:
    /// opening a row loads the remote dev server through the session relay.
    /// A local pane leaves its snapshot untouched and only feeds the
    /// browser-platform bypass list.
    pub fn watch_previews(
        &mut self,
        handle: crate::engine_registry::EngineTarget,
        chat_id: String,
        previews: Option<std::sync::Arc<crate::engine_registry::PreviewProxy>>,
        cx: &mut Context<Self>,
    ) {
        self.remote_previews = previews.clone();
        let engine_key = handle.key().clone();
        let remote = previews.clone().filter(|_| !engine_key.is_local());
        let local = previews.filter(|_| engine_key.is_local());
        self.previews_task = Some(cx.spawn(async move |this, cx| {
            'task: {
                // The watcher lifetime is this pane's: the task ends only
                // when the entity does, and unwatching on exit is what lets
                // the proxy's listener idle out.
                if let Some(proxy) = &remote {
                    proxy.watch(&engine_key, true);
                }
                let mut status = remote.as_ref().map(|proxy| proxy.subscribe());
                loop {
                    let subscription = handle
                        .subscribe(
                            roboco_rpc::methods::WATCH_PREVIEWS,
                            serde_json::json!({"chatId": chat_id}),
                        )
                        .await;
                    if let Ok(mut updates) = subscription {
                        loop {
                            enum Frame {
                                Update(Option<serde_json::Value>),
                                Status,
                            }
                            let frame = tokio::select! {
                                value = updates.recv() => Frame::Update(value),
                                alive = status_changed(&mut status) => {
                                    if !alive {
                                        // The proxy went away; stop polling it.
                                        status = None;
                                    }
                                    Frame::Status
                                }
                            };
                            match frame {
                                Frame::Status => {
                                    let Some(proxy) = remote.as_ref() else {
                                        status = None;
                                        continue;
                                    };
                                    let live = proxy.status();
                                    if this
                                        .update(cx, |this, cx| {
                                            if this.previews.remote {
                                                this.previews.error = live.error.clone();
                                            }
                                            this.context
                                                .set_preview_proxy(live.port, &live.bypass);
                                            cx.notify();
                                        })
                                        .is_err()
                                    {
                                        break 'task;
                                    }
                                }
                                Frame::Update(value) => {
                                    let Some(value) = value else { break };
                                    let Ok(mut snapshot) =
                                        serde_json::from_value::<roboco_proto::PreviewSnapshot>(value)
                                    else {
                                        continue;
                                    };
                                    if let Some(proxy) = &remote {
                                        snapshot.remote = true;
                                        match proxy.ingest(
                                            &engine_key,
                                            std::mem::take(&mut snapshot.services),
                                        ) {
                                            Ok(effective) => {
                                                snapshot.services = effective;
                                                // The desktop URL rewrite: remote
                                                // services render against 7332.
                                                snapshot.proxy_port =
                                                    roboco_proto::PREVIEW_CLIENT_PROXY_PORT;
                                                snapshot.error = None;
                                            }
                                            Err(error) => {
                                                snapshot.services.clear();
                                                snapshot.error = Some(format!(
                                                    "Remote previews could not load this engine's services: {error}"
                                                ));
                                            }
                                        }
                                        // A busy 7332 surfaces inline, never as
                                        // a URL change (the 7331 failure mode).
                                        let live = proxy.status();
                                        if let Some(error) = live.error {
                                            snapshot.error = Some(error);
                                        }
                                        this.update(cx, |this, _| {
                                            this.context.set_preview_proxy(live.port, &live.bypass);
                                        })
                                        .ok();
                                    } else if let Some(proxy) = &local {
                                        proxy.note_local(&snapshot.services);
                                        let live = proxy.status();
                                        this.update(cx, |this, _| {
                                            this.context.set_preview_proxy(live.port, &live.bypass);
                                        })
                                        .ok();
                                    }
                                    if this
                                        .update(cx, |this, cx| {
                                            #[cfg(target_os = "macos")]
                                            for service in &snapshot.services {
                                                this.context.data.register_preview(
                                                    &service.url(snapshot.proxy_port),
                                                );
                                            }
                                            this.previews = snapshot;
                                            this.previews_loading = false;
                                            cx.notify();
                                        })
                                        .is_err()
                                    {
                                        break 'task;
                                    }
                                }
                            }
                        }
                    }
                    if this
                        .update(cx, |this, cx| {
                            this.previews.services.clear();
                            // The reconnect state: the engine session is
                            // offline or revoked, and the relayed previews
                            // went with it.
                            this.previews.error =
                                Some("Connecting to preview discovery…".into());
                            this.previews_loading = false;
                            cx.notify();
                        })
                        .is_err()
                    {
                        break 'task;
                    }
                    cx.background_executor()
                        .timer(std::time::Duration::from_secs(2))
                        .await;
                }
            }
            if let Some(proxy) = &remote {
                proxy.watch(&engine_key, false);
            }
        }));
    }

    pub fn navigate(&mut self, input: &str, window: &mut Window, cx: &mut Context<Self>) {
        let url = match model::normalize_address(input) {
            Ok(url) => url,
            Err(message) => {
                self.validation = Some(message.into());
                cx.notify();
                return;
            }
        };
        self.validation = None;
        self.address
            .update(cx, |input, cx| input.set_text(url.clone(), cx));
        self.address_edited = false;
        self.page.url = Some(url.clone());
        self.page.title.clear();
        self.page.error = None;
        self.clear_favicon(cx);
        #[cfg(any(target_os = "macos", target_os = "linux"))]
        {
            #[cfg(target_os = "macos")]
            {
                self.favicon_generation += 1;
                self.favicon_task = None;
            }
            let result = if let Some(native) = &self.native {
                native.load(&url)
            } else {
                native::NativePage::new(window, &self.context.data, self.native_tx.clone())
                    .map(|mut native| {
                        native.present(self.presentation);
                        self.native = Some(native);
                    })
                    .and_then(|_| self.native.as_ref().unwrap().load(&url))
            };
            self.page.loading = result.is_ok();
            if let Err(error) = result {
                self.page.error = Some(format!("Could not open this page: {error}"));
            }
            window.focus(&self.focus, cx);
        }
        #[cfg(not(any(target_os = "macos", target_os = "linux")))]
        {
            let _ = window;
            cx.open_url(&url);
        }
        cx.emit(BrowserEvent::Changed);
        cx.notify();
    }

    fn submit(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let url = self.address.read(cx).text().to_owned();
        self.navigate(&url, window, cx);
    }

    fn reload(&mut self, cx: &mut Context<Self>) {
        #[cfg(any(target_os = "macos", target_os = "linux"))]
        if let Some(native) = &self.native {
            if self.page.error.is_some() {
                if let Some(url) = &self.page.url {
                    let _ = native.load(url);
                }
            } else {
                native.reload();
            }
            self.page.error = None;
        }
        #[cfg(not(any(target_os = "macos", target_os = "linux")))]
        self.open_external(cx);
        cx.notify();
    }

    fn open_external(&self, cx: &mut Context<Self>) {
        if let Some(url) = self
            .page
            .url
            .as_deref()
            .filter(|url| model::allowed_navigation(url))
        {
            cx.open_url(url);
        }
    }

    fn history(&mut self, forward: bool) {
        #[cfg(any(target_os = "macos", target_os = "linux"))]
        if let Some(native) = &self.native {
            native.history(forward);
        }
        #[cfg(not(any(target_os = "macos", target_os = "linux")))]
        let _ = forward;
    }

    /// Explicitly close even if an async callback temporarily retains an entity.
    fn clear_favicon(&mut self, cx: &mut Context<Self>) {
        if let Some(image) = self.favicon.take() {
            cx.defer(move |cx| gpui::ImageSource::Image(image).evict(None, cx));
        }
    }

    pub fn close(&mut self, cx: &mut Context<Self>) {
        self.set_presentation(Presentation::Hidden, cx);
        self.clear_favicon(cx);
        #[cfg(any(target_os = "macos", target_os = "linux"))]
        {
            if let Some(native) = &mut self.native {
                native.present(Presentation::Hidden);
            }
            #[cfg(target_os = "linux")]
            if let Some(image) = self.native.as_mut().and_then(|native| native.image.take()) {
                cx.defer(move |cx| gpui::ImageSource::Render(image).evict(None, cx));
            }
            self.native = None;
            #[cfg(target_os = "macos")]
            {
                self.favicon_task = None;
                self.favicon_generation += 1;
            }
        }
    }
}

#[cfg(target_os = "macos")]
impl BrowserSurface {
    fn on_native_event(
        &mut self,
        event: native::NativeEvent,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let Some(native) = &mut self.native else {
            return;
        };
        match event {
            native::NativeEvent::Changed | native::NativeEvent::Finished => {
                let finished = matches!(event, native::NativeEvent::Finished);
                let mut page = native.state();
                // A failed provisional request has no committed WebKit URL.
                if page.url.is_none() {
                    page.url = self.page.url.clone();
                }
                if page.error.is_some() {
                    page.loading = false;
                }
                if page.url != self.page.url || (!self.page.loading && page.loading) {
                    if let Some(image) = self.favicon.take() {
                        cx.defer(move |cx| gpui::ImageSource::Image(image).evict(None, cx));
                    }
                    self.favicon_generation += 1;
                    self.favicon_task = None;
                }
                if !self.address.focus_handle(cx).is_focused(window) {
                    if let Some(url) = &page.url {
                        if self.address.read(cx).text() != url {
                            self.address
                                .update(cx, |input, cx| input.set_text(url.clone(), cx));
                        }
                    }
                    self.address_edited = false;
                }
                native.present(self.presentation);
                if finished && let Some(url) = &page.url {
                    native.discover_favicon(url.clone());
                }
                if page != self.page {
                    self.page = page;
                    cx.emit(BrowserEvent::Changed);
                }
                cx.notify();
            }
            native::NativeEvent::NewTab(url) => {
                if self.presentation == Presentation::Live {
                    cx.emit(BrowserEvent::NewTab(Some(url)));
                }
            }
            native::NativeEvent::Key(key) => {
                if self.presentation == Presentation::Live {
                    window.focus(&self.focus, cx);
                    window.defer(cx, move |window, cx| {
                        window.dispatch_keystroke(key, cx);
                    });
                }
            }
            native::NativeEvent::Favicon { page, url } => {
                if self.page.url.as_deref() != Some(&page) || !model::allowed_navigation(&url) {
                    return;
                }
                let generation = self.favicon_generation;
                // reqwest's system proxy has no loopback exemption; a local dev
                // server's favicon must be fetched directly.
                let direct = url::Url::parse(&url).is_ok_and(|parsed| model::loopback(&parsed));
                let download = gpui_tokio::Tokio::spawn(cx, async move {
                    let mut builder = reqwest::Client::builder()
                        .timeout(std::time::Duration::from_secs(5))
                        .redirect(reqwest::redirect::Policy::limited(3));
                    if direct {
                        builder = builder.no_proxy();
                    }
                    let client = builder.build().ok()?;
                    let mut response =
                        client.get(url).send().await.ok()?.error_for_status().ok()?;
                    if response.content_length().is_some_and(|n| n > 1024 * 1024) {
                        return None;
                    }
                    let mut bytes = Vec::new();
                    while let Some(chunk) = response.chunk().await.ok()? {
                        if bytes.len() + chunk.len() > 1024 * 1024 {
                            return None;
                        }
                        bytes.extend_from_slice(&chunk);
                    }
                    let mut reader = image::ImageReader::new(std::io::Cursor::new(bytes))
                        .with_guessed_format()
                        .ok()?;
                    let mut limits = image::Limits::default();
                    limits.max_image_width = Some(1024);
                    limits.max_image_height = Some(1024);
                    limits.max_alloc = Some(8 * 1024 * 1024);
                    reader.limits(limits);
                    let icon = reader.decode().ok()?.thumbnail(32, 32);
                    let mut png = std::io::Cursor::new(Vec::new());
                    icon.write_to(&mut png, image::ImageFormat::Png).ok()?;
                    Some(png.into_inner())
                });
                self.favicon_task = Some(cx.spawn(async move |this, cx| {
                    let Ok(Some(bytes)) = download.await else {
                        return;
                    };
                    let _ = this.update(cx, |this, cx| {
                        if this.favicon_generation == generation
                            && this.page.url.as_deref() == Some(&page)
                        {
                            this.favicon = Some(std::sync::Arc::new(gpui::Image::from_bytes(
                                gpui::ImageFormat::Png,
                                bytes,
                            )));
                            cx.emit(BrowserEvent::Changed);
                            cx.notify();
                        }
                    });
                }));
            }
        }
    }
}

#[cfg(feature = "browser-fixture")]
impl BrowserSurface {
    pub fn fixture_history(&mut self, forward: bool) {
        self.history(forward);
    }
    #[cfg(target_os = "macos")]
    pub fn fixture_move_cursor(&self, x: f64, y: f64) {
        self.native.as_ref().unwrap().fixture_move_cursor(x, y);
    }
    #[cfg(target_os = "macos")]
    pub fn fixture_origin(&self) -> (f32, f32) {
        self.native.as_ref().unwrap().fixture_origin()
    }
    #[cfg(target_os = "macos")]
    pub fn fixture_overlay_visible(&self) -> bool {
        self.native.as_ref().unwrap().fixture_overlay_visible()
    }
    #[cfg(target_os = "macos")]
    pub fn fixture_visibility_changes(&self) -> u64 {
        self.native.as_ref().unwrap().fixture_visibility_changes()
    }
    #[cfg(target_os = "macos")]
    pub fn fixture_focus(&self) {
        self.native.as_ref().unwrap().fixture_focus();
    }
    #[cfg(target_os = "macos")]
    pub fn fixture_focused(&self) -> bool {
        self.native.as_ref().unwrap().fixture_focused()
    }
    #[cfg(target_os = "macos")]
    pub fn fixture_overlay_at(&self, x: f64, y: f64) -> bool {
        self.native.as_ref().unwrap().fixture_overlay_at(x, y)
    }
    #[cfg(target_os = "macos")]
    pub fn fixture_click(&self, x: f64, y: f64) {
        self.native.as_ref().unwrap().fixture_click(x, y);
    }
    pub fn fixture_native_visible(&self) -> bool {
        #[cfg(target_os = "macos")]
        {
            self.native
                .as_ref()
                .is_some_and(|native| native.fixture_visible())
        }
        #[cfg(target_os = "linux")]
        {
            self.presentation != Presentation::Hidden
                && self.native.as_ref().is_some_and(|n| n.image.is_some())
        }
        #[cfg(not(any(target_os = "macos", target_os = "linux")))]
        {
            false
        }
    }
    #[cfg(target_os = "macos")]
    pub fn fixture_backdrop_layers(&self) -> String {
        self.native.as_ref().unwrap().fixture_backdrop_layers()
    }
    #[cfg(target_os = "macos")]
    pub fn fixture_page_hit(&self, x: f64, y: f64) -> bool {
        self.native.as_ref().unwrap().fixture_page_hit(x, y)
    }
    #[cfg(target_os = "macos")]
    pub fn fixture_backdrops(&self) -> Vec<(f64, f64, f64, f64)> {
        self.native.as_ref().unwrap().fixture_backdrops()
    }
    #[cfg(target_os = "macos")]
    pub fn fixture_geometry(&self) -> (f32, f32, f32) {
        self.native.as_ref().unwrap().fixture_geometry()
    }
    pub fn fixture_eval(&self, script: &str) {
        #[cfg(target_os = "macos")]
        if let Some(native) = &self.native {
            native.fixture_eval(script);
        }
        #[cfg(target_os = "linux")]
        if let Some(native) = &self.native {
            native.evaluate(script);
        }
        #[cfg(not(any(target_os = "macos", target_os = "linux")))]
        let _ = script;
    }
}

/// Await the proxy's next status change; `false` once the proxy is gone
/// (never resolves when this pane has none to watch).
async fn status_changed(
    status: &mut Option<tokio::sync::watch::Receiver<crate::engine_registry::ClientStatus>>,
) -> bool {
    match status {
        Some(receiver) => receiver.changed().await.is_ok(),
        None => std::future::pending().await,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[gpui::test]
    fn address_enter_rejects_unsupported_schemes_and_escape_restores_url(
        cx: &mut gpui::TestAppContext,
    ) {
        cx.update(|cx| {
            gpui_base::init(cx);
            crate::composer::init(cx, Default::default());
            cx.set_global(crate::theme::Theme::default());
            bind_keys(cx, &crate::settings::KeymapConfig::default());
        });
        let window = cx.add_window(|window, cx| {
            BrowserSurface::new(BrowserContext::default(), false, window, cx)
        });
        window
            .update(cx, |browser, window, cx| {
                browser.page.url = Some("https://example.com/".into());
                browser
                    .address
                    .update(cx, |input, cx| input.set_text("javascript:alert(1)", cx));
                browser.focus_address(window, cx);
            })
            .unwrap();
        cx.simulate_keystrokes(window.into(), "enter");
        window
            .update(cx, |browser, _, _| {
                assert!(
                    browser.validation.is_some(),
                    "Enter did not reach browser address handling"
                );
                assert_eq!(browser.page.url.as_deref(), Some("https://example.com/"));
            })
            .unwrap();
        cx.simulate_keystrokes(window.into(), "escape");
        window
            .update(cx, |browser, window, cx| {
                assert!(browser.validation.is_none());
                assert_eq!(browser.address.read(cx).text(), "https://example.com/");
                assert!(browser.focus.is_focused(window));
                window.blur();
            })
            .unwrap();
    }

    #[gpui::test]
    fn customized_app_shortcuts_win_over_browser_defaults(cx: &mut gpui::TestAppContext) {
        cx.update(|cx| {
            let mut config = crate::settings::KeymapConfig::default();
            bind_keys(cx, &config);
            assert_eq!(
                cx.key_bindings()
                    .borrow()
                    .bindings_for_action(&FocusAddress)
                    .count(),
                1
            );
            assert_eq!(
                cx.key_bindings()
                    .borrow()
                    .bindings_for_action(&Reload)
                    .count(),
                1
            );
            cx.clear_key_bindings();
            config.toggle_sidebar = crate::settings::platform_combo("mod-l");
            config.toggle_changes = "mod-shift-r".into();
            bind_keys(cx, &config);
            assert_eq!(
                cx.key_bindings()
                    .borrow()
                    .bindings_for_action(&FocusAddress)
                    .count(),
                0
            );
            assert_eq!(
                cx.key_bindings()
                    .borrow()
                    .bindings_for_action(&Reload)
                    .count(),
                0
            );
            assert_eq!(
                cx.key_bindings()
                    .borrow()
                    .bindings_for_action(&NewTab)
                    .count(),
                1
            );
        });
    }
}
