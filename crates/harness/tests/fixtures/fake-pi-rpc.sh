#!/bin/sh
# Fixture pi RPC process (ticket 21): a scripted stdin/stdout JSONL responder
# covering the pi first-party RPC protocol surface — id-correlated command
# responses, every event type the native driver maps, stdout noise/CRLF
# tolerance, turn errors, auto-retry, compaction, extension UI dialogs, and
# the steering queue — plus the prompt-delivery hardening surface
# (orchestration v2, ticket 02): prompt dispositions (started / queued /
# handled / none for old pi), a rejection while pi streams, a slow
# preflight, the settle probe (busy re-arm, failure, the settle race) and
# clear_queue strand recovery — and the mid-turn steering surface
# (orchestration v2, ticket 03): set_steering_mode all at session start,
# steer bursts delivered together in order, images riding a steer, and
# clear_queue-before-abort when a Stop finds steers pi never consumed —
# and the native fork surface (orchestration v2, ticket 19): the session
# entry tree behind get_entries (the driver walks it after each settle to
# record provider turn refs) and the `--fork` fork process (the source
# copy plus the exclusive fork RPC when the boundary is not the latest
# turn).
# NOTE: the python below is embedded in a sh single-quoted string - it must
# never contain an apostrophe (a stray one splits the script into argv).
exec python3 -u -c '
import base64, json, os, signal, subprocess, sys, time

def emit(value):
    # CRLF on purpose: the driver must tolerate it (371d79b1 lessons).
    print(json.dumps(value), end="\r\n", flush=True)

def noise(text):
    print(text, end="\r\n", flush=True)

def response(req, data=None):
    out = {"id": req.get("id"), "type": "response", "command": req["type"], "success": True}
    if data is not None:
        out["data"] = data
    emit(out)

def reject(req, message):
    emit({"id": req.get("id"), "type": "response", "command": req["type"], "success": False, "error": message})

def user_message(text):
    emit({"type": "message_start", "message": {"role": "user", "content": [{"type": "text", "text": text}]}})
    emit({"type": "message_end", "message": {"role": "user", "content": [{"type": "text", "text": text}]}})
    append_entry("user")

def assistant_chunks(chunks, thinking=False, stop="stop", error=None):
    emit({"type": "message_start", "message": {"role": "assistant", "content": []}})
    for chunk in chunks:
        kind = "thinking_delta" if thinking else "text_delta"
        emit({"type": "message_update", "usage": {"input": 500, "cacheRead": 300, "output": 10, "totalTokens": 810, "cost": {}},
              "assistantMessageEvent": {"type": kind, "contentIndex": 0 if not thinking else 1, "delta": chunk}})
    message = {"role": "assistant", "content": [], "stopReason": stop,
               "usage": {"input": 500, "cacheRead": 300, "output": 10, "totalTokens": 810}}
    if error:
        message["errorMessage"] = error
    emit({"type": "message_end", "message": message})
    append_entry("assistant")

def turn_and_settle(reply=None, thinking=False, stop="stop", error=None):
    emit({"type": "turn_start"})
    if thinking:
        assistant_chunks(["thinking hard "], thinking=True)
    if reply is not None:
        assistant_chunks([reply], stop=stop, error=error)
    emit({"type": "turn_end", "message": {"role": "assistant", "content": [], "stopReason": stop}, "toolResults": []})
    emit({"type": "agent_end", "messages": [], "willRetry": False})
    emit({"type": "agent_settled"})

def read_command():
    line = sys.stdin.readline()
    if not line:
        sys.exit(0)
    return json.loads(line.strip())

# --- session identity ------------------------------------------------------
argv = sys.argv[1:]
if "--version" in argv:
    # model_context() probes the binary version; answer it cleanly.
    print("0.87.0-fixture")
    sys.exit(0)
assert "--mode" in argv and argv[argv.index("--mode") + 1] == "rpc", argv
resumed_session = None
if "--session" in argv:
    resumed_session = argv[argv.index("--session") + 1]
    assert os.path.isfile(resumed_session), f"resume target missing: {resumed_session}"

title_mode = "--no-tools" in argv
if title_mode:
    # The title sandbox: isolated one-shot spawn (never resumes, never
    # writes a session), tools off, shared title instructions as the
    # system prompt. Extensions stay enabled (provider configs can be
    # extension-registered; --no-extensions would break model resolution).
    assert "--no-session" in argv, argv
    assert "--no-skills" in argv and "--no-prompt-templates" in argv, argv
    assert "--no-context-files" in argv and "--no-themes" in argv, argv
    assert "--system-prompt" in argv, argv
    assert "--no-extensions" not in argv, argv
    assert "--session" not in argv, argv

if os.environ.get("ROBOCO_MCP_SERVER"):
    # The Roboco MCP bridge: pi runs with the bridge extension on the
    # command line and the server spec in the environment when (and only
    # when) the engine injected its MCP server into this run.
    assert "--extension" in argv, argv
    bridge_path = argv[argv.index("--extension") + 1]
    assert os.path.isfile(bridge_path), "bridge extension missing: " + bridge_path
    with open(bridge_path) as handle:
        bridge = handle.read()
    assert "registerTool" in bridge and "tools/call" in bridge, "not the roboco bridge"
    spec = json.loads(os.environ["ROBOCO_MCP_SERVER"])
    assert spec["name"] == "roboco", spec
    assert isinstance(spec["args"], list), spec
else:
    assert "--extension" not in argv, argv

