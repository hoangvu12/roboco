#!/bin/sh
# Fixture pi RPC process (ticket 21): a scripted stdin/stdout JSONL responder
# covering the pi first-party RPC protocol surface — id-correlated command
# responses, every event type the native driver maps, stdout noise/CRLF
# tolerance, turn errors, auto-retry, compaction, extension UI dialogs, and
# the steering queue. Replaces fake-pi-acp.sh for the native driver.
exec python3 -u -c '
import json, os, signal, subprocess, sys, time

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
assert "--mode" in argv and argv[argv.index("--mode") + 1] == "rpc", argv
resumed_session = None
if "--session" in argv:
    resumed_session = argv[argv.index("--session") + 1]
    assert os.path.isfile(resumed_session), f"resume target missing: {resumed_session}"

session_file = os.path.join(os.getcwd(), ".pi-fixture-session.jsonl")
with open(session_file, "a") as handle:  # exists so the driver resume check passes
    handle.write("fixture\n")
session_id = "pi-fixture-session"
thinking_level_seen = None
steering_queue = []

def state_data():
    return {
        "model": {"provider": "mock", "id": "mock/model", "name": "Mock", "contextWindow": 200000},
        "thinkingLevel": thinking_level_seen or "medium",
        "isStreaming": False, "isCompacting": False,
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
    text = req["message"]
    if text == "require-resume":
        assert resumed_session == session_file, f"engine did not resume: {resumed_session}"
    if text == "reject":
        reject(req, "A prompt is already running (busy)")
        return
    response(req)  # preflight acceptance

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
        # Answer the driver post-settle stats poll, then exit while the
        # session is parked: the next dispatch must resume by --session.
        while True:
            followup = read_command()
            if followup["type"] == "get_session_stats":
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
        assert steer["type"] == "steer" and steer["message"] == "steered text", steer
        steering_queue.append(steer["message"])
        emit({"type": "queue_update", "steering": list(steering_queue), "followUp": []})
        response(steer)
        emit({"type": "turn_end", "message": {"role": "assistant", "content": [], "stopReason": "stop"}, "toolResults": []})
        # pi delivers the steer at the boundary: queue drains, then the user
        # message arrives, then the reply to it.
        steering_queue.remove(steer["message"])
        emit({"type": "queue_update", "steering": [], "followUp": []})
        user_message(steer["message"])
        turn_and_settle("reply-to-steer")
        return
    if text == "steer-strand":
        # The steer is accepted mid-run but never delivered: the pi queue
        # check happened before it landed, so the run settles with the
        # steer stranded. The driver must recover it via clear_queue and
        # deliver it as a fresh prompt after the Done.
        user_message(text)
        emit({"type": "turn_start"})
        assistant_chunks(["reply part"])
        steer = read_command()
        assert steer["type"] == "steer", steer
        steering_queue.append(steer["message"])
        response(steer)
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
        time.sleep(0.3)
        emit({"type": "agent_start"})
        emit({"type": "turn_start"})
        assistant_chunks(["wake output"])
        emit({"type": "turn_end", "message": {"role": "assistant", "content": [], "stopReason": "stop"}, "toolResults": []})
        emit({"type": "agent_settled"})
        return
    # default happy path
    user_message(text)
    turn_and_settle("reply:" + text)

while True:
    command = read_command()
    kind = command["type"]
    if kind == "get_state":
        response(command, state_data())
    elif kind == "set_thinking_level":
        thinking_level_seen = command["level"]
        response(command)
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
