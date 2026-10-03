use crate::limits::MAX_ID_BYTES;
use mimir_plugin_sdk::raw::session_control::SessionError;
use serde::{Deserialize, Deserializer, Serialize, de::IgnoredAny};
use serde_json::{Value, json, value::RawValue};

/// One serialized JSON-RPC message without its terminating newline.
pub type Frame = Vec<u8>;

pub mod code {
    pub const PARSE_ERROR: i32 = -32700;
    pub const INVALID_REQUEST: i32 = -32600;
    pub const METHOD_NOT_FOUND: i32 = -32601;
    pub const INVALID_PARAMS: i32 = -32602;
    pub const INTERNAL: i32 = -32603;

    pub const CLOSED: i32 = -32001;
    pub const NOT_READY: i32 = -32002;
    pub const NOT_FOUND: i32 = -32003;
    pub const BUSY: i32 = -32004;
    pub const INVALID: i32 = -32005;
    pub const CANCELLED: i32 = -32006;
    pub const FAILED: i32 = -32007;

    pub const NOT_ATTACHED: i32 = -32010;
    pub const OVERLOADED: i32 = -32011;
    pub const FRAME_TOO_LARGE: i32 = -32012;
    pub const NOT_INITIALIZED: i32 = -32013;
    pub const ALREADY_INITIALIZED: i32 = -32014;
    pub const INCOMPATIBLE_PROTOCOL: i32 = -32015;
    pub const SHUTTING_DOWN: i32 = -32016;
    pub const UNKNOWN_VIEW: i32 = -32017;
    pub const UNKNOWN_ATTACHMENT: i32 = -32018;
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct BridgeError {
    pub code: i32,
    pub message: String,
    pub data: Value,
}

impl BridgeError {
    pub fn new(code: i32, kind: &str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
            data: json!({ "kind": kind }),
        }
    }

    pub fn with_data(mut self, key: &str, value: Value) -> Self {
        if let Value::Object(map) = &mut self.data {
            map.insert(key.into(), value);
        }
        self
    }

    pub fn parse_error(message: impl Into<String>) -> Self {
        Self::new(code::PARSE_ERROR, "parse_error", message)
    }

    pub fn invalid_request(message: impl Into<String>) -> Self {
        Self::new(code::INVALID_REQUEST, "invalid_request", message)
    }

    pub fn method_not_found(method: &str) -> Self {
        Self::new(
            code::METHOD_NOT_FOUND,
            "method_not_found",
            format!("unknown method {method:?}"),
        )
    }

    pub fn invalid_params(message: impl Into<String>) -> Self {
        Self::new(code::INVALID_PARAMS, "invalid_params", message)
    }

    pub fn internal(message: impl Into<String>) -> Self {
        Self::new(code::INTERNAL, "internal", message)
    }

    pub fn not_initialized() -> Self {
        Self::new(
            code::NOT_INITIALIZED,
            "not_initialized",
            "send initialize before any other request",
        )
    }

    pub fn already_initialized() -> Self {
        Self::new(
            code::ALREADY_INITIALIZED,
            "already_initialized",
            "initialize already completed on this connection",
        )
    }

    pub fn shutting_down() -> Self {
        Self::new(
            code::SHUTTING_DOWN,
            "shutting_down",
            "the bridge is closing its sessions",
        )
    }

    pub fn overloaded(limit: usize) -> Self {
        Self::new(
            code::OVERLOADED,
            "overloaded",
            format!("more than {limit} requests are pending; retry after a response"),
        )
    }

    pub fn not_attached(session: &str) -> Self {
        Self::new(
            code::NOT_ATTACHED,
            "not_attached",
            format!("session {session:?} is not attached to this bridge; create or open it first"),
        )
    }

    pub fn unknown_view(view: &str) -> Self {
        Self::new(code::UNKNOWN_VIEW, "unknown_view", format!("no open view {view:?}"))
    }