session_file = None if "--no-session" in argv else os.path.join(os.getcwd(), ".pi-fixture-session.jsonl")
if resumed_session is not None:
    # get_state reports the file the process was resumed with (pi answers
    # with the session it actually holds), so a forked chat that resumes
    # the copy keeps reporting THAT file as its session.
    session_file = resumed_session
fork_source = None
if "--fork" in argv:
    # The native-fork launch (ticket 19): a short-lived process that copies
    # the source session and re-roots it at the boundary. It never runs a
    # prompt, never resumes, and never loads the MCP bridge (--fork cannot
    # combine with --session / --no-session anyway).
    fork_source = argv[argv.index("--fork") + 1]
    assert "--session" not in argv and "--no-session" not in argv, argv
    assert os.path.isfile(fork_source), "fork source missing: " + fork_source
    session_file = os.path.join(os.getcwd(), ".pi-fixture-forked.jsonl")
if session_file is not None:
    with open(session_file, "a") as handle:  # exists so the driver resume check passes
        handle.write("fixture\n")
session_id = "pi-fixture-session"
thinking_level_seen = None
set_model_seen = None
steering_mode_seen = None
steering_queue = []
# pi isStreaming flag, scripted by the scenarios that need a run in
# flight (an extension-initiated run).
streaming = False

# --- session entry tree (ticket 19) ---------------------------------------
# Every user and assistant message appends a {type: message, id, parentId}
# entry so get_entries can serve the tree the driver walks after each
# settle for provider turn refs: the leaf chain u1 -> a1 -> u2 ... with the
# walk bounded by the `since` cursor the driver passes.
entry_seq = 0
leaf_id = None
entries = []

def append_entry(role):
    global entry_seq, leaf_id
    entry_seq += 1
    entry_id = ("u%d" if role == "user" else "a%d") % entry_seq
    entries.append({
        "type": "message",
        "id": entry_id,
        "parentId": leaf_id,
        "message": {"role": role},
    })
    leaf_id = entry_id

def entries_data(command):
    # The get_entries payload: append order, the entries strictly after
    # `since`, and the current leaf (None for an empty session). An unknown
    # `since` cursor fails (pi rejects it; the driver falls back to a full
    # listing).
    since = command.get("since")
    if since is None:
        return {"entries": list(entries), "leafId": leaf_id}
    ids = [entry["id"] for entry in entries]
    if since not in ids:
        return None
    return {"entries": entries[ids.index(since) + 1:], "leafId": leaf_id}

# The fork source copied history (ticket 19): three turns. The boundary
# tests exercise both "the latest turn" (last_turn u3: no RPC fork — the
# copy already ends there) and "an earlier turn" (last_turn u2 / u1: the
# exclusive fork RPC at the NEXT turn user entry, u3 / u2).
FORK_TREE = [
    {"type": "message", "id": "u1", "parentId": None, "message": {"role": "user"}},
    {"type": "message", "id": "a1", "parentId": "u1", "message": {"role": "assistant"}},
    {"type": "message", "id": "u2", "parentId": "a1", "message": {"role": "user"}},
    {"type": "message", "id": "a2", "parentId": "u2", "message": {"role": "assistant"}},
    {"type": "message", "id": "u3", "parentId": "a2", "message": {"role": "user"}},
    {"type": "message", "id": "a3", "parentId": "u3", "message": {"role": "assistant"}},
]


def state_data():
    return {
        "model": {"provider": "mock", "id": "mock/model", "name": "Mock", "contextWindow": 200000},
        "thinkingLevel": thinking_level_seen or "medium",
        "isStreaming": streaming, "isCompacting": False,
        "steeringMode": "one-at-a-time", "followUpMode": "one-at-a-time",
        "sessionFile": session_file, "sessionId": session_id,
        "sessionName": "fixture", "autoCompactionEnabled": True,
        "messageCount": 2, "pendingMessageCount": len(steering_queue),
    }

def stats_data():
    return {
        "sessionFile": session_file, "sessionId": session_id,
        "userMessages": 1, "assistantMessages": 1, "toolCalls": 0, "toolResults": 0,
        "totalMessages": 2,
        "tokens": {"input": 50000, "output": 10000, "cacheRead": 40000, "cacheWrite": 5000, "total": 105000},
        "cost": 0.45,
        "contextUsage": {"tokens": 60000, "contextWindow": 200000, "percent": 30},
    }

def tool_flow():
    emit({"type": "turn_start"})
    emit({"type": "message_update", "usage": {}, "assistantMessageEvent":
          {"type": "toolcall_start", "contentIndex": 0, "id": "tool-1", "toolName": "bash"}})
    emit({"type": "tool_execution_start", "toolCallId": "tool-1", "toolName": "bash",
          "args": {"command": "printf TOOL-OK"}})
    emit({"type": "tool_execution_update", "toolCallId": "tool-1", "toolName": "bash",
          "args": {"command": "printf TOOL-OK"}, "partialResult": {"content": [{"type": "text", "text": "TOOL"}]}})
    emit({"type": "tool_execution_end", "toolCallId": "tool-1", "toolName": "bash",
          "result": {"content": [{"type": "text", "text": "TOOL-OK"}], "details": {}}, "isError": False})
    emit({"type": "tool_execution_start", "toolCallId": "tool-2", "toolName": "edit",
          "args": {"path": "src/lib.rs", "edits": [{"oldText": "old", "newText": "new"}]}})
    emit({"type": "tool_execution_end", "toolCallId": "tool-2", "toolName": "edit",
          "result": {"content": [{"type": "text", "text": "edited"}],
                     "details": {"patch": "--- a/src/lib.rs\n+++ b/src/lib.rs\n@@ -1 +1 @@\n-old\n+new\n"}},
          "isError": True})
    assistant_chunks(["tools done"])
    emit({"type": "turn_end", "message": {"role": "assistant", "content": [], "stopReason": "stop"}, "toolResults": []})
    emit({"type": "agent_end", "messages": [], "willRetry": False})
    emit({"type": "agent_settled"})

