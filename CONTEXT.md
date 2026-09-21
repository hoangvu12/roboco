# Domain context

## Remote access

**Engine**:
The per-device process that owns chats, chat docs, and harnesses, and serves them to paired clients over a WebSocket. One engine per machine.
_Avoid_: Environment, server, backend, daemon (when it means the engine generally)

**Pairing**:
The one-time act of registering a client with an engine by redeeming a short-lived pair code or pairing URL. After pairing, the client holds a session and never pairs again.
_Avoid_: Login, sign-in, connecting (when it means pairing)

**Session**:
The credential a paired client holds for an engine. Does not expire; lives until revoked. The only "session" in Roboco's language — a chat's state is its Chat status, its document is its Chat doc, a shell is a Terminal.
_Avoid_: Token (when a human-facing word is wanted), login

**Remote access**:
An engine serving paired clients over the network. Remoteness is about where the client sits, not where data is replicated — data stays engine-local either way.
_Avoid_: Cloud, sync

**Web client**:
The engine-served Roboco client that runs in a browser at the engine's own remote access URL. Pairs like any other client and holds its session credential in browser storage, scoped to the engine's origin.
_Avoid_: Web app (when it means this), hosted app

## Chats

**Chat**:
The durable conversation and work history with a harness. Owns its transcript, configuration, and message queue; survives harness restarts.
_Avoid_: Conversation, thread, session (when it means the chat)

**Chat status**:
A chat's live run state: idle, working, awaiting input, or errored.
_Avoid_: Session, state (when it means chat status)

**Chat doc**:
The chat's durable document — transcript, command ledger, and message queue in one doc, owned by the engine.
_Avoid_: Session doc

**Transcript**:
The ordered message history inside a chat doc.
_Avoid_: History (that is git history), log

**Turn**:
One user-to-harness work cycle in a chat: from a user message through the harness's work until it comes to rest. Keyed by the user message that started it.
_Avoid_: Round, exchange

**Run**:
One execution of a harness against a chat, from send to done. Resumable after crashes via its run journal. The harness conversation id belongs to the run.
_Avoid_: Execution, session

**Steer**:
Inject input into a harness mid-run at a step or turn boundary.
_Avoid_: Interrupt (that is the stronger act), nudge

**Interrupt**:
Stop a harness mid-run without waiting for a boundary.
_Avoid_: Cancel, abort

**Command**:
A durable intent recorded in a chat doc — run, steer, interrupt, or respond-input — executed at most once by the engine.
_Avoid_: Message (a command is not a message), request

**Queued message**:
A user message waiting in the chat's queue, optionally held until the turn ends, delivered in order.
_Avoid_: Draft, pending message

## Harnesses

**Harness**:
The adapter around an agent CLI (claude code, codex, cursor, …) that Roboco drives to run a chat. Roboco is a harness wrapper, not a harness itself. One word everywhere, including UI labels.
_Avoid_: Provider, driver, agent (when it means the adapter)

**Harness account**:
A per-device login for an agent CLI's credential store. Lives on the engine's machine; separate from pairing.
_Avoid_: Agent account, login (when it means pairing)

**Model**:
A harness-offered model selectable per chat, carrying its reasoning levels and options.
_Avoid_: Provider, engine (in the LLM sense)

**Subagent**:
A child agent a harness spawns mid-run, surfaced inline in the parent chat's transcript.
_Avoid_: Task agent, delegate

## Engine data

**Registry**:
The engine's authoritative row store of devices, spaces, chats, and chat statuses.
_Avoid_: Workspace (when it means the registry), registry room

**Space**:
A folder opened on a device — the unit chats are grouped by in the sidebar, and the folder a chat's checkout lives in.
_Avoid_: Project, workspace

**Device**:
A machine running an engine, as represented to paired clients. The user-facing word for machines you can drive — "pair a device", Devices settings.
_Avoid_: Environment, machine, paired engine

## Source control

**Checkout**:
The working copy a chat runs against — either the space's folder or a worktree.
_Avoid_: Repo (when it means the working copy), workspace

**Worktree**:
A linked git worktree managed by Roboco, used as a chat's checkout to isolate changes.
_Avoid_: Branch (when it means worktree)

**Change request**:
The provider-neutral PR/MR concept paired with a checkout, created and updated from a chat's turn diffs.
_Avoid_: Pull request, merge request (provider-specific words)

## Terminal & preview

**Terminal**:
An interactive shell running on the engine, streamed to a client.
_Avoid_: Session, console, PTY

**Preview**:
A live local dev server discovered on the engine and proxied to clients under a stable name. Not screenshots (appshots) and not file previews.
_Avoid_: Appshot, file preview

## Theme vocabulary

- **Theme family** — A named collection of related theme variants that share an origin, such as Night Owl and Night Owl Light.
- **Theme variant** — One complete, resolved palette for a single appearance (`light` or `dark`). Runtime UI consumes variants, not source-format tokens.
- **Theme source** — The durable origin of a custom family: an imported snapshot, linked file, linked package, or editable native file.
- **Imported snapshot** — A self-contained copy of a compiled theme family. It no longer follows changes to its original source.
- **Linked theme** — A custom family that follows a source on disk and can be reloaded without re-importing it.
- **Linked file** — A link to one VS Code-compatible theme definition.
- **Linked package** — A link to a VS Code extension folder or `package.json` that declares one or more related theme variants.
- **Editable theme** — A duplicate stored as a native resolved-family JSON file. Users edit the file directly and explicitly reload it; invalid edits preserve the last known good family.
- **Last known good** — The most recent successfully compiled family retained by a linked theme when its current source is missing or invalid.
- **Import report** — A per-variant summary of mapped roles, fallbacks, unsupported values, and inferred decisions produced during compilation.
- **Mapping review** — The optional advanced view of an import report. Normal theme selection and import do not expose token names.
- **Theme hardening** — Deterministic post-mapping repairs that prefer stronger semantically related source colors, minimally adjust only shared Roboco roles when needed, and record every decision in the mapping review.
- **Theme default accent** — The interaction accent chosen or inferred for a theme variant by its authoring source.
- **Accent override** — A Roboco preset that replaces interaction roles only; syntax, terminal ANSI, diff, warning, error, and success colors remain owned by the theme.
- **Recommended surface treatment** — A theme variant's authored recommendation for whether its surfaces are frosted or opaque. It is used only when the user keeps the surface preference at theme default.
- **Surface preference** — A device-local choice of theme default, frosted, or opaque that is independent of appearance, theme, and accent selections.
- **Resolved surface treatment** — The effective frosted or opaque treatment produced by applying the surface preference to the active variant's recommendation.
