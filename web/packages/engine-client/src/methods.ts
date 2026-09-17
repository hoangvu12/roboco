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
/** Fetch a tool sidecar blob (`{blobRef}` → `{text}`) - full output/diff text. */
export const FETCH_TOOL_BLOB = "FetchToolBlob";
/** Dev-server discovery for one chat: streams `PreviewSnapshot` (crates/proto/src/preview.rs). */
export const WATCH_PREVIEWS = "WatchPreviews";
/** The one stream that answers a `{stream: true}` readiness ack before items. */
export const WATCH_CHECKOUT_CHANGE_REQUEST = "WatchCheckoutChangeRequest";
export const REVOKE_PAIRING_SESSION = "RevokePairingSession";
export const GET_REMOTE_ACCESS = "GetRemoteAccess";
export const SET_REMOTE_ACCESS = "SetRemoteAccess";
export const CREATE_PAIRING_LINK = "CreatePairingLink";
/** Harness accounts (legacy wire name "Agent*", ADR 0005); every mutation replies with the fresh snapshot. */
export const LIST_AGENT_ACCOUNTS = "ListAgentAccounts";
export const ACTIVATE_AGENT_ACCOUNT = "ActivateAgentAccount";
export const FORGET_AGENT_ACCOUNT = "ForgetAgentAccount";
export const START_AGENT_LOGIN = "StartAgentLogin";
export const COMPLETE_AGENT_LOGIN = "CompleteAgentLogin";
export const POLL_AGENT_LOGIN = "PollAgentLogin";
export const CANCEL_AGENT_LOGIN = "CancelAgentLogin";
/** Workspace entity mutations, tagged `{op: createChat|renameChat|deleteChat|…}` (crates/engine/src/rpc.rs MutateParams). */
export const MUTATE = "Mutate";
/** The add-space palette's folder browse (`{query, path?, targetDeviceId?}`;
 *  `path` omitted means "browse home"). Replies `FolderListing`. */
export const LIST_FOLDERS = "ListFolders";
/** The add-space palette's Locations rail: mounted drives/volumes of the
 *  browsed device (`{targetDeviceId?}`). Replies `DriveListing`. */
export const LIST_DRIVES = "ListDrives";
/** Resolve/optionally create a typed project path ON THE OWNING DEVICE
 *  (`{path, createIfMissing, targetDeviceId}` — targetDeviceId required).
 *  Replies `PrepareSpacePathReply` (path, exists, gitDetected). */
export const PREPARE_SPACE_PATH = "PrepareSpacePath";
/** Harness catalog for the pickers (one row per harness). */
export const LIST_HARNESSES = "ListHarnesses";
/** Model catalog for the picked harness (filter input drives refetch on focus). */
export const LIST_MODELS = "ListModels";
/** Composer surface: QueueCommand takes `{chatId, command, transfers}`; command is one of the SessionCommandPayload variants. */
export const QUEUE_COMMAND = "QueueCommand";
/** Message-queue surface (crates/engine/rpc.rs §3.5). The queue lives on the chat doc;
 *  `WatchQueue` streams `{items}` snapshots, the rest are mutations that require
 *  an explicit ack so a racing device's row never silently moves. Edit leases
 *  gate host-authoritative delivery while a client is editing. */
export const QUEUE_MESSAGE = "QueueMessage";
export const UPDATE_QUEUED_MESSAGE = "UpdateQueuedMessage";
export const MOVE_QUEUED_MESSAGE = "MoveQueuedMessage";
export const REMOVE_QUEUED_MESSAGE = "RemoveQueuedMessage";
export const SEND_QUEUED_MESSAGE_NOW = "SendQueuedMessageNow";
export const STEER_QUEUED_MESSAGE_NOW = "SteerQueuedMessageNow";
export const BEGIN_QUEUED_MESSAGE_EDIT = "BeginQueuedMessageEdit";
export const RENEW_QUEUED_MESSAGE_EDIT = "RenewQueuedMessageEdit";
export const FINISH_QUEUED_MESSAGE_EDIT = "FinishQueuedMessageEdit";
/** Workspace file surface (crates/engine/src/workspace_files.rs): the space's
 *  directory tree, text/image reads, writes, and the change stream. */
export const LIST_WORKSPACE_DIRECTORY = "ListWorkspaceDirectory";
export const SEARCH_WORKSPACE_FILES = "SearchWorkspaceFiles";
export const READ_WORKSPACE_FILE = "ReadWorkspaceFile";
export const READ_WORKSPACE_IMAGE = "ReadWorkspaceImage";
export const WRITE_WORKSPACE_FILE = "WriteWorkspaceFile";
/** The one workspace stream; items are `WorkspaceFileChanges` frames. */
export const WATCH_WORKSPACE_FILES = "WatchWorkspaceFiles";

/** Uploads / attachments (crates/rpc/src/lib.rs methods module). Chunked
 *  binary → durable host path. `UploadChunk` and `UploadCommit` may take
 *  `targetDeviceId` to forward to the chat's host device (the web composer
 *  always does — uploads do not write to the browser side). */
export const UPLOAD_CHUNK = "UploadChunk";
export const UPLOAD_COMMIT = "UploadCommit";
/** Transcript image read-back: 64KB base64 chunks until `done`. */
export const READ_ATTACHMENT_CHUNK = "ReadAttachmentChunk";

// Terminals (crates/engine/src/rpc.rs §3.4): OpenTerminal → TerminalSession,
// SubscribeTerminal streams TerminalEvent (replay then live tail, no
// readiness ack — the first item IS the ack), Write/Resize take the
// terminal id, Close kills the PTY.
export const OPEN_TERMINAL = "OpenTerminal";
export const SUBSCRIBE_TERMINAL = "SubscribeTerminal";
export const WRITE_TERMINAL = "WriteTerminal";
export const RESIZE_TERMINAL = "ResizeTerminal";
export const CLOSE_TERMINAL = "CloseTerminal";

/** Per-checkout working-tree diffs (DataRpc, relay-forwardable). */
export const WATCH_CHECKOUT_DIFFS = "WatchCheckoutDiffs";
/** One-shot scoped capture (`mode` = workingTree | branch | turn). */
export const GET_CHECKOUT_DIFF = "GetCheckoutDiff";
/** Full text of one side of a file in a diff (used for non-truncated text view). */
export const GET_CHECKOUT_FILE_DIFF_TEXT = "GetCheckoutFileDiffText";
/** Branches for a checkout (one-shot). Default branch first. */
export const LIST_BRANCHES = "ListBranches";
/** Refs for a repo folder (one-shot): branches plus their current/worktree state (`crates/rpc/src/lib.rs:117`, `pickers.rs:1255`). */
export const LIST_REFS = "ListRefs";
/** Check a repo folder out onto another ref (`crates/rpc/src/lib.rs:125`, `pickers.rs:1340`). */
export const SWITCH_REF = "SwitchRef";