def dialog_flow():
    # select → answer → confirm → answer → finish.
    emit({"type": "extension_ui_request", "id": "dlg-1", "method": "select",
          "title": "Pick a runner", "options": ["pnpm", "npm"]})
    answer = read_command()
    assert answer["type"] == "extension_ui_response" and answer["id"] == "dlg-1", answer
    assert answer["value"] == "npm", answer
    emit({"type": "extension_ui_request", "id": "dlg-2", "method": "confirm",
          "title": "Run it?", "message": "This will write files."})
    answer = read_command()
    assert answer["type"] == "extension_ui_response" and answer["id"] == "dlg-2", answer
    assert answer["confirmed"] is True, answer
    emit({"type": "extension_ui_request", "id": "dlg-3", "method": "notify",
          "message": "fire and forget", "notifyType": "info"})
    turn_and_settle("dialogs done")

def run_prompt(req):
    global streaming
    text = req["message"]
    if text == "require-resume":
        assert resumed_session == session_file, f"engine did not resume: {resumed_session}"
    if text.startswith("You generate session titles"):
        assert title_mode, "title instructions must only run in the title sandbox"
        response(req, {"disposition": "started"})
        user_message(text)
        turn_and_settle("Fix Login Flow")
        return
    if text == "reject":
        reject(req, "A prompt is already running (busy)")
        return
    if text == "handled":
        # pi runs the extension command itself: accepted, handled — no run
        # and no agent_settled ever follow.
        response(req, {"disposition": "handled"})
        return
    if text == "old-pi-handled":
        # pi < 0.99: no disposition field, and the command needed no run.
        response(req)
        return
    if text == "old-pi":
        # pi < 0.99: no disposition field — the driver infers whether a run
        # started from the settle probe.
        response(req)
        user_message(text)
        turn_and_settle("reply:" + text)
        return
    if text == "old-pi-stop":
        # B3: pi < 0.99 (no disposition) arms the settle probe while the run
        # is still live. A Stop then must NOT take the already-settled
        # shortcut: it clears the queue and aborts like any live turn. The
        # abort is recorded in the session file so the test can see it.
        streaming = True
        response(req)
        user_message(text)
        emit({"type": "agent_start"})
        emit({"type": "turn_start"})
        assistant_chunks(["working"])
        while True:
            command = read_command()
            kind = command["type"]
            if kind == "get_state":
                response(command, state_data())
            elif kind == "get_session_stats":
                response(command, stats_data())
            elif kind == "clear_queue":
                response(command, {"steering": [], "followUp": []})
            elif kind == "abort":
                with open(session_file, "a") as handle:
                    handle.write("abort\n")
                streaming = False
                emit({"type": "agent_settled"})
                response(command)
                return
            else:
                reject(command, "unexpected old-pi-stop command: " + kind)
    if text == "slow-preflight":
        # pi preflight (input handlers, before_agent_start hooks, the
        # Roboco MCP bridge, compaction) runs longer than any fixed
        # acceptance budget: the driver must wait, not time the run out.
        time.sleep(31)
    response(req, {"disposition": "started"})  # preflight acceptance

    if text == "crash":
        assistant_chunks(["partial text"])
        print("pi fatal: last stderr context", file=sys.stderr, flush=True)
        sys.exit(23)
    if text == "signal-crash":
        assistant_chunks(["partial text"])
        print("pi fatal: signal context", file=sys.stderr, flush=True)
        os.kill(os.getpid(), signal.SIGKILL)
    if text == "inherited-pipe-crash":
        subprocess.Popen(["sleep", "300"])
        print("pi fatal: inherited pipe context", file=sys.stderr, flush=True)
        sys.exit(25)
    if text == "idle-crash":
        user_message(text)
        turn_and_settle("reply:" + text)
        # Answer the driver post-settle probe + stats, then exit while the
        # session is parked: the next dispatch must resume by --session.
        while True:
            followup = read_command()
            if followup["type"] == "get_state":
                response(followup, state_data())
            elif followup["type"] == "get_session_stats":
                response(followup, stats_data())
                break
        time.sleep(0.1)
        sys.exit(24)
    if text == "hung":
        # Accept, stream one chunk, then never settle: the interrupt path
        # must still end the run.
        assistant_chunks(["working"])
        while True:
            time.sleep(30)
    if text == "interrupt":
        assistant_chunks(["working"])
        abort = read_command()
        assert abort["type"] == "abort", abort
        emit({"type": "agent_settled"})
        response(abort)
        return
    if text == "tree":
        child = subprocess.Popen(["sh", "-c", "trap \"\" TERM; sleep 300 & echo $!; wait"],
                                 stdout=subprocess.PIPE, text=True)
        assistant_chunks(["tree:" + str(child.pid), "tree:" + child.stdout.readline().strip()])
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        time.sleep(300)
    if text == "tools":
        user_message(text)
        tool_flow()
        return
    if text == "thinking":
        user_message(text)
        turn_and_settle("reply:" + text, thinking=True)
        return
    if text == "which-model":
        # Reflect the configuration the process currently runs with: the
        # model id set_model last selected (none = the default state row)
        # and the thinking level set_thinking_level last applied.
        model = set_model_seen.split("|", 1)[1] if set_model_seen else "mock/model"
        level = thinking_level_seen or "medium"
        user_message(text)
        turn_and_settle("reply:" + model + "/" + level)
        return
    if text == "error":
        user_message(text)
        emit({"type": "turn_start"})
        assistant_chunks(["doomed"], stop="error", error="provider returned 529")
        emit({"type": "turn_end", "message": {"role": "assistant", "content": [], "stopReason": "error"}, "toolResults": []})
        emit({"type": "agent_end", "messages": [], "willRetry": False})
        emit({"type": "agent_settled"})
        return
    if text == "retry-exhausted":
        user_message(text)
        emit({"type": "auto_retry_start", "attempt": 1, "maxAttempts": 3, "delayMs": 10, "errorMessage": "overloaded"})
        emit({"type": "auto_retry_end", "success": False, "attempt": 3, "finalError": "provider overloaded (all retries failed)"})
        emit({"type": "agent_settled"})
        return
    if text == "retry-recover":
        user_message(text)
        emit({"type": "auto_retry_start", "attempt": 1, "maxAttempts": 3, "delayMs": 10, "errorMessage": "overloaded"})
        emit({"type": "auto_retry_end", "success": True, "attempt": 1})
        turn_and_settle("reply:" + text)
        return
    if text == "compaction-fail":
        user_message(text)
        emit({"type": "compaction_start", "reason": "threshold"})
        emit({"type": "compaction_end", "reason": "threshold", "result": None, "aborted": False,
              "willRetry": False, "errorMessage": "summarizer failed"})
        emit({"type": "agent_settled"})
        return
    if text == "compaction-ok":
        user_message(text)
        emit({"type": "compaction_start", "reason": "threshold"})
        emit({"type": "compaction_end", "reason": "threshold",
              "result": {"summary": "s", "firstKeptEntryId": "e1", "tokensBefore": 10, "estimatedTokensAfter": 5},
              "aborted": False, "willRetry": False})
        turn_and_settle("reply:" + text)
        return
    if text == "extension-ui":
        user_message(text)
        dialog_flow()
        return
    if text == "steer-flow":
        user_message(text)
        emit({"type": "turn_start"})
        assistant_chunks(["first part "])
        steer = read_command()
        # A mid-run steer is the same prompt with steer behavior: pi queues
        # it while streaming.
        assert steer["type"] == "prompt" and steer.get("streamingBehavior") == "steer", steer
        assert steer["message"] == "steered text", steer
        steering_queue.append(steer["message"])
        # pi order: the queue_update precedes the acknowledgement.
        emit({"type": "queue_update", "steering": list(steering_queue), "followUp": []})
        response(steer, {"disposition": "queued"})
        # The current assistant turn keeps running a while before the
        # boundary delivers the steer (realistic pacing; also widens the
        # window engine tests use to observe the steer landing mid-run).
        time.sleep(0.4)
        emit({"type": "turn_end", "message": {"role": "assistant", "content": [], "stopReason": "stop"}, "toolResults": []})
        # pi delivers the steer at the boundary: queue drains, then the user
        # message arrives (the consumed signal), then the reply to it.
        steering_queue.remove(steer["message"])
        emit({"type": "queue_update", "steering": [], "followUp": []})
        user_message(steer["message"])
        turn_and_settle("reply-to-steer")
        return
    if text == "steer-burst":
        # Several quick steers (steering mode all): pi queues each while
        # streaming and delivers them TOGETHER at the next step boundary,
        # in send order — none dropped, none reordered.
        user_message(text)
        emit({"type": "turn_start"})
        assistant_chunks(["first part "])
        steers = []
        for _ in range(3):
            steer = read_command()
            assert steer["type"] == "prompt" and steer.get("streamingBehavior") == "steer", steer
            steers.append(steer["message"])
            steering_queue.append(steer["message"])
            emit({"type": "queue_update", "steering": list(steering_queue), "followUp": []})
            response(steer, {"disposition": "queued"})
        assert steers == ["burst one", "burst two", "burst three"], steers
        emit({"type": "turn_end", "message": {"role": "assistant", "content": [], "stopReason": "stop"}, "toolResults": []})
        del steering_queue[:]
        emit({"type": "queue_update", "steering": [], "followUp": []})
        for message in steers:
            user_message(message)
        turn_and_settle("reply-to-burst")
        return
    if text == "steer-image":
        # A steer carries its attached images: the prompt command must ride
        # the same inline ImageContent blocks a turn-start prompt does.
        user_message(text)
        emit({"type": "turn_start"})
        assistant_chunks(["first part "])
        steer = read_command()
        assert steer["type"] == "prompt" and steer.get("streamingBehavior") == "steer", steer
        images = steer.get("images")
        assert isinstance(images, list) and len(images) == 1, steer
        assert images[0]["type"] == "image", images
        assert images[0]["mimeType"] == "image/png", images
        expected = b"\x89PNG\r\n\x1a\n" + b"roboco steer image"
        assert base64.b64decode(images[0]["data"]) == expected, images
        steering_queue.append(steer["message"])
        emit({"type": "queue_update", "steering": list(steering_queue), "followUp": []})
        response(steer, {"disposition": "queued"})
        emit({"type": "turn_end", "message": {"role": "assistant", "content": [], "stopReason": "stop"}, "toolResults": []})
        del steering_queue[:]
        emit({"type": "queue_update", "steering": [], "followUp": []})
        user_message(steer["message"])
        turn_and_settle("reply-to-image-steer")
        return
    if text == "stop-clears-queue":
        # A steer accepted into pi queue but never consumed when Stop lands:
        # the driver must clear pi queue BEFORE the abort, so the steer is
        # cancelled with the turn instead of delivering into the aborted run.
        user_message(text)
        emit({"type": "turn_start"})
        assistant_chunks(["working"])
        steer = read_command()
        assert steer["type"] == "prompt" and steer.get("streamingBehavior") == "steer", steer
        steering_queue.append(steer["message"])
        emit({"type": "queue_update", "steering": list(steering_queue), "followUp": []})
        response(steer, {"disposition": "queued"})
        # The marker: the steer is accepted (pi queue holds it) and the run
        # is still streaming — this is the Stop window.
        assistant_chunks(["steer queued"])
        cleared = read_command()
        assert cleared["type"] == "clear_queue", cleared
        assert steering_queue == ["stranded steer"], steering_queue
        del steering_queue[:]
        response(cleared, {"steering": [], "followUp": []})
        abort = read_command()
        assert abort["type"] == "abort", abort
        emit({"type": "agent_settled"})
        response(abort)
        return
    if text == "compaction-recover":
        # An overflow error triggers compaction with a retry; the compaction
        # succeeds and the turn is retried: the run must end COMPLETED —
        # the recovered error no longer rejects the prompt.
        user_message(text)
        emit({"type": "turn_start"})
        assistant_chunks(["doomed"], stop="error", error="context window overflow")
        emit({"type": "turn_end", "message": {"role": "assistant", "content": [], "stopReason": "error"}, "toolResults": []})
        emit({"type": "compaction_start", "reason": "overflow"})
        emit({"type": "compaction_end", "reason": "overflow",
              "result": {"summary": "s", "firstKeptEntryId": "e1", "tokensBefore": 10, "estimatedTokensAfter": 5},
              "aborted": False, "willRetry": True})
        emit({"type": "turn_start"})
        assistant_chunks(["recovered"])
        emit({"type": "turn_end", "message": {"role": "assistant", "content": [], "stopReason": "stop"}, "toolResults": []})
        emit({"type": "agent_end", "messages": [], "willRetry": False})
        emit({"type": "agent_settled"})
        return
    if text == "steer-strand":
        # The steer is accepted mid-run but never delivered: pi queue
        # check happened before it landed, so the run settles with the
        # steer stranded. The settle probe must clear the queue and hand
        # the text back for the next turn.
        user_message(text)
        emit({"type": "turn_start"})
        assistant_chunks(["reply part"])
        steer = read_command()
        assert steer["type"] == "prompt" and steer.get("streamingBehavior") == "steer", steer
        steering_queue.append(steer["message"])
        response(steer, {"disposition": "queued"})
        emit({"type": "turn_end", "message": {"role": "assistant", "content": [], "stopReason": "stop"}, "toolResults": []})
        emit({"type": "agent_end", "messages": [], "willRetry": False})
        emit({"type": "agent_settled"})
        return
    if text == "follow-up-prompt":
        # A parked session next message arrives as a new prompt.
        user_message(text)
        turn_and_settle("reply:" + text)
        return
    if text == "noise":
        noise("not json at all")
        noise("")
        user_message(text)
        turn_and_settle("reply:" + text)
        return
    if text == "huge":
        user_message(text)
        emit({"type": "turn_start"})
        assistant_chunks(["x" * (1024 * 1024 + 17)])
        emit({"type": "turn_end", "message": {"role": "assistant", "content": [], "stopReason": "stop"}, "toolResults": []})
        emit({"type": "agent_end", "messages": [], "willRetry": False})
        emit({"type": "agent_settled"})
        return
    if text == "unknown-events":
        user_message(text)
        emit({"type": "future_event_kind", "detail": "additive upstream"})
        emit({"type": "entry_appended", "entry": {"id": "e1"}})
        emit({"type": "session_info_changed", "name": "renamed"})
        emit({"type": "thinking_level_changed", "level": "high"})
        emit({"type": "summarization_retry_scheduled", "attempt": 1, "maxAttempts": 2, "delayMs": 10, "errorMessage": "x"})
        emit({"type": "bash_execution_update", "delta": "stray"})
        emit({"type": "extension_error", "extensionPath": "/x.js", "event": "boot", "error": "boom"})
        emit({"type": "response", "command": "parse", "success": False, "error": "unmatched id"})
        turn_and_settle("reply:" + text)
        return
    if text == "wake-turn":
        # Extension-initiated run AFTER the prompted turn settles: the driver
        # must emit a second Done for it.
        user_message(text)
        turn_and_settle("reply:" + text)
        # Close the prompted turn fully (settle probe + stats) before the
        # wake run starts — the fake is single-threaded and cannot answer
        # the probe while a scenario runs.
        probe = read_command()
        assert probe["type"] == "get_state", probe
        response(probe, state_data())
        stats = read_command()
        assert stats["type"] == "get_session_stats", stats
        response(stats, stats_data())
        time.sleep(0.3)
        emit({"type": "agent_start"})
        emit({"type": "turn_start"})
        assistant_chunks(["wake output"])
        emit({"type": "turn_end", "message": {"role": "assistant", "content": [], "stopReason": "stop"}, "toolResults": []})
        emit({"type": "agent_settled"})
        return
    if text == "reject-while-streaming":
        # The prompted turn completes and parks; an extension-initiated run
        # starts streaming; the user message sent then is delivered as a
        # prompt (steer behavior) and pi REJECTS it. The driver must keep
        # the session alive, surface the rejection, return the message to
        # its queue, and re-deliver it once the extension run winds down.
        user_message(text)
        turn_and_settle("reply:" + text)
        # Close the prompted turn fully (settle probe + stats) before the
        # wake run starts — the fake is single-threaded and cannot answer
        # the probe while a scenario sleeps. A probe left unanswered would
        # both kill this scenario at the prompt assert (the buffered
        # get_state is what read_command returns) and make the driver queue
        # the steer behind the in-flight probe instead of delivering it as
        # the prompt pi rejects.
        probe = read_command()
        assert probe["type"] == "get_state", probe
        response(probe, state_data())
        stats = read_command()
        assert stats["type"] == "get_session_stats", stats
        response(stats, stats_data())
        # The turn-ref capture (ticket 19) rides between the settle stats
        # and the Done: answer it before the streamed window opens.
        refs = read_command()
        assert refs["type"] == "get_entries", refs
        response(refs, entries_data(refs))
        time.sleep(0.3)  # the driver parks: the turn Done is out
        streaming = True
        emit({"type": "agent_start"})
        emit({"type": "turn_start"})
        assistant_chunks(["working"])
        prompt = read_command()
        assert prompt["type"] == "prompt" and prompt.get("streamingBehavior") == "steer", prompt
        reject(prompt, "A prompt is already running (busy)")
        # The extension run winds down.
        emit({"type": "turn_end", "message": {"role": "assistant", "content": [], "stopReason": "stop"}, "toolResults": []})
        emit({"type": "agent_end", "messages": [], "willRetry": False})
        streaming = False
        emit({"type": "agent_settled"})
        # Whatever the driver asks next (settle probe, stats) until the
        # message re-delivers as the next turn prompt — the loop tolerates
        # the driver interleaving its probe and the re-delivery either way
        # around the settle.
        while True:
            command = read_command()
            if command["type"] == "get_state":
                response(command, state_data())
            elif command["type"] == "get_session_stats":
                response(command, stats_data())
            elif command["type"] == "get_entries":
                data = entries_data(command)
                assert data is not None, command
                response(command, data)
            elif command["type"] == "prompt":
                assert command.get("streamingBehavior") == "steer", command
                response(command, {"disposition": "started"})
                user_message(command["message"])
                turn_and_settle("reply:" + command["message"])
                break
            else:
                reject(command, "unexpected command: " + command["type"])
        return
    if text == "late-ack":
        # B4: a steer races the settle. The run settles while the steer is
        # still in preflight (its acknowledgement has not landed), and the
        # settle probe reads pi at REST. The driver must not end the turn
        # on that quiescent reading while an input awaits its
        # acknowledgement: the turn ends only after the ack lands, the
        # acked steer runs, and ITS settle probes clean.
        user_message(text)
        emit({"type": "turn_start"})
        assistant_chunks(["working"])
        steer = read_command()
        assert steer["type"] == "prompt" and steer.get("streamingBehavior") == "steer", steer
        # Still in preflight: not in the queue yet (get_state reports no
        # pending message), and the run settles with it unacknowledged.
        emit({"type": "turn_end", "message": {"role": "assistant", "content": [], "stopReason": "stop"}, "toolResults": []})
        emit({"type": "agent_end", "messages": [], "willRetry": False})
        emit({"type": "agent_settled"})
        responded = False
        while True:
            command = read_command()
            kind = command["type"]
            if kind == "get_state":
                response(command, state_data())
            elif kind == "get_session_stats":
                response(command, stats_data())
                if not responded:
                    # The acknowledgement lands late, after the settle
                    # probe already read pi at rest.
                    responded = True
                    response(steer, {"disposition": "started"})
                    user_message(steer["message"])
                    turn_and_settle("reply-to-late-steer")
            elif kind == "get_entries":
                data = entries_data(command)
                assert data is not None, command
                response(command, data)
            elif kind == "clear_queue":
                drained = {"steering": list(steering_queue), "followUp": []}
                del steering_queue[:]
                response(command, drained)
            else:
                reject(command, "unexpected late-ack command: " + kind)
        return
    if text == "steer-burst-mixed":
        # B5: a burst of steers where the MIDDLE one carries images: the
        # driver must deliver the burst in mailbox order — the image steer
        # cannot be overtaken on the wire by the text steer queued after
        # it, so pi receives exactly [text, image, text].
        user_message(text)
        emit({"type": "turn_start"})
        assistant_chunks(["first part "])
        steers = []
        for wants_image in (False, True, False):
            steer = read_command()
            assert steer["type"] == "prompt" and steer.get("streamingBehavior") == "steer", steer
            if wants_image:
                images = steer.get("images")
                assert isinstance(images, list) and len(images) == 1, steer
                expected = b"\x89PNG\r\n\x1a\n" + b"roboco burst image"
                assert base64.b64decode(images[0]["data"]) == expected, images
            else:
                assert "images" not in steer, steer
            steers.append(steer["message"])
            steering_queue.append(steer["message"])
            emit({"type": "queue_update", "steering": list(steering_queue), "followUp": []})
            response(steer, {"disposition": "queued"})
        assert steers == ["mixed one", "mixed two", "mixed three"], steers
        emit({"type": "turn_end", "message": {"role": "assistant", "content": [], "stopReason": "stop"}, "toolResults": []})
        del steering_queue[:]
        emit({"type": "queue_update", "steering": [], "followUp": []})
        for message in steers:
            user_message(message)
        turn_and_settle("reply-to-mixed-burst")
        return
    if text == "reject-cap":
        # B1: pi rejects the parked message on every delivery. The driver
        # caps the re-delivery after the retries: the message is handed
        # back to the engine as a failed send and the turn ends ERRORED —
        # never an endless loop of Error + Completed for a message that
        # never ran.
        user_message(text)
        turn_and_settle("reply:" + text)
        # Close the prompted turn fully (probe + stats + refs) before the
        # parked message arrives, the way reject-while-streaming does.
        probe = read_command()
        assert probe["type"] == "get_state", probe
        response(probe, state_data())
        stats = read_command()
        assert stats["type"] == "get_session_stats", stats
        response(stats, stats_data())
        refs = read_command()
        assert refs["type"] == "get_entries", refs
        response(refs, entries_data(refs))
        while True:
            command = read_command()
            kind = command["type"]
            if kind == "prompt":
                assert command.get("streamingBehavior") == "steer", command
                reject(command, "A prompt is already running (busy)")
            elif kind == "get_state":
                response(command, state_data())
            elif kind == "get_session_stats":
                response(command, stats_data())
            elif kind == "get_entries":
                data = entries_data(command)
                assert data is not None, command
                response(command, data)
            else:
                reject(command, "unexpected reject-cap command: " + kind)
    if text == "probe-fail-mailbox":
        # B2: a message lands while the settle probe is in flight (the
        # first answer is held open), so the driver queues it behind the
        # turn; the probe then fails out and the turn ends errored. The
        # queued message must still run as the next turn: the mailbox
        # never wedges behind the dead turn.
        user_message(text)
        turn_and_settle("reply:" + text)
        for attempt in range(3):
            probe = read_command()
            assert probe["type"] == "get_state", probe
            if attempt == 0:
                time.sleep(1.0)
            reject(probe, "wedged")
        while True:
            command = read_command()
            kind = command["type"]
            if kind == "prompt":
                assert command.get("streamingBehavior") == "steer", command
                response(command, {"disposition": "started"})
                user_message(command["message"])
                turn_and_settle("reply:" + command["message"])
            elif kind == "get_state":
                response(command, state_data())
            elif kind == "get_session_stats":
                response(command, stats_data())
            elif kind == "get_entries":
                data = entries_data(command)
                assert data is not None, command
                response(command, data)
            else:
                reject(command, "unexpected probe-fail-mailbox command: " + kind)
    if text == "steer-strand-image":
        # B6: a steer carrying images strands in pi queue at settle. The
        # clear_queue recovery must re-queue the ORIGINAL message — the
        # re-delivered prompt carries its images again, never bare text.
        user_message(text)
        emit({"type": "turn_start"})
        assistant_chunks(["reply part"])
        steer = read_command()
        assert steer["type"] == "prompt" and steer.get("streamingBehavior") == "steer", steer
        images = steer.get("images")
        assert isinstance(images, list) and len(images) == 1, steer
        steering_queue.append(steer["message"])
        response(steer, {"disposition": "queued"})
        emit({"type": "turn_end", "message": {"role": "assistant", "content": [], "stopReason": "stop"}, "toolResults": []})
        emit({"type": "agent_end", "messages": [], "willRetry": False})
        emit({"type": "agent_settled"})
        expected = b"\x89PNG\r\n\x1a\n" + b"roboco strand image"
        while True:
            command = read_command()
            kind = command["type"]
            if kind == "get_state":
                response(command, state_data())
            elif kind == "clear_queue":
                drained = {"steering": list(steering_queue), "followUp": []}
                del steering_queue[:]
                response(command, drained)
            elif kind == "get_session_stats":
                response(command, stats_data())
            elif kind == "get_entries":
                data = entries_data(command)
                assert data is not None, command
                response(command, data)
            elif kind == "prompt":
                # The recovered message re-delivers with its images intact.
                recovered = command.get("images")
                assert isinstance(recovered, list) and len(recovered) == 1, command
                assert base64.b64decode(recovered[0]["data"]) == expected, recovered
                response(command, {"disposition": "started"})
                user_message(command["message"])
                turn_and_settle("reply-to-recovered-image-steer")
                return
            else:
                reject(command, "unexpected steer-strand-image command: " + kind)
    if text == "settle-race":
        # The settle probe is in flight when the user message arrives: the
        # driver must queue it behind the turn (or join the run) — never
        # strand the turn.
        user_message(text)
        turn_and_settle("reply:" + text)
        probe = read_command()
        assert probe["type"] == "get_state", probe
        # Hold the probe answer open so the raced message lands in the
        # window.
        time.sleep(1.0)
        response(probe, state_data())
        return
    if text == "busy-probe":
        # The settle probe answers BUSY twice (pi still streaming), then at
        # rest: the driver must re-arm after each busy report and end the
        # turn only once pi rests.
        user_message(text)
        turn_and_settle("reply:" + text)
        for attempt in range(3):
            probe = read_command()
            assert probe["type"] == "get_state", probe
            if attempt < 2:
                response(probe, dict(state_data(), isStreaming=True))
            else:
                response(probe, state_data())
        stats = read_command()
        assert stats["type"] == "get_session_stats", stats
        response(stats, stats_data())
        return
    if text == "probe-fail":
        # The settle probe fails every time: the turn must end ERRORED
        # (visibly, with the reason) — not hang, and not a silent kill.
        user_message(text)
        turn_and_settle("reply:" + text)
        for attempt in range(3):
            probe = read_command()
            assert probe["type"] == "get_state", probe
            reject(probe, "wedged")
        return
    if text == "fork-turn":
        # The forked chat first turn (ticket 19): the main process must
        # RESUME the session file the fork process created (never a fresh
        # session, never the source), and the prompt must arrive BARE — a
        # pasted history in front of it would not match this scenario at
        # all, so the default happy path answering a pasted prompt is the
        # oracle for the paste.
        assert resumed_session is not None, "the forked session was not resumed"
        assert resumed_session.endswith(".pi-fixture-forked.jsonl"), resumed_session
        user_message(text)
        turn_and_settle("reply:" + text)
        return
    if text == "fork-fallback":
        # The refused/stale fork fallback (ticket 19): a FRESH session (no
        # --session) with the budgeted history pasted in front of the
        # prompt.
        assert resumed_session is None, "the fallback must start a fresh session"
        assert "PINEAPPLE" in text, text
        assert text.endswith("fork-fallback"), text
        user_message(text)
        turn_and_settle("reply:fork-fallback")
        return
    # default happy path
    user_message(text)
    turn_and_settle("reply:" + text)