    pub fn unknown_attachment(attachment: &str) -> Self {
        Self::new(
            code::UNKNOWN_ATTACHMENT,
            "unknown_attachment",
            format!("no MCP attachment {attachment:?}"),
        )
    }

    pub fn frame_too_large(limit: usize) -> Self {
        Self::new(
            code::FRAME_TOO_LARGE,
            "frame_too_large",
            format!("a frame exceeded the negotiated {limit} byte limit and was discarded"),
        )
        .with_data("max_frame_bytes", json!(limit))
    }
}

impl From<&SessionError> for BridgeError {
    fn from(error: &SessionError) -> Self {
        let (code, kind) = match error {
            SessionError::Closed => (code::CLOSED, "closed"),
            SessionError::NotReady => (code::NOT_READY, "not_ready"),
            SessionError::NotFound(_) => (code::NOT_FOUND, "not_found"),
            SessionError::Busy(_) => (code::BUSY, "busy"),
            SessionError::Invalid(_) => (code::INVALID, "invalid"),
            SessionError::Cancelled => (code::CANCELLED, "cancelled"),
            SessionError::Failed(_) => (code::FAILED, "failed"),
        };
        Self::new(code, kind, error.message())
    }
}

impl From<SessionError> for BridgeError {
    fn from(error: SessionError) -> Self {
        Self::from(&error)
    }
}

/// A request id echoed byte for byte: a string or a number of at most
/// `MAX_ID_BYTES` serialized bytes.
#[derive(Debug, Clone)]
pub struct Id(Box<RawValue>);

impl Id {
    fn new(raw: Box<RawValue>) -> Result<Self, BridgeError> {
        let text = raw.get();
        let shaped = matches!(text.as_bytes().first(), Some(b'"' | b'-' | b'0'..=b'9'));
        if !shaped {
            return Err(BridgeError::invalid_request(
                "id must be a string or a number",
            ));
        }
        if text.len() > MAX_ID_BYTES {
            return Err(BridgeError::invalid_request(format!(
                "id is longer than {MAX_ID_BYTES} bytes"
            )));
        }
        Ok(Self(raw))
    }

    pub fn raw(&self) -> &str {
        self.0.get()
    }
}

#[derive(Debug)]
pub enum Incoming {
    Request {
        id: Id,
        method: String,
        params: Option<Box<RawValue>>,
    },
    Notification {
        method: String,
        params: Option<Box<RawValue>>,
    },
    /// A reply to something the bridge sent. The bridge sends no requests.
    Response,
    Invalid {
        id: Option<Id>,
        error: BridgeError,
    },
}

fn present<'de, D, T>(deserializer: D) -> Result<Option<T>, D::Error>
where
    D: Deserializer<'de>,
    T: Deserialize<'de>,
{
    T::deserialize(deserializer).map(Some)
}

#[derive(Deserialize)]
struct Envelope {
    #[serde(default, deserialize_with = "present")]
    jsonrpc: Option<String>,
    #[serde(default, deserialize_with = "present")]
    id: Option<Box<RawValue>>,
    #[serde(default, deserialize_with = "present")]
    method: Option<String>,
    #[serde(default, deserialize_with = "present")]
    params: Option<Box<RawValue>>,
    #[serde(default, deserialize_with = "present")]
    result: Option<Box<RawValue>>,
    #[serde(default, deserialize_with = "present")]
    error: Option<Box<RawValue>>,
}

#[derive(Deserialize)]
struct IdOnly {
    #[serde(default, deserialize_with = "present")]
    id: Option<Box<RawValue>>,
}

fn recover_id(line: &[u8]) -> Option<Id> {
    let raw = serde_json::from_slice::<IdOnly>(line).ok()?.id?;
    Id::new(raw).ok()
}

