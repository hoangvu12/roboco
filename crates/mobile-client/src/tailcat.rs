//! The Tailcat transport (android-next#02): `libroboco_tailcat` — the Go
//! adapter built `-buildmode=c-shared` (`adapters/roboco-tailcat/capi`) —
//! loaded on demand with `dlopen`, so a LAN-only user never starts a Go
//! runtime.
//!
//! The library pulls a Tailcat engine's fixed application port to a
//! loopback listener (`http://127.0.0.1:<port>`); the WebSocket code then
//! dials that URL unchanged. Tailcat carries bytes only: pairing,
//! credentials and revocation stay Roboco's (ADR 0008).
//!
//! Hygiene: the `tc…` address embeds the server's pre-shared key and the
//! client state holds this phone's private key. Neither is ever logged or
//! put in an error: logs use [`describe`], and the library's own errors are
//! redacted before they cross the C ABI.

use std::ffi::{CStr, CString, c_char};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use sha2::{Digest, Sha256};

pub use roboco_proto::tailcat::{INVITE_PREFIX, TailcatInvite, describe};

/// The C ABI version this core speaks (`roboco_tailcat_abi_version`).
pub const ABI_VERSION: i32 = 1;

/// The scheme of a Tailcat engine's display handle (its `base_url`).
pub const DISPLAY_SCHEME: &str = "tailcat:";

/// The library's file name on this platform: `libroboco_tailcat.so` on
/// Android and Linux, `roboco_tailcat.dll` on Windows (host tests).
pub fn library_file_name() -> String {
    format!(
        "{}roboco_tailcat{}",
        std::env::consts::DLL_PREFIX,
        std::env::consts::DLL_SUFFIX
    )
}

/// The non-secret stand-in a Tailcat engine's record carries as its
/// `base_url`: `tailcat:` plus the first 8 hex digits of SHA-256(address).
/// Deterministic (the same engine always shows the same handle) and
/// one-way (it never reveals the address).
pub fn display_handle(address: &str) -> String {
    let digest = Sha256::digest(address.as_bytes());
    let mut handle = String::from(DISPLAY_SCHEME);
    for byte in &digest[..4] {
        handle.push_str(&format!("{byte:02x}"));
    }
    handle
}

/// Why a Tailcat route could not be opened. Messages are fixed or come
/// from the library's redacted errors; they never carry the address.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum TailcatError {
    /// The library is missing from this build, failed to load, or speaks
    /// another ABI version.
    #[error("Tailcat isn't available in this build ({0})")]
    Unavailable(String),
    /// The tunnel did not come up: no route, the engine is offline, or
    /// DERP is blocked.
    #[error("could not reach the engine over Tailcat ({0})")]
    Unreachable(String),
}

/// One live tunnel: a loopback listener connected to the engine. Dropping
/// it closes the listener and every connection through it (on a separate
/// thread: closing joins the Go proxy's workers).
pub struct Tunnel {
    url: String,
    closer: Mutex<Option<Box<dyn FnOnce() + Send>>>,
}

impl Tunnel {
    /// `url` is `http://127.0.0.1:<port>`; `closer` runs once, on drop.
    pub fn new(url: String, closer: Box<dyn FnOnce() + Send>) -> Self {
        Self {
            url,
            closer: Mutex::new(Some(closer)),
        }
    }

    /// The loopback HTTP base (`POST {url}/pairing/redeem`).
    pub fn url(&self) -> &str {
        &self.url
    }

    /// The loopback WebSocket endpoint every dial uses.
    pub fn endpoint(&self) -> String {
        crate::pairing::websocket_endpoint(&self.url)
    }
}

impl Drop for Tunnel {
    fn drop(&mut self) {
        let closer = self
            .closer
            .get_mut()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .take();
        if let Some(closer) = closer {
            let _ = std::thread::Builder::new()
                .name("tailcat-close".into())
                .spawn(closer);
        }
    }
}

impl std::fmt::Debug for Tunnel {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // The loopback URL is not a secret.
        f.debug_struct("Tunnel").field("url", &self.url).finish()
    }
}

/// A freshly opened tunnel plus the client identity to store (unchanged
/// when one was passed in; generated when the input was empty).
pub struct OpenedTunnel {
    pub tunnel: Tunnel,
    pub client_state: String,
}

/// Opens tunnels. Blocking (the library pings the engine, up to ~20 s);
/// callers run it on a blocking thread. The seam lets the route state
/// machine be tested without Go.
pub trait TunnelOpener: Send + Sync {
    fn open(&self, address: &str, client_state: &str) -> Result<OpenedTunnel, TailcatError>;
}

