# sh.roboco.bridge

A Mimir plugin that lets the Roboco engine own native Mimir sessions over stdio. It is a WASI 0.3 component built against the public `mimir-plugin-sdk` 0.13. It runs as a headless runtime frontend and speaks framed JSON-RPC 2.0.

The host stays the authority. The bridge holds session attachments and view subscriptions, forwards SDK data as JSON, and returns typed errors. It keeps no second copy of session state, no scheduler and no journal reader.

This directory is standalone. It is not part of the Roboco Cargo workspace.

## Layout

| Path | Purpose |
| --- | --- |
| `src/lib.rs` | crate root, protocol core compiles natively, frontend compiles for WASI only |
| `src/limits.rs` | frame and chunk budgets |
| `src/framing.rs` | NDJSON splitter with a frame limit and EOF tail |
| `src/wire.rs` | JSON-RPC envelopes, ids, error codes |
| `src/outbox.rs` | control lane, event lane, fragmenting |
| `src/methods.rs` | method list, parameter parsing, host contract versions |
| `src/results.rs` | result shaping and page clamping |
| `src/bridge.rs` | WASI frontend, one async writer, one reader, the session hub |
| `build.py` | reproducible build and package entrypoint |
| `tests/bridge_client.py` | pipe client for `mimir plugin run` |
| `tests/fake_provider.py` | loopback OpenAI-compatible streaming fake |
| `tests/transport.py` | real-process acceptance harness |

## Build and package

```
python3 build.py --sdk-archive <mimir-plugin-sdk-0.13.0.crate> [--mimir <mimir binary>]
```

The script extracts the archive, checks its version against `Cargo.toml`, runs the native tests, builds `wasm32-wasip2` in release mode against that extraction, validates the component, and checks the imports and exports. It requires `wasi:cli/stdin@0.3.0` and `stdout@0.3.0`, exports `mimir:frontend/frontend@1.0.0` and `mimir:plugin-core/lifecycle@3.0.0`, and compares the Mimir imports with `HOST_CONTRACTS`. With `--mimir` it also runs `plugin check`.

Output goes to `out/`.

| File | Content |
| --- | --- |
| `sh.roboco.bridge-0.1.0/` | `mimir-plugin.toml`, `plugin.wasm`, `PACKAGE.json`, `INSTALL.txt` |
| `sh.roboco.bridge-0.1.0.tgz` | the same directory as an archive |
| `sh.roboco.bridge-0.1.0.tgz.sha256` | archive digest |
| `INSTALL.txt` | install commands with the concrete digest |

`PACKAGE.json` records the SDK archive digest, the source checksum, the wasm digest and the import versions.

Two clean builds from the same checkout path give byte identical wasm and archive. A different checkout or `--work` path changes the wasm, because Cargo hashes path dependencies into symbol names.

## Install

Nothing installs automatically. Read `out/INSTALL.txt`. The short form is

```
mimir plugin check out/sh.roboco.bridge-0.1.0
mimir plugin install out/sh.roboco.bridge-0.1.0
mimir plugin list
```

Installing from a directory compiles the component. A debug Mimir needs about a minute. Use a throwaway `MIMIR_CODING_AGENT_DIR` to try it without touching real settings.

The engine launches `mimir plugin run sh.roboco.bridge` and passes no other flag. Bridge configuration travels in `initialize`. `MIMIR_CODING_AGENT_DIR` is inherited from the engine environment unchanged.

`mimir plugin run` needs stdout to be a pipe. A regular file as stdout fails with an epoll `EPERM` in the host.

## Test

```
cd integrations/mimir
python3 tests/transport.py --mimir <mimir binary> [--package out/sh.roboco.bridge-0.1.0] [--keep] [--report report.json] [-v] [-k name]
```

The harness makes a fresh isolated root, installs the built package with `plugin check`, `plugin install` and `plugin list`, starts the fake provider, and drives real `mimir plugin run` processes over pipes. No account or network provider is used. `--root` with `--skip-install` reuses an installed root for quick runs. Give `--root` an absolute path.

The fake provider routes by request. A request that advertises the `agent` tool is a parent turn and reads `FakeProvider.script`. Any other request is a child turn and reads `FakeProvider.child_script`. A scripted reply can be a function of the request body. Child tests launch real native agents through the advertised `agent` tool schema. No child notification backend is faked.

`tests/repro_stale_view_state.py --mimir <binary> --root <installed root>` reproduces the host gap described under Known gaps and exits 1 while it is present.

Native protocol tests run with `cargo test --lib` through `build.py`, or by hand with

```
cargo --config "patch.crates-io.mimir-plugin-sdk.path='<extracted sdk dir>'" test --lib --locked
```

## Wire protocol