pub fn parse_frame(line: &[u8]) -> Incoming {
    let first = line.iter().copied().find(|byte| !byte.is_ascii_whitespace());
    if first != Some(b'{') {
        let error = if serde_json::from_slice::<IgnoredAny>(line).is_err() {
            BridgeError::parse_error("frame is not valid JSON")
        } else if first == Some(b'[') {
            BridgeError::invalid_request("batch requests are not supported")
        } else {
            BridgeError::invalid_request("frame must be a JSON object")
        };
        return Incoming::Invalid { id: None, error };
    }
    let Ok(envelope) = serde_json::from_slice::<Envelope>(line) else {
        let error = if serde_json::from_slice::<IgnoredAny>(line).is_err() {
            BridgeError::parse_error("frame is not valid JSON")
        } else {
            BridgeError::invalid_request("frame has a field of the wrong type")
        };
        return Incoming::Invalid {
            id: recover_id(line),
            error,
        };
    };
    if envelope.jsonrpc.as_deref() != Some("2.0") {
        return Incoming::Invalid {
            id: recover_id(line),
            error: BridgeError::invalid_request("jsonrpc must be the string \"2.0\""),
        };
    }
    match (envelope.method, envelope.id) {
        (Some(method), Some(raw)) => match Id::new(raw) {
            Ok(id) => Incoming::Request {
                id,
                method,
                params: envelope.params,
            },
            Err(error) => Incoming::Invalid { id: None, error },
        },
        (Some(method), None) => Incoming::Notification {
            method,
            params: envelope.params,
        },
        (None, Some(_)) if envelope.result.is_some() != envelope.error.is_some() => {
            Incoming::Response
        }
        (None, _) => Incoming::Invalid {
            id: recover_id(line),
            error: BridgeError::invalid_request("frame has no method"),
        },
    }
}

