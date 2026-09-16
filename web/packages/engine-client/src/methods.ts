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
/** The one stream that answers a `{stream: true}` readiness ack before items. */
export const WATCH_CHECKOUT_CHANGE_REQUEST = "WatchCheckoutChangeRequest";
export const REVOKE_PAIRING_SESSION = "RevokePairingSession";
export const GET_REMOTE_ACCESS = "GetRemoteAccess";
/** Workspace entity mutations, tagged `{op: createChat|renameChat|deleteChat|…}` (crates/engine/src/rpc.rs MutateParams). */
export const MUTATE = "Mutate";
/** Workspace file surface (crates/engine/src/workspace_files.rs): the space's
 *  directory tree, text/image reads, writes, and the change stream. */
export const LIST_WORKSPACE_DIRECTORY = "ListWorkspaceDirectory";
export const SEARCH_WORKSPACE_FILES = "SearchWorkspaceFiles";
export const READ_WORKSPACE_FILE = "ReadWorkspaceFile";
export const READ_WORKSPACE_IMAGE = "ReadWorkspaceImage";
export const WRITE_WORKSPACE_FILE = "WriteWorkspaceFile";
/** The one workspace stream; items are `WorkspaceFileChanges` frames. */
export const WATCH_WORKSPACE_FILES = "WatchWorkspaceFiles";