One JSON-RPC 2.0 message per line, UTF-8, `\n` terminated. `\r\n` and blank lines are accepted on input. Embedded newlines are always escaped in output. A final unterminated line at EOF is served.

Ids are strings or numbers and are echoed byte for byte. The raw id literal, quotes included, may be at most 256 bytes. A missing id is a notification and gets no reply. A `null`, boolean, object, array or over long id gets an `invalid_request` error with `id: null`. Batches are rejected. `jsonrpc` must be `"2.0"`. Incoming responses are ignored.

Every method uses an object `params` with unknown fields rejected.

### Handshake

The first request must be `initialize`.

```
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocol_versions":[1],"client":{"name":"roboco","version":"1"},"limits":{"max_frame_bytes":1048576,"chunk_bytes":262144}}}
```

The result carries `protocol_version`, `bridge`, `client`, `limits`, `host_contracts`, `capabilities`, `methods`, `features` and `ready`. Other methods before it return `not_initialized`. A second call returns `already_initialized`. No shared version returns `incompatible_protocol` with `data.supported`.

Frame budget is 1 MiB by default, 64 KiB to 16 MiB allowed. Chunk size is 256 KiB by default, 1 KiB minimum, and is clamped so its base64 fits the frame. Out of range proposals are rejected with `-32602`, not clamped.

`capabilities` is whatever the host reports to `plugin run`. It can be empty while human commands still work. The bridge does not pre-gate on it, the host decides per call.

### Methods

| Method | Params | Result |
| --- | --- | --- |
| `bridge.status` | none | initialized flag, sessions, views, pending count, queued output bytes, limits |
| `bridge.shutdown` | none | `{closed_sessions}` after every attachment is released |
| `catalog` | `{cwd}` | providers and models |
| `session.list` | `{cwd?, cursor?}` | `{sessions}` |
| `session.create` | `{cwd, configuration?}` | `{session, state, reused}` |
| `session.open` | `{session}` | `{session, state, reused}` |
| `session.close` | `{session}` | `{released}` |
| `session.state` | `{session}` | state, configuration, active request, requests, plan, goal |
| `session.open_view` | `{session, detail?: "full"\|"lifecycle"}` | `{view, session}` then `view.event` notifications |
| `view.close` | `{view}` | `{closed}` |
| `session.read_entries` | `{session, cut, anchor?, max_entries?, max_bytes?}` | `{cut, entries, bytes, older, newer}` |
| `session.read_entry_chunk` | `{session, cut, id, offset?, max_bytes?}` | `{id, offset, total, length, encoding, data}` |
| `session.commands` | `{session}` | `{commands}` |
| `session.command` | `{session, name, tail?}` | host command result |
| `session.skills`, `session.plan`, `session.children` | `{session}` | host data |
| `session.child_outcome` | `{session, handle, attempt}` | host data |
| `session.steer_child` | `{session, handle, attempt, text}` | host result |
| `session.stop_child` | `{session, handle, attempt}` | host result |
| `session.prompt` | `{session, input:{text, images?, skills?}, delivery:"start"\|"follow_up", submission_key?}` | `{request_id}` |
| `session.steer` | `{session, text}` | host result |
| `session.configure` | `{session, change}` | host result |
| `session.decide_plan` | `{session, plan_id, decision}` | host result |
| `session.change_goal` | `{session, change}` | host result |
| `session.answer` | `{session, request_id, answers}` | host result |
| `session.cancel_request` | `{session, request_id}` | host result |
| `session.cancel_all` | `{session}` | host result |
| `session.attach_mcp` | `{session, servers}` | attachment result |
| `session.detach_mcp` | `{session, attachment}` | host result |

Images in `input.images` are `{media_type, data}` with base64 data.

`session.notify` and `session.context_snapshot` return `-32601` with `data.reason` set to "model-only context is never exposed through the bridge". Unknown methods and unknown commands never become prompts.

### Views

`session.open_view` returns before the first event. Events arrive as notifications.

```
{"jsonrpc":"2.0","method":"view.event","params":{"view":"...","item":{"observation":{"request_id":"...","child_attempt":null,"observation":{"sequence":3,"source":"...","event":{"text_delta":{"index":0,"value":"hi"}}}}}}}
```

Items are `start`, `reset`, `state`, `child`, `observation`, `checkpoint`, `display`, and `completed`. Each view is independent. Views and attachments from many sessions share one stdio pair. A view that ends sends `view.ended`. An event that cannot be serialized sends `view.event_error`.

A root `completed` item means the root prompt finished. It does not mean the goal or the child sessions are idle. Read `session.state` and `session.children` for that.

A view's first item is `start`. It carries the state, the root checkpoint, `children` and `recovering`, all captured together. `children` lists the children as of that root checkpoint. Child changes after the checkpoint arrive as `child` items that follow the start, so a consumer applies `start.children` and then every later `child` item. A view opened while a child runs can show an empty `children` and then a `child` item for it. A view opened after the root settles lists the child in `start.children`.