/// The production opener: `libroboco_tailcat` from the app's native
/// library directory, loaded on the first open.
pub struct LibraryOpener {
    native_lib_dir: Option<PathBuf>,
    derp_map: Option<String>,
}

impl LibraryOpener {
    pub fn new(native_lib_dir: Option<PathBuf>, derp_map: Option<String>) -> Self {
        Self {
            native_lib_dir,
            derp_map,
        }
    }
}

impl TunnelOpener for LibraryOpener {
    fn open(&self, address: &str, client_state: &str) -> Result<OpenedTunnel, TailcatError> {
        let Some(dir) = self.native_lib_dir.as_deref() else {
            return Err(TailcatError::Unavailable(
                "no native library directory".into(),
            ));
        };
        let library = TailcatLib::load(dir)?;
        tracing::info!(address = %describe(address), "opening a Tailcat tunnel");
        library.start_client(address, client_state, self.derp_map.as_deref())
    }
}

type StartClient = unsafe extern "C" fn(
    *const c_char,
    *const c_char,
    *const c_char,
    *mut *mut c_char,
    *mut *mut c_char,
    *mut *mut c_char,
) -> i64;
type CloseClient = unsafe extern "C" fn(i64);
type FreeString = unsafe extern "C" fn(*mut c_char);
type AbiVersion = unsafe extern "C" fn() -> i32;

/// The loaded library. Never unloaded: a Go runtime cannot be torn down,
/// so every successful load stays in [`LOADED`] for the process lifetime.
struct TailcatLib {
    _library: libloading::Library,
    start: StartClient,
    close: CloseClient,
    free: FreeString,
}

/// Successful loads by path. Failures are not cached: retrying a missing
/// library is cheap, and host tests load from more than one directory.
static LOADED: Mutex<Vec<(PathBuf, Arc<TailcatLib>)>> = Mutex::new(Vec::new());

impl TailcatLib {
    fn load(dir: &Path) -> Result<Arc<Self>, TailcatError> {
        let path = dir.join(library_file_name());
        let mut loaded = crate::lock(&LOADED);
        if let Some((_, library)) = loaded.iter().find(|(loaded, _)| *loaded == path) {
            return Ok(library.clone());
        }
        if !path.is_file() {
            return Err(TailcatError::Unavailable(format!(
                "{} is missing",
                library_file_name()
            )));
        }
        // SAFETY: loading runs the library's initialisers (the Go runtime
        // and the capi package's init); the library is ours, built from
        // adapters/roboco-tailcat/capi.
        let library = unsafe { libloading::Library::new(&path) }.map_err(|error| {
            tracing::warn!(%error, "libroboco_tailcat failed to load");
            TailcatError::Unavailable(format!("{} failed to load", library_file_name()))
        })?;
        // SAFETY: the symbol types mirror capi/exports.go; the version
        // check below refuses a library speaking another ABI before any
        // other symbol is called.
        let library = unsafe {
            let version: AbiVersion = *library
                .get::<AbiVersion>(b"roboco_tailcat_abi_version\0")
                .map_err(|_| missing_symbol("roboco_tailcat_abi_version"))?;
            let version = version();
            if version != ABI_VERSION {
                return Err(TailcatError::Unavailable(format!(
                    "{} speaks ABI {version}, this app needs {ABI_VERSION}",
                    library_file_name()
                )));
            }
            let start: StartClient = *library
                .get::<StartClient>(b"roboco_tailcat_start_client\0")
                .map_err(|_| missing_symbol("roboco_tailcat_start_client"))?;
            let close: CloseClient = *library
                .get::<CloseClient>(b"roboco_tailcat_close\0")
                .map_err(|_| missing_symbol("roboco_tailcat_close"))?;
            let free: FreeString = *library
                .get::<FreeString>(b"roboco_tailcat_free\0")
                .map_err(|_| missing_symbol("roboco_tailcat_free"))?;
            Arc::new(Self {
                _library: library,
                start,
                close,
                free,
            })
        };
        loaded.push((path, library.clone()));
        Ok(library)
    }

