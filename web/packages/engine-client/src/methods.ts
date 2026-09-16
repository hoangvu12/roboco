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