Each `children` entry and each `child` item is a `ViewChild`, either `{"child": ChildInfo}` or `{"oversized": ChildHeader}`.

```
{"child":{"child":{"handle":"agent-d9f04c6fe3392859","attempt":1,"profile":"general-purpose","description":"Probe child","model":"fake/fake-model","status":"running","background":true,"spawned_by":"c9073ce2-...","completion_pending":false,"presentation":{"agent_id":"agent-d9f04c6fe3392859","status":"running","phase":"running","turns":1,"tokens":18,"elapsed_ms":40,"...":"..."},"cut":{"journal":{"child":"agent-d9f04c6fe3392859"},"leaf":"944cc896-..."}}}}
{"child":{"oversized":{"handle":"agent-d9f04c6fe3392859","attempt":3,"status":"completed","completion_pending":true,"cut":{"journal":{"child":"agent-d9f04c6fe3392859"},"leaf":null},"bytes":2000000}}}
```

`ChildInfo.cut` and `ChildHeader.cut` are optional and serialize as `null`. A `null` cut means the child journal could not be read at that point. Read details from `session.children`, which returns canonical `ChildInfo` and supersedes an oversized header. A cut in a view is the last cut published before that item. A cut from `session.children` is the latest. The two can differ in `leaf` for the same attempt. Both name the journal `{"child": handle}`. Pass either to `session.read_entries` as `cut`. The oversized variant has no real host trigger in the harness. Its shape is covered by native tests only.

`status` is one of `queued`, `running`, `completed`, `failed`, `deadline`, `stopped`, `interrupted`.

### Errors

Errors are `{"code","message","data":{"kind",...}}`.

| Code | Kind |
| --- | --- |
| -32700, -32600, -32601, -32602, -32603 | parse, invalid_request, method_not_found, invalid_params, internal |
| -32001 | closed |
| -32002 | not_ready |
| -32003 | not_found |
| -32004 | busy (another process holds the session) |
| -32005 | invalid |
| -32006 | cancelled |
| -32007 | failed |
| -32010 | not_attached |
| -32011 | overloaded (more than 256 requests pending) |
| -32012 | frame_too_large (the line is discarded, the stream recovers) |
| -32013 | not_initialized |
| -32014 | already_initialized |
| -32015 | incompatible_protocol |
| -32016 | shutting_down |
| -32017 | unknown_view |
| -32018 | unknown_attachment |

Codes -32001 to -32007 mirror the host session errors.

### Large payloads

No frame exceeds the negotiated budget. A message that would is split into `bridge.fragment` notifications.

```
{"jsonrpc":"2.0","method":"bridge.fragment","params":{"transfer":"t1","index":0,"count":3,"total_bytes":123456,"data":"<base64>"}}
```

The receiver concatenates the decoded `data` of indexes `0..count`, checks the length against `total_bytes`, and parses the result as one JSON message. Fragments of different transfers may interleave. Fragments of one transfer arrive in order.

`session.read_entries` clamps its page budget to at least 1 KiB and at most three quarters of the negotiated frame, capped at 4 MiB. An entry larger than the page budget arrives as an `oversized` record with its `id` and `bytes`. Read it with `session.read_entry_chunk` from offset 0 until `offset + length == total`. `data` is base64 of the entry's JSON bytes. Chunk reads are bounded by the negotiated chunk size.

### Output ordering and cleanup

One writer owns stdout. Control replies go before event traffic, events keep their order. Pending requests and queued output are bounded. When stdin reaches EOF, or stdout closes, or a fatal read fails, the bridge closes every view, MCP attachment and session through the host and waits for each close before it returns. The host recovers the runtime after the run returns. A killed process leaves the session recoverable by the next opener.

## Model context

Display entries from the host can carry `model_context` blocks. The bridge forwards SDK data unchanged. Engines must not treat `model_context` as chat content.

## Known gaps

- The Linux Mimir frontend suite passed all 10 tests sequentially. An earlier parallel run timed out in exact cancellation. Its cause remains unconfirmed.
- `plugin run` with a regular file as stdout fails with epoll `EPERM`. Report it to the Mimir owners.
- `capabilities()` is empty under `plugin run` even though human commands work.
- `plugin install` from a directory is slow in the debug build.
- Real children ran through the `agent` tool with the built in `general-purpose` profile. The `explore`, `plan` and `review` profiles, `depends_on` and `reviews` relations, `replace`, foreground launches and child loops were not run.
- A steer to a running child returns `accepted`. The harness does not assert that the child model later sees the text.
- Oversized `ChildHeader` items need a child whose details exceed the view's retained size. The real host was not driven to produce one.
- Plan decisions were run for `save_and_stop` and `implement`. `implement_fresh` was not run.
- The transport fixture's fake model has no reasoning control, so that suite checks refusal. The separate authenticated Roboco engine smoke passed `chatgpt/gpt-6-luna` at low, medium, high, xhigh, and max.
- `session.attach_mcp` and `session.detach_mcp` have native parameter tests only.
- Wasm output is reproducible only for the same checkout and work path.
- `transport.py` uses a fake OpenAI compatible provider. `crates/engine/tests/mimir_authenticated.rs` separately exercised an existing login through this packaged bridge and the Roboco engine. It is not a rendered desktop or full-app end-to-end test.