    fn start_client(
        self: &Arc<Self>,
        address: &str,
        client_state: &str,
        derp_map: Option<&str>,
    ) -> Result<OpenedTunnel, TailcatError> {
        let invalid = || TailcatError::Unreachable("invalid Tailcat address".into());
        let address = CString::new(address).map_err(|_| invalid())?;
        let state = CString::new(client_state).map_err(|_| invalid())?;
        let derp_map = derp_map
            .map(CString::new)
            .transpose()
            .map_err(|_| TailcatError::Unreachable("invalid DERP map URL".into()))?;
        let mut url: *mut c_char = std::ptr::null_mut();
        let mut new_state: *mut c_char = std::ptr::null_mut();
        let mut error: *mut c_char = std::ptr::null_mut();
        // SAFETY: every input is a NUL-terminated string that outlives the
        // call; the out pointers are valid and the library sets each to
        // NULL or a malloc'd string, which take_string frees exactly once.
        let handle = unsafe {
            (self.start)(
                address.as_ptr(),
                state.as_ptr(),
                derp_map.as_ref().map_or(std::ptr::null(), |map| map.as_ptr()),
                &mut url,
                &mut new_state,
                &mut error,
            )
        };
        let url = self.take_string(url);
        let new_state = self.take_string(new_state);
        let error = self.take_string(error);
        if handle <= 0 {
            return Err(TailcatError::Unreachable(
                error.unwrap_or_else(|| "Tailcat client startup failed".into()),
            ));
        }
        let library = self.clone();
        let tunnel = Tunnel::new(
            url.unwrap_or_default(),
            Box::new(move || {
                // SAFETY: `handle` came from start_client; close is
                // idempotent on the Go side.
                unsafe { (library.close)(handle) }
            }),
        );
        Ok(OpenedTunnel {
            tunnel,
            client_state: new_state.unwrap_or_default(),
        })
    }

    fn take_string(&self, value: *mut c_char) -> Option<String> {
        if value.is_null() {
            return None;
        }
        // SAFETY: a non-NULL out string is NUL-terminated and owned by us
        // until freed here.
        let text = unsafe { CStr::from_ptr(value) }
            .to_string_lossy()
            .into_owned();
        unsafe { (self.free)(value) };
        Some(text)
    }
}

fn missing_symbol(name: &str) -> TailcatError {
    TailcatError::Unavailable(format!("{} lacks {name}", library_file_name()))
}

#[cfg(test)]
mod tests {
    use super::*;

    const ADDRESS: &str = "tcSECRETaddressSECRETaddress0123456789";

    #[test]
    fn the_display_handle_is_deterministic_and_never_contains_the_address() {
        let handle = display_handle(ADDRESS);
        assert_eq!(handle, display_handle(ADDRESS));
        assert!(handle.starts_with(DISPLAY_SCHEME));
        assert_eq!(handle.len(), DISPLAY_SCHEME.len() + 8);
        assert!(handle[DISPLAY_SCHEME.len()..]
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit()));
        assert!(!handle.contains("SECRET"));
        assert_ne!(handle, display_handle("tcAnotherAddress"));
    }

    #[test]
    fn the_library_name_follows_the_platform() {
        let name = library_file_name();
        assert!(name.contains("roboco_tailcat"));
        if cfg!(windows) {
            assert_eq!(name, "roboco_tailcat.dll");
        } else if cfg!(target_os = "macos") {
            assert_eq!(name, "libroboco_tailcat.dylib");
        } else {
            assert_eq!(name, "libroboco_tailcat.so");
        }
    }

    #[test]
    fn without_the_library_the_opener_reports_unavailable() {
        let opener = LibraryOpener::new(None, None);
        assert!(matches!(
            opener.open(ADDRESS, ""),
            Err(TailcatError::Unavailable(_))
        ));
        let empty = tempfile::tempdir().unwrap();
        let opener = LibraryOpener::new(Some(empty.path().to_owned()), None);
        match opener.open(ADDRESS, "") {
            Err(TailcatError::Unavailable(message)) => assert!(!message.contains("SECRET")),
            other => panic!("expected Unavailable, got {:?}", other.err()),
        }
    }

    #[test]
    fn a_tunnel_runs_its_closer_once_on_drop() {
        let (sender, receiver) = std::sync::mpsc::channel();
        let tunnel = Tunnel::new(
            "http://127.0.0.1:4242".into(),
            Box::new(move || {
                let _ = sender.send(());
            }),
        );
        assert_eq!(tunnel.endpoint(), "ws://127.0.0.1:4242/");
        drop(tunnel);
        receiver
            .recv_timeout(std::time::Duration::from_secs(5))
            .expect("closer ran");
        assert!(receiver.recv_timeout(std::time::Duration::from_millis(100)).is_err());
    }
}