/// A response whose `result` is already serialized JSON.
pub fn response_bytes(id: &Id, result: &[u8]) -> Frame {
    let mut out = Vec::with_capacity(result.len() + 64);
    out.extend_from_slice(br#"{"jsonrpc":"2.0","id":"#);
    out.extend_from_slice(id.raw().as_bytes());
    out.extend_from_slice(br#","result":"#);
    out.extend_from_slice(result);
    out.push(b'}');
    out
}

pub fn response<T: Serialize>(id: &Id, result: &T) -> Frame {
    let mut out = Vec::with_capacity(160);
    out.extend_from_slice(br#"{"jsonrpc":"2.0","id":"#);
    out.extend_from_slice(id.raw().as_bytes());
    out.extend_from_slice(br#","result":"#);
    match serde_json::to_writer(&mut out, result) {
        Ok(()) => {
            out.push(b'}');
            out
        }
        Err(error) => error_response(
            Some(id),
            &BridgeError::internal(format!("result could not be serialized: {error}")),
        ),
    }
}

pub fn error_response(id: Option<&Id>, error: &BridgeError) -> Frame {
    let mut out = Vec::with_capacity(192);
    out.extend_from_slice(br#"{"jsonrpc":"2.0","id":"#);
    out.extend_from_slice(id.map_or("null", Id::raw).as_bytes());
    out.extend_from_slice(br#","error":"#);
    serde_json::to_writer(&mut out, error).expect("a bridge error always serializes");
    out.push(b'}');
    out
}

pub fn notification<T: Serialize>(method: &str, params: &T) -> Result<Frame, serde_json::Error> {
    let mut out = Vec::with_capacity(160);
    out.extend_from_slice(br#"{"jsonrpc":"2.0","method":"#);
    serde_json::to_writer(&mut out, method)?;
    out.extend_from_slice(br#","params":"#);
    serde_json::to_writer(&mut out, params)?;
    out.push(b'}');
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request_id(line: &str) -> String {
        match parse_frame(line.as_bytes()) {
            Incoming::Request { id, .. } => id.raw().to_owned(),
            other => panic!("not a request: {other:?}"),
        }
    }

    fn invalid(line: &str) -> (Option<String>, BridgeError) {
        match parse_frame(line.as_bytes()) {
            Incoming::Invalid { id, error } => (id.map(|id| id.raw().to_owned()), error),
            other => panic!("not invalid: {other:?}"),
        }
    }

    #[test]
    fn arbitrary_string_and_number_ids_echo_verbatim() {
        for id in [
            r#""abc""#,
            r#""""#,
            r#""é\n\"quoted\"""#,
            r#""🦀""#,
            "0",
            "-17",
            "18446744073709551615",
            "-9223372036854775808",
            "1.50",
            "1e3",
            "123456789012345678901234567890",
        ] {
            let line = format!(r#"{{"jsonrpc":"2.0","id":{id},"method":"m"}}"#);
            assert_eq!(request_id(&line), id);
            let Incoming::Request { id: parsed, .. } = parse_frame(line.as_bytes()) else {
                unreachable!()
            };
            let frame = response(&parsed, &json!({"ok": true}));
            let text = String::from_utf8(frame).unwrap();
            assert!(text.starts_with(&format!(r#"{{"jsonrpc":"2.0","id":{id},"result":"#)));
            serde_json::from_str::<Value>(&text).unwrap();
        }
    }

    #[test]
    fn ids_that_are_not_string_or_number_are_rejected_with_a_null_id() {
        for id in ["null", "true", "{}", "[1]", "false"] {
            let (echoed, error) = invalid(&format!(r#"{{"jsonrpc":"2.0","id":{id},"method":"m"}}"#));
            assert_eq!(echoed, None, "id {id}");
            assert_eq!(error.code, code::INVALID_REQUEST);
        }
    }

    #[test]
    fn id_length_limit_is_exact() {
        let fits = format!("\"{}\"", "a".repeat(MAX_ID_BYTES - 2));
        assert_eq!(fits.len(), MAX_ID_BYTES);
        assert_eq!(request_id(&format!(r#"{{"jsonrpc":"2.0","id":{fits},"method":"m"}}"#)), fits);
        let long = format!("\"{}\"", "a".repeat(MAX_ID_BYTES - 1));
        let (_, error) = invalid(&format!(r#"{{"jsonrpc":"2.0","id":{long},"method":"m"}}"#));
        assert_eq!(error.code, code::INVALID_REQUEST);
    }

    #[test]
    fn notifications_requests_and_client_responses_are_classified() {
        assert!(matches!(
            parse_frame(br#"{"jsonrpc":"2.0","method":"ping"}"#),
            Incoming::Notification { .. }
        ));
        assert!(matches!(
            parse_frame(br#"{"jsonrpc":"2.0","id":1,"result":{}}"#),
            Incoming::Response
        ));
        assert!(matches!(
            parse_frame(br#"{"jsonrpc":"2.0","id":"x","error":{"code":1,"message":"m"}}"#),
            Incoming::Response
        ));
        assert!(matches!(
            parse_frame(br#"{"jsonrpc":"2.0","id":1,"method":"m","params":{"a":1}}"#),
            Incoming::Request { params: Some(_), .. }
        ));
    }

    #[test]
    fn malformed_frames_get_the_right_error_class() {
        for line in ["", "   ", "{", "nonsense", "{\"jsonrpc\":\"2.0\",}", "{\"a\":1} trailing", "\u{feff}{}"] {
            let (id, error) = invalid(line);
            assert_eq!(id, None, "{line:?}");
            assert_eq!(error.code, code::PARSE_ERROR, "{line:?}");
        }
        for line in ["[]", "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\"}]", "1", "\"s\"", "null", "true"] {
            let (_, error) = invalid(line);
            assert_eq!(error.code, code::INVALID_REQUEST, "{line:?}");
        }
        assert!(invalid("[1]").1.message.contains("batch"));
    }

    #[test]
    fn invalid_requests_recover_a_usable_id() {
        let (id, error) = invalid(r#"{"jsonrpc":"1.0","id":7,"method":"m"}"#);
        assert_eq!(id.as_deref(), Some("7"));
        assert_eq!(error.code, code::INVALID_REQUEST);
        let (id, _) = invalid(r#"{"jsonrpc":"2.0","id":"k","method":5}"#);
        assert_eq!(id.as_deref(), Some(r#""k""#));
        let (id, _) = invalid(r#"{"id":"k"}"#);
        assert_eq!(id.as_deref(), Some(r#""k""#));
        let (id, _) = invalid(r#"{"jsonrpc":"2.0","id":9}"#);
        assert_eq!(id.as_deref(), Some("9"));
        let (id, _) = invalid(r#"{"jsonrpc":"2.0","id":{"x":1},"method":"m"}"#);
        assert_eq!(id, None);
    }

    #[test]
    fn serialized_frames_never_contain_a_raw_newline() {
        let nasty = "line\nbreak\r\n\u{2028}\u{2029}\0tab\t\"quote\"\\ 🦀";
        let id = match parse_frame(br#"{"jsonrpc":"2.0","id":"a\nb","method":"m"}"#) {
            Incoming::Request { id, .. } => id,
            other => panic!("{other:?}"),
        };
        let frames = [
            response(&id, &json!({ "text": nasty })),
            error_response(Some(&id), &BridgeError::new(-1, "k", nasty)),
            notification("view.event", &json!({ "text": nasty })).unwrap(),
        ];
        for frame in frames {
            assert!(!frame.contains(&b'\n'));
            assert!(!frame.contains(&b'\r'));
            let value: Value = serde_json::from_slice(&frame).unwrap();
            let text = value.to_string();
            assert!(text.contains("line\\nbreak"));
        }
        let round = response(&id, &json!({ "text": nasty }));
        let value: Value = serde_json::from_slice(&round).unwrap();
        assert_eq!(value["result"]["text"], nasty);
        assert_eq!(value["id"], "a\nb");
    }

    #[test]
    fn session_errors_keep_their_kind_and_text() {
        let cases = [
            (SessionError::Closed, code::CLOSED, "closed"),
            (SessionError::NotReady, code::NOT_READY, "not_ready"),
            (SessionError::NotFound("n".into()), code::NOT_FOUND, "not_found"),
            (SessionError::Busy("owned elsewhere".into()), code::BUSY, "busy"),
            (SessionError::Invalid("i".into()), code::INVALID, "invalid"),
            (SessionError::Cancelled, code::CANCELLED, "cancelled"),
            (SessionError::Failed("f".into()), code::FAILED, "failed"),
        ];
        for (error, expected, kind) in cases {
            let bridge = BridgeError::from(&error);
            assert_eq!(bridge.code, expected);
            assert_eq!(bridge.data["kind"], kind);
            assert_eq!(bridge.message, error.message());
        }
        assert_eq!(
            BridgeError::from(SessionError::Busy("owned elsewhere".into())).message,
            "owned elsewhere"
        );
    }

    #[test]
    fn preserialized_results_embed_unchanged() {
        let id = match parse_frame(br#"{"jsonrpc":"2.0","id":"x","method":"m"}"#) {
            Incoming::Request { id, .. } => id,
            other => panic!("{other:?}"),
        };
        let frame = response_bytes(&id, br#"{"a":[1,2]}"#);
        assert_eq!(frame, br#"{"jsonrpc":"2.0","id":"x","result":{"a":[1,2]}}"#);
    }

    #[test]
    fn error_response_without_an_id_uses_null() {
        let frame = error_response(None, &BridgeError::parse_error("x"));
        let value: Value = serde_json::from_slice(&frame).unwrap();
        assert!(value["id"].is_null());
        assert_eq!(value["error"]["code"], code::PARSE_ERROR);
    }
}