## Engine protocol summary

Result shapes below were captured from the real host through the packaged bridge. Fake provider replies stand in for the model.

| Call | Params | Result |
| --- | --- | --- |
| `session.children` | `{session}` | `{"children":[ChildInfo]}` |
| `session.child_outcome` | `{session, handle, attempt}` | `{"outcome":null}` while that attempt runs, then `{"outcome":{handle, attempt, status, result, error, changed_files, usage}}` for exactly that attempt, also after the child was continued. An attempt the child never had is `not_found`. The read never delivers or claims the result. |
| `session.steer_child` | `{session, handle, attempt, text}` | `{"control":"accepted"}`, `{"control":{"attempt_changed":N}}` with the current attempt, `{"control":"terminal"}` or `{"control":"finalizing"}` |
| `session.stop_child` | `{session, handle, attempt}` | same control values. An accepted stop settles as `status:"stopped"`. |
| `session.configure` | `{session, change:{provider?, model?, reasoning?, mode?: "build"\|"plan"}}` | `{"configuration":{provider, model, reasoning, mode}}`, or `-32005 invalid` with the state unchanged |
| `session.answer` | `{session, request_id, answers:[{question_id, selected_options, freeform_text, none_of_above}]}` | `{}`, or `-32003 not_found` when the request is no longer pending |
| `session.change_goal` | `{session, change}` where change is `{"start":{"objective","duration_seconds"}}`, `{"edit":text}`, `"pause"`, `{"resume":seconds\|null}` or `"clear"` | `{"request_id":id}` when the host started work, else `{"request_id":null}`. A change with no goal returns `-32005`. |
| `session.decide_plan` | `{session, plan_id, decision:"implement"\|"implement_fresh"\|"save_and_stop"}` | `{"request_id":id\|null}`. A stale or unknown plan id returns `-32007`. |
| `session.plan` | `{session}` | `{"plan":null}` or `{"plan":{id, name, path, markdown, sections, stages, status}}` |

- A child handle is `agent-` plus hex. Continuing a child keeps the handle and moves `attempt` forward. Steer and stop need the exact attempt. A stale attempt is refused with the current one and changes nothing.
- A completed child keeps `completion_pending: true` until the parent turn that receives the result runs. Reading `session.child_outcome` does not change it.
- A pending question shows as `state.user_request` and as an observation `user_request_ready`. It carries `id` and `questions[{id, prompt, allow_multiple, options[{label, description}]}]` with every option description intact. While it waits, `state.active_request` stays set and no `completed` item is sent. `session.answer` releases the waiting tool.
- The plan id for `session.decide_plan` is `session.plan` `id`, not the `plan_proposed` observation id. A `plan_proposed` observation exists once per proposal. `status` runs `review_pending`, `saved_stopped`, `accepted`, `implementing`, `completed`, `abandoned`.
- Goal `phase` is `active`, `paused`, `blocked`, `complete` or `cleared`. `pause` cancels the request in flight, which then completes with `stop_reason: "cancelled"`. A goal whose rounds do no work pauses itself with cause `no_progress`.
- A long quiet model stream and a settled root look different. While a stream is open, `state.active_request` is set and no `completed` item arrives for that request.

## Engine contract

1. Spawn `mimir plugin run sh.roboco.bridge` with piped stdin and stdout and `MIMIR_CODING_AGENT_DIR` unchanged. Drain stderr.
2. Send `initialize` first and read `limits`, `methods` and `host_contracts`. Refuse a bridge whose contracts differ from the engine build.
3. Run one reader that handles fragments before routing. Match replies by id. Route `view.event` by `params.view`.
4. Treat `-32004 busy` as another owner. Do not retry in a loop.
5. Treat the host as the source of truth. Rebuild state from `start`, `checkpoint` cuts and `session.read_entries`, not from a local copy. Apply `start.children` and then every later `child` item.
6. Release with `session.close` and wait for the reply. On shutdown send `bridge.shutdown`, wait for the reply, close stdin, and wait for the process to exit.
7. Never read model-only context. Never expose `model_context` as chat text.
