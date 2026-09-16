/**
 * RPC method names, mirroring `roboco_rpc::methods` (crates/rpc/src/lib.rs).
 * Wire names are frozen (ADR 0005); wiregen does not emit the method table
 * yet, so this module carries the subset the connection core needs and the
 * tests drive. Extend it as later tickets call more methods.
 */
export const ENGINE_INFO = "EngineInfo";
export const ENGINE_READY = "EngineReady";
export const LOCAL_DEVICE = "LocalDevice";
export const WATCH_CHATS = "WatchChats";
export const WATCH_SPACES = "WatchSpaces";
export const WATCH_DEVICES = "WatchDevices";
export const WATCH_SESSIONS = "WatchSessions";
export const WATCH_QUEUE = "WatchQueue";
/** The chat doc's transcript stream: full `reset` first, then delta frames. */
export const WATCH_DOC_MESSAGES = "WatchDocMessages";
/** Fetch a tool sidecar blob (`{blobRef}` → `{text}`) — full output/diff text. */
export const FETCH_TOOL_BLOB = "FetchToolBlob";
/** The one stream that answers a `{stream: true}` readiness ack before items. */
export const WATCH_CHECKOUT_CHANGE_REQUEST = "WatchCheckoutChangeRequest";
export const REVOKE_PAIRING_SESSION = "RevokePairingSession";
export const GET_REMOTE_ACCESS = "GetRemoteAccess";
/** Workspace entity mutations, tagged `{op: createChat|renameChat|deleteChat|…}` (crates/engine/src/rpc.rs MutateParams). */
export const MUTATE = "Mutate";
