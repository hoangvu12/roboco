# Mimir

Roboco drives Mimir natively through `sh.roboco.bridge`, a Mimir frontend
plugin that lives in `integrations/mimir/`. No ACP is involved. Mimir owns
execution and each saved conversation. The engine owns the chat: its
document, queue, command ledger and the projection clients render.

## Install the bridge

Nothing installs automatically. Build the package once per Mimir SDK
version, then install it with Mimir's own command:

```
python3 integrations/mimir/build.py --sdk-archive <mimir-plugin-sdk-0.13.0.crate> --mimir <mimir>
mimir plugin check integrations/mimir/out/sh.roboco.bridge-0.1.0
mimir plugin install integrations/mimir/out/sh.roboco.bridge-0.1.0
mimir plugin list
```

`mimir plugin list` must show `sh.roboco.bridge (Roboco bridge) 0.1.0
[compatible]`. `integrations/mimir/out/INSTALL.txt` repeats these commands
with the archive digest. To try the bridge without touching real settings,
point `MIMIR_CODING_AGENT_DIR` at an empty directory for both the install and
the engine.

Roboco finds `mimir` on `PATH`, in `~/.cargo/bin` or `~/.local/bin`, or at
`MIMIR_EXECUTABLE`. The engine inherits `MIMIR_CODING_AGENT_DIR` and
credentials unchanged and never points Mimir at a private directory.

## Setup states

`GetNativeReadiness` reports, with the corrective action:

| State | Meaning |
| --- | --- |
| `ready` | The bridge started, its host contracts match this engine, and at least one model is selectable. |
| `missingExecutable` | No `mimir` was found. |
| `pluginMissing` | `mimir plugin run sh.roboco.bridge` refused: install or enable the package. |
| `incompatible` | The installed bridge speaks other host contract versions; install the build that matches this Roboco. |
| `noModels` | Mimir has no configured, signed-in provider. |
| `failed` | Anything else, with the bridge's own message. |

Readiness is cached by executable identity and agent directory; `force`
re-probes.

## Runtime

One bridge process per agent directory serves every Mimir chat on the
engine. Each chat holds its own attachment to its own native conversation.
Prompts are admitted with the chat message id as the submission key, so a
lost reply is reconciled from the host's request list and never resent.

Before anything reaches the host, the chat's conversation mapping and the
pending message are saved to disk (a failed save sends nothing), and what was
sent (text, images, skill selections) is kept engine-local until the host
answers. Each message's delivery is one of `submitting`, `admitted`,
`steered`, `handled`, `unknown` or `refused`, with its `kind` (`prompt`,
`command`, `steer`):

- Slash commands go to host command routing whether or not a turn is running;
  they are never steered into the model. A command typed mid-turn keeps what
  was sent too, so one the host refuses can be retried with its original text
  once the turn ends.
- A lost reply leaves the message `unknown` and its command ledger entry
  `unknown`. Nothing is resent on its own. `RetrySubmission` is accepted only
  where `retryable` says so: a keyed prompt (the host returns the original
  admission if it has one) or a message the host refused, and only while the
  engine still holds what was sent. Commands and steering carry no key and are
  never resent after a lost reply.
- A message still `submitting` when the engine stopped becomes `unknown` on
  the next start; reattaching settles it from the host's request list.
- A failed release leaves the chat detached (its view is gone); `reconnect`
  opens a fresh view.

- A client disconnect or closed tab changes nothing: views are engine-owned.
- Interrupt cancels exactly the chat's active root request. Children, goals
  and other chats keep running.
- A crashed bridge is shown as an interruption. The engine restarts it only
  after the old process tree is reaped, at most three times in ten minutes,
  and reopens each chat to reconcile its saved state. Nothing is resubmitted.
- The bridge holds the harness execution lease until its tree is reaped. An
  attachment idle for 30 minutes is released; a bridge with no attachments
  stops after 5 minutes.

## Continue in Mimir

The `release` control (Continue in Mimir) is refused while Mimir is still
working. Otherwise it releases the attachment and waits for the host's
cleanup and lease release. Open `mimir` in the same folder and pick the
conversation with `/sessions` (or `/resume`). While the TUI owns it, Roboco
shows Busy and creates nothing. `reconnect` reopens the same conversation and
projects every turn written in the TUI.

## Wire contract for clients

Clients render and control through existing pairing and RPC:

- `TranscriptUpdate.native` carries `NativeChatState`: link, configuration,
  active request, recent requests, plan and goal, the pending question or
  approval, children with their right-pane `docId`, and per-message delivery.
- Tool parts carry `ToolCall::Native { view }`: host title, running title,
  kind, summary, locations, quiet, group, the latest progress line, and
  `detailRef` / `detailBytes` for the complete public `NativeToolDetail`.
  `outputRef` holds the full public output text: a nonempty public display
  list is authoritative (text and resource text; image, audio and link blocks
  stay in the detail's `displayContent`), otherwise the host's public output.
  Model-only content is never shown in its place.
- The initial chat submission selects its model and reasoning. After that,
  Mimir owns configuration. Queued prompts and reopened chats cannot apply
  stale `ChatConfig` defaults. Both clients change settings through `configure`.
- A detail's `stream` and `progress` are `NativeBlobSeries`: every streamed
  byte and every progress line (JSON lines), as numbered blobs
  `{blobRef}.{index:06}`.
- `FetchToolBlob { blobRef, offset?, maxBytes? }` returns `{text}`, or a
  `ToolBlobWindow` when a window is requested. Windows of non-UTF-8 blobs are
  base64 (`encoding`).
- `notice` parts carry `NativeNotice` (plan lifecycle, compaction, branch
  summary, plugin snapshots, delivered child results with usage, command
  output). Live output cut off by a lost bridge or engine becomes an
  `interrupted` notice whose `detailRef` holds the provisional parts; whatever
  the host saved arrives as ordinary entries, so nothing shows twice.
- A branch rewind removes entries the branch no longer has, results included;
  a call whose result the branch lacks shows no output, stream or progress.
  Returning to that branch restores the user's own message entries and, from
  each saved result, the detail it finished its call with, including the
  stream and progress only the engine recorded.
- Children keep `attempts`: each attempt's status, presentation and, once it
  settles, `outcomeRef` (a blob holding its `NativeChildOutcome` with usage).
- Controls are `QueueCommand` entries with payload `native` and a
  `NativeControl`; the command's `outcome` is a typed `NativeControlOutcome`
  (`unknown`, with status `unknown`, when the host's answer was lost). A
  `respondInput` answer in a Mimir chat is the `answer` control: it is
  `unknown` when the reply was lost and `rejected` only when the host has no
  such pending question.
  Child steering reports `accepted` ("Guidance queued"), never delivery.
- `GetCommand {chatId, commandId}` reads the durable command entry without
  executing it. Clients use bounded reads after a control to show accepted,
  refused, pending, or unconfirmed results. A failed read never resends it.
- Reads: `GetNativeCatalog`, `GetNativePlan`, `ListNativeChildren`,
  `GetNativeChildOutcome {chatId, handle, attempt}`, which reads exactly that
  attempt, also after the child was continued. Child reads never claim a
  completion.

## Tests

```
cargo test -p roboco-harness --lib mimir
cargo test -p roboco-engine --test mimir_scripted
ROBOCO_MIMIR_BIN=<mimir> ROBOCO_MIMIR_PACKAGE=integrations/mimir/out/sh.roboco.bridge-0.1.0 \
  cargo test -p roboco-engine --test mimir_bridge_live -- --nocapture
```

The live test installs the package into a throwaway agent directory (about a
minute with a debug Mimir) unless `ROBOCO_MIMIR_ROOT` names one that has it,
and uses the loopback fake provider from `integrations/mimir/tests`. No
account or network provider is involved. Windows execution has not been run.

`mimir_authenticated` is ignored in ordinary test runs. It uses an explicitly
prepared private test agent directory with an installed bridge and an existing
login. It calls `chatgpt/gpt-6-luna` at each reasoning level in the host catalog,
checks Build and Plan configuration, and releases and reopens the same native
conversation:

```
ROBOCO_MIMIR_BIN=<mimir> ROBOCO_MIMIR_AUTH_ROOT=<private-test-root> \
  cargo test -p roboco-engine --test mimir_authenticated -- --ignored --nocapture
```

This is an authenticated engine smoke test, not a rendered desktop or full-app
end-to-end test. Rendered Linux desktop and native Windows verification remain
outstanding. The browser fixture and headless GPUI tests exercise native
controls, child tabs, recovery, and complete public detail exports.