if fork_source is not None:
    # fork-mode process: get_state (the copy session file, never the
    # source), get_entries (the copied tree), the exclusive fork RPC when
    # the boundary is not the latest turn, get_state again, then EOF (the
    # driver shuts the process down). Markers land in the copy session
    # file so tests can prove which steps ran. A source file named with
    # "cancel" or "refuse" scripts the fork RPC failing those two ways.
    with open(session_file, "a") as handle:
        handle.write("fork-launch:" + fork_source + "\n")
    while True:
        command = read_command()
        kind = command["type"]
        if kind == "get_state":
            response(command, dict(state_data(), sessionFile=session_file, sessionId="pi-fixture-forked"))
        elif kind == "get_entries":
            response(command, {"entries": [dict(entry) for entry in FORK_TREE], "leafId": FORK_TREE[-1]["id"]})
        elif kind == "fork":
            assert command["entryId"] in ("u2", "u3"), command
            with open(session_file, "a") as handle:
                handle.write("fork-rpc:" + command["entryId"] + "\n")
            base = os.path.basename(fork_source)
            if "cancel" in base:
                response(command, {"text": "the boundary prompt", "cancelled": True})
            elif "refuse" in base:
                reject(command, "fork refused")
            else:
                response(command, {"text": "the boundary prompt", "cancelled": False})
        else:
            reject(command, "unexpected fork command: " + kind)

