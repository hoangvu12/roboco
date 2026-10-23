//! The core's `tracing` output on Android's logcat.
//!
//! Without a subscriber every `tracing` event the core emits (link ends,
//! reconnect reasons, probe failures) is dropped, so a phone in the field
//! shows nothing. [`install`] puts a minimal layer in place at core init:
//! each event becomes one `__android_log_write` call under the tag
//! [`TAG`] (`adb logcat -s RobocoCore`). No Google or NDK-glue crates: the
//! one extern is declared here and links the platform's `liblog`.
//!
//! The filter keeps INFO and above for Roboco's own crates (targets
//! starting with `roboco`) and WARN and above for everything else, so the
//! dependencies' chatter stays out. `log`-crate records (tungstenite and
//! friends) are bridged through `tracing-log` by `try_init`.
//!
//! Only Android installs anything: on desktop and in CI [`install`] is a
//! no-op, and the layer and filter compile there only so their unit tests
//! run on the host.

#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use std::fmt::Write as _;

use tracing::field::{Field, Visit};
use tracing::{Event, Level, Subscriber};
use tracing_subscriber::filter::Targets;
use tracing_subscriber::layer::{Context, Layer};

/// The logcat tag every core line carries.
pub const TAG: &str = "RobocoCore";

/// `android/log.h` priorities.
const ANDROID_LOG_VERBOSE: i32 = 2;
const ANDROID_LOG_DEBUG: i32 = 3;
const ANDROID_LOG_INFO: i32 = 4;
const ANDROID_LOG_WARN: i32 = 5;
const ANDROID_LOG_ERROR: i32 = 6;

/// Install the logcat subscriber as the process-wide default. Idempotent:
/// the first call installs, later calls (every `CoreClient::new`, every
/// `core_version`) return at once. A no-op off Android.
pub fn install() {
    #[cfg(target_os = "android")]
    {
        use tracing_subscriber::layer::SubscriberExt as _;
        use tracing_subscriber::util::SubscriberInitExt as _;

        static ONCE: std::sync::Once = std::sync::Once::new();
        ONCE.call_once(|| {
            let layer = LogcatLayer::new(android::write).with_filter(filter());
            // `try_init` also bridges `log` records; an error means another
            // subscriber won the race (a host app's own), which is fine.
            let _ = tracing_subscriber::registry().with(layer).try_init();
        });
    }
}

/// INFO for Roboco's crates, WARN for the rest.
pub(crate) fn filter() -> Targets {
    Targets::new()
        .with_target("roboco", Level::INFO)
        .with_default(Level::WARN)
}

/// The logcat priority of a `tracing` level.
fn priority(level: &Level) -> i32 {
    match *level {
        Level::TRACE => ANDROID_LOG_VERBOSE,
        Level::DEBUG => ANDROID_LOG_DEBUG,
        Level::INFO => ANDROID_LOG_INFO,
        Level::WARN => ANDROID_LOG_WARN,
        Level::ERROR => ANDROID_LOG_ERROR,
    }
}

/// Formats each event as `target: message key=value …` and hands it, with
/// its priority, to `write` (logcat on Android; a capture in tests).
pub(crate) struct LogcatLayer<W> {
    write: W,
}

impl<W> LogcatLayer<W>
where
    W: Fn(i32, &str) + Send + Sync + 'static,
{
    pub(crate) fn new(write: W) -> Self {
        Self { write }
    }
}

impl<S, W> Layer<S> for LogcatLayer<W>
where
    S: Subscriber,
    W: Fn(i32, &str) + Send + Sync + 'static,
{
    fn on_event(&self, event: &Event<'_>, _ctx: Context<'_, S>) {
        let meta = event.metadata();
        let mut fields = Fields::default();
        event.record(&mut fields);
        // A bridged `log` record carries its real target as a field.
        let target = fields.log_target.as_deref().unwrap_or(meta.target());
        let mut line = String::with_capacity(target.len() + fields.message.len() + fields.rest.len() + 2);
        line.push_str(target);
        line.push_str(": ");
        line.push_str(&fields.message);
        line.push_str(&fields.rest);
        (self.write)(priority(meta.level()), &line);
    }
}

