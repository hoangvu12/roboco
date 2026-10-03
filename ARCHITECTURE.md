# Roboco architecture

Roboco is a Rust engine and GPUI desktop client for running coding agents. The same binary runs headed (`roboco`) or headless (`roboco headless`). Windows and Linux are the supported release platforms.

## Engine and client

The engine owns agent processes, session history, command queues, terminals, repositories, worktrees, files, and provider credentials. The UI renders and controls engines through the typed RPC protocol in `crates/rpc`.

A headed process attaches to an existing local engine when available. Otherwise it embeds an engine and serves its RPC surface on loopback so other local windows can attach. The in-memory and WebSocket transports use the same request and stream envelopes. An OS instance lock protects each engine data directory.

The loopback listener rejects browser Origin headers. It remains credential-free for native local clients. A request naming a different target device fails; the client must send it through the owning engine's connection.

## Local storage

Production startup always opens the stable local profile under `{data_dir}/profiles/local/`. `local-profile.json` records the local identity; `device-id` identifies the engine. Neither is a Roboco account. Existing local sessions and attachments retain their storage paths.

Session documents use Loro for structured local persistence. They contain transcripts and durable command queues. The local registry contains the engine's device, spaces, chats, and session metadata. SQLite stores document snapshots, sidecars, and the processed-command ledger. Run journals preserve agent events for recovery. There are no cloud room clients or cross-engine document replication.

The command executor deduplicates durable requests and writes their outcomes on the owning engine. Shutdown pauses queues before stopping agent processes and terminals, then flushes local documents. This prevents shutdown from starting queued work.

Provider authentication is separate from engine pairing. Claude, Codex, and other agent account settings manage their own provider credentials; removing the Roboco account service does not remove provider login.

## Direct remote access

The [remote access specification](.scratch/remote-access/spec.md) defines the pairing and multi-engine implementation. [ADR 0004](docs/adr/0004-engine-local-data.md) establishes engine-local ownership; consult `docs/adr/` for the current decision filenames.

Remote access is opt-in. Pairing exchanges a short-lived, single-use code for a non-expiring, revocable client session. The engine stores hashed verifiers. Pairing URLs carry their code in a fragment. Native clients send credentials through authorization headers rather than request URLs.

The additional remote bind serves HTTP pairing and WebSocket RPC with mandatory credentials. The engine serves plain HTTP/WS; operators supply internet reachability and TLS through their own tunnels. Remote clients use the same RPC capabilities as local clients.

The client registry owns a supervised connection to every paired engine. Sidebar rows retain their device grouping, and operations route to their owning engine. Cached session lists and open transcripts support offline reading. Disconnected engines refuse writes and reconnect with capped backoff. No engine synchronizes its sessions, settings, queue, or search index to another engine.

## Desktop state

A space is a folder on an engine. The existing space palette, device tabs, and composer chip select the target for a new chat. The sidebar presents sessions grouped by device. Tabs and other viewport preferences remain client-local in `ui-settings.json`.

Transcript subscriptions deliver an initial reset followed by deltas. Virtualized transcript and file views avoid rebuilding unrelated rows for streaming text updates. Terminal, file, diff, and preview surfaces use engine capabilities rather than accessing a remote engine's files on the client machine.

## Workspace map

| Path | Responsibility |
| --- | --- |
| `apps/roboco` | Binary, CLI, daemon integration, application startup |
| `crates/engine` | Engine lifecycle, agent execution, local storage services, RPC handlers |
| `crates/rpc` | Request/stream envelopes and transports |
| `crates/proto` | Shared domain and protocol types |
| `crates/doc` | Local Loro schemas, transcripts, registry, command data |
| `crates/sync` | Local document store and snapshot compatibility; historical crate name |
| `crates/harness` | Provider and agent adapters |
| `crates/voice` | Desktop-local dictation: optional Parakeet model download and verify, capture, inference; no RPC/sync |
| `crates/ui` | GPUI views, client state, settings |
| `crates/preview` | Local application preview services |
| `crates/update` | Release metadata and updates |
| `crates/theme`, `crates/syntax` | Presentation and syntax support |

## Verification and upstream work

Listener integration tests run engines with deterministic mock agent behavior and exercise the actual HTTP/WebSocket surface. Client routing and reconnection tests use real engines. UI regressions run the headless GPUI suite; Windows rendering and lifecycle scripts provide native application checks.

Specs and tickets live under `.scratch/`. Historical research stays under `docs/research/`; it may describe the removed cloud implementation. Upstream ports follow [the cherry-pick workflow](docs/reference/upstream-ports.md). Upstream main is never merged into Roboco main.