while True:
    command = read_command()
    kind = command["type"]
    if kind == "get_state":
        response(command, state_data())
    elif kind == "get_available_models":
        # Probe-shaped invocations (--no-session: the discovery probe, the
        # title one-shot) run with a neutral cwd and the fff home scan
        # disabled (perf 03a): discovery must never index $HOME — a probe
        # spawned in $HOME with pi-fff installed walks the whole tree and
        # times out. A SESSION process (a real chat run) legitimately serves
        # this command WITHOUT the probe env: the bare-legacy-id resolution
        # in apply_model asks its own session client for the live catalog
        # (pi-acp setSessionModel parity; roboco 86cb3339).
        if "--no-session" in argv:
            if os.environ.get("FFF_ENABLE_HOME_SCAN") != "0":
                reject(command, "probe env missing FFF_ENABLE_HOME_SCAN=0")
                continue
            if os.getcwd() == os.path.expanduser("~"):
                reject(command, "probe cwd is the home directory")
                continue
        # The live catalog: provider-scoped entries plus edge cases (a
        # provider-less row and a name-less row must be skipped or fall
        # back to the id; the bare-model entry backs legacy id resolution).
        response(command, {"models": [
            {"id": "flash-model", "name": "Flash Model", "provider": "mock", "reasoning": False},
            {"id": "reasoning-model", "name": "Reasoning Model", "provider": "mock", "reasoning": True},
            {"id": "nested/gateway/model", "name": "Nested Model", "provider": "mock", "reasoning": False},
            {"id": "no-provider-model", "name": "No Provider", "provider": "", "reasoning": False},
            {"id": "no-name-model", "provider": "mock", "reasoning": False},
            {"id": "bare-model", "name": "Bare Model", "provider": "mock", "reasoning": False},
        ]})
    elif kind == "set_model":
        assert command["provider"] and command["modelId"], command
        set_model_seen = command["provider"] + "|" + command["modelId"]
        if command["modelId"] == "reject-model":
            reject(command, "Model not found: " + command["provider"] + "/reject-model")
        else:
            if session_file is not None:
                with open(session_file, "a") as handle:
                    handle.write("model:" + set_model_seen + "\n")
            response(command, {"provider": command["provider"], "id": command["modelId"]})
    elif kind == "set_thinking_level":
        thinking_level_seen = command["level"]
        # Record the applied level in the session file so tests can prove
        # the wire command carried the requested reasoning level.
        with open(session_file, "a") as handle:
            handle.write("thinking:" + command["level"] + "\n")
        response(command)
    elif kind == "set_steering_mode":
        # The driver selects mode all at session start unless the user
        # configured one. Title runs never steer, so the sandbox must never
        # touch the setting. Recorded in the session file so tests can
        # prove the command was sent (or not, when configured).
        assert not title_mode, "title runs never set a steering mode"
        assert command["mode"] == "all", command
        steering_mode_seen = command["mode"]
        if session_file is not None:
            with open(session_file, "a") as handle:
                handle.write("steering-mode:" + command["mode"] + "\n")
        response(command)
    elif kind == "get_entries":
        # The settle capture (ticket 19): the driver walks the session
        # tree after each settle to name the turn provider ref. An unknown
        # `since` cursor is rejected (the driver re-reads the full tree).
        data = entries_data(command)
        if data is None:
            reject(command, "unknown since cursor: " + str(command.get("since")))
        else:
            response(command, data)
    elif kind == "get_session_stats":
        response(command, stats_data())
    elif kind == "clear_queue":
        drained = {"steering": list(steering_queue), "followUp": []}
        steering_queue = []
        response(command, drained)
    elif kind == "prompt":
        run_prompt(command)
    elif kind == "abort":
        response(command)
    else:
        reject(command, f"Unknown command: {kind}")
' "$@"