/// The event's `message` plus every other field as ` key=value`. The
/// `log.*` fields `tracing-log` adds to bridged `log` records are dropped,
/// except `log.target`, which names the line instead of the bridge's own.
#[derive(Default)]
struct Fields {
    message: String,
    rest: String,
    log_target: Option<String>,
}

impl Visit for Fields {
    fn record_str(&mut self, field: &Field, value: &str) {
        match field.name() {
            "message" => self.message.push_str(value),
            "log.target" => self.log_target = Some(value.to_owned()),
            name if name.starts_with("log.") => {}
            name => {
                let _ = write!(self.rest, " {name}={value}");
            }
        }
    }

    fn record_debug(&mut self, field: &Field, value: &dyn std::fmt::Debug) {
        match field.name() {
            "message" => {
                let _ = write!(self.message, "{value:?}");
            }
            name if name.starts_with("log.") => {}
            name => {
                let _ = write!(self.rest, " {name}={value:?}");
            }
        }
    }
}

#[cfg(target_os = "android")]
mod android {
    use std::ffi::{CString, c_char, c_int};

    #[link(name = "log")]
    unsafe extern "C" {
        fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }

    /// One logcat line. Interior NULs would truncate the C string, so they
    /// are escaped; liblog itself truncates lines past its ~4 KB limit.
    pub(super) fn write(priority: i32, message: &str) {
        let text = match CString::new(message) {
            Ok(text) => text,
            Err(_) => CString::new(message.replace('\0', "\\0")).unwrap_or_default(),
        };
        // SAFETY: both pointers are valid NUL-terminated strings that
        // outlive the call; liblog copies them before returning.
        unsafe {
            __android_log_write(priority, c"RobocoCore".as_ptr(), text.as_ptr());
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};

    use tracing_subscriber::layer::SubscriberExt as _;

    use super::*;

    fn capture() -> (Arc<Mutex<Vec<(i32, String)>>>, impl Subscriber + Send + Sync) {
        let lines = Arc::new(Mutex::new(Vec::new()));
        let sink = lines.clone();
        let layer = LogcatLayer::new(move |priority, line: &str| {
            sink.lock().unwrap().push((priority, line.to_owned()));
        })
        .with_filter(filter());
        (lines, tracing_subscriber::registry().with(layer))
    }

    #[test]
    fn roboco_crates_log_at_info_and_others_at_warn() {
        let filter = filter();
        assert!(filter.would_enable("roboco_mobile_client::engines::link", &Level::INFO));
        assert!(!filter.would_enable("roboco_mobile_client::engines::link", &Level::DEBUG));
        assert!(!filter.would_enable("tungstenite::protocol", &Level::INFO));
        assert!(filter.would_enable("tungstenite::protocol", &Level::WARN));
    }

    #[test]
    fn an_event_becomes_one_line_with_its_priority_and_fields() {
        let (lines, subscriber) = capture();
        tracing::subscriber::with_default(subscriber, || {
            tracing::warn!(target: "roboco_mobile_client::engines::link", engine = "e1", reason = ?Some(3), "link ended");
            tracing::info!(target: "roboco_rpc::pump", "dial ok");
            tracing::info!(target: "hyper::client", "filtered out");
            tracing::error!(target: "hyper::client", "kept");
        });
        let lines = lines.lock().unwrap();
        assert_eq!(
            *lines,
            vec![
                (
                    ANDROID_LOG_WARN,
                    "roboco_mobile_client::engines::link: link ended engine=e1 reason=Some(3)".to_owned()
                ),
                (ANDROID_LOG_INFO, "roboco_rpc::pump: dial ok".to_owned()),
                (ANDROID_LOG_ERROR, "hyper::client: kept".to_owned()),
            ]
        );
    }

    #[test]
    fn install_is_idempotent_and_a_no_op_off_android() {
        install();
        install();
    }
}
