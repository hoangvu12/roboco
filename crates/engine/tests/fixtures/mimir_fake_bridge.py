#!/usr/bin/env python3
"""Scripted stand-in for `mimir plugin run sh.roboco.bridge`.

Speaks the bridge's framed JSON-RPC protocol on stdio for deterministic
failure paths the real host cannot produce on demand. State persists in
$FAKE_BRIDGE_DIR across processes; every call is appended to calls.jsonl.

scenario.json keys:
  missing_plugin       exit like `plugin run` without the installed plugin
  contracts            host_contracts reported at initialize
  lose_prompt_reply    admit the first prompt, then exit without replying
  page_bytes           read_entries page budget (entries above it are oversized)
  fragment_bytes       split messages above this size into bridge.fragment
  reset_after_prompt   after the first turn, rewind the branch and send reset
  reply_bytes          size of the scripted assistant reply
  hold_turns           admitted prompts keep running until cancelled
  lose_steer_reply     record the first steer, then exit without replying
  lose_command_reply   record the first command, then exit without replying
  lose_control_reply   record the first goal change, then exit without replying
  stall_prompt         record the first prompt but never admit or answer it
  refuse_prompt_once   refuse the first prompt as busy
  close_fails          refuse session.close
  crash_after_save     save the first reply, then exit before its checkpoint
  hold_tool            a streaming tool turn (root or child) stops after its
                       call is saved; the next bridge resumes it once its view opens
  stream_deltas        output deltas a streaming tool sends (64 KiB each)
  progress_lines       progress lines a streaming tool sends
  lose_answer_reply    apply the first answer, then exit without replying
  refuse_command_while_running
                       refuse a command as busy while a request runs

Prompts starting with "tool <variant>" run one tool call (see TOOL_VARIANTS).
"child tool" runs a streaming tool inside child agent-t and announces the
child again halfway through its output. "child twice" runs child agent-1 and
continues it before the turn returns. "ask" waits on question q-1.
Commands: /rewind N keeps the first N saved entries; /restore returns to the
branch before the last rewind. Both send a view reset.
"""

import base64
import json
import os
import sys
import uuid
from pathlib import Path

DIR = Path(os.environ["FAKE_BRIDGE_DIR"])
SCENARIO = json.loads((DIR / "scenario.json").read_text()) if (DIR / "scenario.json").exists() else {}
STATE_FILE = DIR / "state.json"
CONTRACTS = {
    "mimir:sessions/session-control": "8.0.0",
    "mimir:observations/session-observation": "7.0.0",
    "mimir:presentation/types": "3.0.0",
    "mimir:plugin-core/plugin-runtime": "3.0.0",
    "mimir:frontend/frontend": "1.0.0",
}
METHODS = [
    "initialize", "bridge.status", "bridge.shutdown", "catalog", "session.list", "session.create",
    "session.open", "session.close", "session.state", "session.open_view", "view.close",
    "session.read_entries", "session.read_entry_chunk", "session.commands", "session.command",
    "session.skills", "session.plan", "session.children", "session.child_outcome",
    "session.steer_child", "session.stop_child", "session.prompt", "session.steer",
    "session.configure", "session.decide_plan", "session.change_goal", "session.answer",
    "session.cancel_request", "session.cancel_all", "session.attach_mcp", "session.detach_mcp",
]

if SCENARIO.get("missing_plugin"):
    sys.stderr.write("Error: frontend sh.roboco.bridge must be installed, enabled, headless-eligible, and runtime-lifetime: \n")
    sys.exit(1)


def load():
    if STATE_FILE.exists():
        return json.loads(STATE_FILE.read_text())
    return {"sessions": {}, "flags": {}}


def save():
    STATE_FILE.write_text(json.dumps(STATE))


STATE = load()
VIEWS = {}
POSITION = [0]
TRANSFER = [0]


def record(method, params):
    with open(DIR / "calls.jsonl", "a") as calls:
        calls.write(json.dumps({"pid": os.getpid(), "method": method, "params": params}) + "\n")


def write(message):
    data = json.dumps(message, separators=(",", ":")).encode()
    limit = SCENARIO.get("fragment_bytes")
    if limit and len(data) > limit:
        TRANSFER[0] += 1
        chunks = [data[i : i + limit] for i in range(0, len(data), limit)]
        for index, chunk in enumerate(chunks):
            write_line({"jsonrpc": "2.0", "method": "bridge.fragment", "params": {
                "transfer": f"t{TRANSFER[0]}", "index": index, "count": len(chunks),
                "total_bytes": len(data), "data": base64.b64encode(chunk).decode()}})
        return
    write_line(message)


def write_line(message):
    sys.stdout.write(json.dumps(message, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def reply(id, result):
    write({"jsonrpc": "2.0", "id": id, "result": result})


def error(id, code, kind, message):
    write({"jsonrpc": "2.0", "id": id, "error": {"code": code, "message": message, "data": {"kind": kind}}})


def configuration():
    return {"provider": "fake", "model": "fake-model", "reasoning": None, "mode": "build"}


def view_state(session):
    s = STATE["sessions"][session]
    return {"info": {"id": session, "cwd": s["cwd"], "title": None, "live": True},
            "configuration": configuration(), "active_request": s.get("active"),
            "requests": s["requests"], "plan": None, "goal": None, "user_request": s.get("user_request")}


def cut(session):
    s = STATE["sessions"][session]
    return {"journal": "root", "leaf": s["entries"][-1]["id"] if s["entries"] else None}


def journal_entries(session, journal):
    s = STATE["sessions"][session]
    if isinstance(journal, dict) and "child" in journal:
        return s.get("children", {}).get(journal["child"], {}).get("entries", [])
    return s["entries"]


TERMINAL = ("completed", "failed", "deadline", "stopped", "interrupted")
NO_USAGE = {"input_tokens": 3, "output_tokens": 2, "reasoning_tokens": 0, "cache_read_tokens": 0,
            "cache_write_tokens": 0, "total_tokens": 5, "provider_usage": {"root": 0, "nodes": ["null"]}}


def child_cut(session, handle):
    entries = STATE["sessions"][session]["children"][handle]["entries"]
    return {"journal": {"child": handle}, "leaf": entries[-1]["id"] if entries else None}


def child_info(session, handle):
    attempt = STATE["sessions"][session]["children"][handle]["attempts"][-1]
    run = {"attempt": attempt["attempt"], "agent_id": handle, "agent": "general-purpose", "model": "fake-model",
           "description": "Probe child", "status": attempt["status"]}
    return {"handle": handle, "attempt": attempt["attempt"], "profile": "general-purpose",
            "description": "Probe child", "model": "fake-model", "status": attempt["status"], "background": False,
            "spawned_by": None, "completion_pending": attempt["status"] in TERMINAL, "presentation": run,
            "cut": child_cut(session, handle)}


def child_outcome(handle, attempt):
    return {"handle": handle, "attempt": attempt["attempt"], "status": attempt["status"],
            "result": attempt["result"], "error": None, "changed_files": [], "usage": NO_USAGE}


def child_changed(session, handle):
    event(session, {"child": {"child": child_info(session, handle)}})


def child_settled(session, handle, status, result):
    STATE["sessions"][session]["children"][handle]["attempts"][-1].update({"status": status, "result": result})
    save()
    child_changed(session, handle)


def child_checkpoint(session, handle, live):
    save()
    event(session, {"checkpoint": {"cut": child_cut(session, handle), "live_invocations": live}})


def child_event(session, handle, value):
    attempt = STATE["sessions"][session]["children"][handle]["attempts"][-1]["attempt"]
    event(session, {"observation": {"request_id": None, "child_attempt": attempt, "observation": {
        "sequence": POSITION[0], "source": {"session_id": session, "run_id": "r", "agent_id": handle,
        "parent_agent_id": session, "turn": 1, "model_attempt": 1, "tool_call_id": None}, "event": value}}})


def event(session, item):
    POSITION[0] += 1
    for view, owner in list(VIEWS.items()):
        if owner == session:
            write({"jsonrpc": "2.0", "method": "view.event", "params": {"view": view, "position": POSITION[0], "item": item}})


def observation(session, request, value):
    return {"observation": {"request_id": request, "child_attempt": None, "observation": {
        "sequence": POSITION[0], "source": {"session_id": session, "run_id": "r", "agent_id": session,
        "parent_agent_id": None, "turn": 1, "model_attempt": 1, "tool_call_id": None}, "event": value}}}


def user_entry(text):
    return {"id": str(uuid.uuid4()), "item": {"message": {"user": {"content": {"blocks": [
        {"display_text": text}, {"model_context": f"<issue_description>{text}</issue_description>"}]},
        "details": {"root": 0, "nodes": ["null"]}}}}}


def assistant_entry(text):
    return {"id": str(uuid.uuid4()), "item": {"message": {"assistant": {"content": {"blocks": [
        {"text": {"text": text, "provider_metadata": {"openai_responses": None}}}]},
        "model": "fake-model", "usage": None, "request_token_anchor": None, "stop_reason": "end_turn",
        "provider_data": {"root": 0, "nodes": ["null"]}}}}}


def start_item(session):
    s = STATE["sessions"][session]
    children = [{"child": child_info(session, handle)} for handle in s.get("children", {})]
    return {"state": view_state(session), "children": children,
            "root": {"cut": cut(session), "live_invocations": s.get("live_invocations", [])},
            "recovering": False}


PRESENTATION = {"title": "Read: note.txt", "running_title": "Reading", "display_kind": "file_read",
                "summary": "note.txt", "locations": [{"path": "note.txt", "line": None}], "result_state": "normal",
                "preview": "full", "subagent": None, "semantic_content": None, "quiet": False, "group": None}
NULL = {"root": 0, "nodes": ["null"]}
ARGS = {"root": 1, "nodes": [{"string_value": "note.txt"}, {"object": [{"key": "path", "value": 0}]}]}
# variant -> (public display list, saved model-facing content, live public output)
TOOL_VARIANTS = {
    "resource": ([{"resource_text": {"uri": "file:///r.txt", "mime_type": "text/plain", "text": "PUBLIC RESOURCE"}}],
                 "MODEL_ONLY", "LIVE PUBLIC"),
    "image": ([{"image": {"data": "aGk=", "media_type": "image/png", "url": None}}], "MODEL_ONLY", ""),
    "plain": ([{"text": "RESULT"}], "MODEL_ONLY", "RESULT"),
    "live": ([], "BOUNDED", "FULL LIVE OUTPUT " * 20000),
    "stream": ([], "BOUNDED", "streamed done"),
}


def stream_text():
    line = "line {:06d} ünïcödé ☃\n"
    text = "".join(line.format(i) for i in range(160000)).encode()
    return text[: SCENARIO.get("stream_deltas", 0) * 65536]


def tool_call_entry(call, inv):
    return {"id": str(uuid.uuid4()), "item": {"message": {"assistant": {"content": {"blocks": [
        {"tool_call": {"invocation": {"id": inv, "start": PRESENTATION}, "id": call, "name": "read_file",
                       "arguments": ARGS, "raw_arguments": None, "provider_data": NULL,
                       "provider_metadata": {"openai_responses": None}}}]},
        "model": "fake-model", "usage": None, "request_token_anchor": None, "stop_reason": "tool_use",
        "provider_data": NULL}}}}


def tool_result_entry(call, inv, display, content):
    return {"id": str(uuid.uuid4()), "item": {"message": {"tool_result": {
        "invocation_id": inv, "presentation": PRESENTATION, "display_content": display,
        "tool_call_id": call, "tool_name": "read_file", "content": {"text": content},
        "is_error": False, "details": NULL}}}}


def tool_started(call, inv):
    return {"tool_started": {
        "name": "read_file", "id": call, "invocation_id": inv, "input_json": json.dumps({"path": "note.txt"}),
        "presentation": PRESENTATION}}


def tool_stream(call):
    data = stream_text()
    output = [{"tool_output_delta": {"tool_call_id": call, "output_stream": "stdout", "bytes": list(data[i : i + 65536])}}
              for i in range(0, len(data), 65536)]
    progress = [{"tool_progress": {"id": call, "text": f"step {i + 1}"}}
                for i in range(SCENARIO.get("progress_lines", 0))]
    return output, progress


def tool_finished(call, inv, variant):
    display, content, live = TOOL_VARIANTS[variant]
    return {"tool_finished": {
        "name": "read_file", "id": call, "input_json": json.dumps({"path": "note.txt"}), "output": live,
        "model_output": content, "is_error": False, "details_json": "null", "output_profile": "generic",
        "presentation": PRESENTATION, "invocation_id": inv, "display_content": display, "duration_ms": 5}}


def finish_tool(session, request, call, inv, variant):
    s = STATE["sessions"][session]
    display, content, _ = TOOL_VARIANTS[variant]
    if variant == "stream":
        output, progress = tool_stream(call)
        for value in output + progress:
            event(session, observation(session, request, value))
    event(session, observation(session, request, tool_finished(call, inv, variant)))
    s["entries"].append(tool_result_entry(call, inv, display, content))
    s["live_invocations"] = []
    save()
    event(session, {"checkpoint": {"cut": cut(session), "live_invocations": []}})
    finish_turn(session, request, "done")


def tool_turn(session, request, text, variant):
    s = STATE["sessions"][session]
    begin_turn(session, request, text)
    n = len(s["entries"])
    call, inv = f"call_{n}", f"inv-{n}"
    event(session, observation(session, request, tool_started(call, inv)))
    s["entries"].append(tool_call_entry(call, inv))
    s["live_invocations"] = [inv]
    s["held_tool"] = {"request": request, "call": call, "inv": inv, "variant": variant}
    save()
    event(session, {"checkpoint": {"cut": cut(session), "live_invocations": [inv]}})
    if SCENARIO.get("hold_tool") and once("tool_held"):
        return
    s.pop("held_tool")
    finish_tool(session, request, call, inv, variant)


def child_tool_turn(session, request, text):
    s = STATE["sessions"][session]
    begin_turn(session, request, text)
    handle, call, inv = "agent-t", "call_c1", "inv-c1"
    child = {"attempts": [{"attempt": 1, "status": "running", "result": None}], "entries": [user_entry("stream for me")]}
    s.setdefault("children", {})[handle] = child
    save()
    child_changed(session, handle)
    child_checkpoint(session, handle, [])
    child_event(session, handle, tool_started(call, inv))
    child["entries"].append(tool_call_entry(call, inv))
    child_checkpoint(session, handle, [inv])
    s["held_child"] = {"request": request, "handle": handle, "call": call, "inv": inv}
    save()
    if SCENARIO.get("hold_tool") and once("tool_held"):
        return
    finish_child_tool(session)


def finish_child_tool(session):
    s = STATE["sessions"][session]
    held = s.pop("held_child")
    handle, call, inv = held["handle"], held["call"], held["inv"]
    output, progress = tool_stream(call)
    half = len(output) // 2
    for value in output[:half]:
        child_event(session, handle, value)
    # A lifecycle announcement mid-tool: its cut is the call already saved.
    child_changed(session, handle)
    for value in output[half:] + progress:
        child_event(session, handle, value)
    child_event(session, handle, tool_finished(call, inv, "stream"))
    child = s["children"][handle]
    child["entries"].append(tool_result_entry(call, inv, [], "BOUNDED"))
    child_checkpoint(session, handle, [])
    child["entries"].append(assistant_entry("child done"))
    child_checkpoint(session, handle, [])
    child_settled(session, handle, "completed", "child done")
    finish_turn(session, held["request"], "done")


def child_twice_turn(session, request, text):
    s = STATE["sessions"][session]
    begin_turn(session, request, text)
    handle = "agent-1"
    child = {"attempts": [], "entries": []}
    s.setdefault("children", {})[handle] = child
    for attempt, (brief, result) in enumerate([("probe", "first result"), ("continue", "second result")], 1):
        child["attempts"].append({"attempt": attempt, "status": "running", "result": None})
        save()
        child_changed(session, handle)
        child["entries"] += [user_entry(brief), assistant_entry(result)]
        child_checkpoint(session, handle, [])
        child_settled(session, handle, "completed", result)
    finish_turn(session, request, "done")


def ask_turn(session, request, text):
    s = STATE["sessions"][session]
    begin_turn(session, request, text)
    s["user_request"] = {"id": "q-1", "questions": [{"id": "color", "prompt": "Which color?", "allow_multiple": False,
                         "options": [{"label": "red", "description": "warm"}, {"label": "blue", "description": "cool"}]}]}
    save()
    event(session, {"state": view_state(session)})
    event(session, observation(session, request, {"user_request_ready": {
        "tool_call_id": "call_ask", "request": s["user_request"]}}))


def reset(session):
    save()
    event(session, {"reset": start_item(session)})


def set_status(session, request, status):
    s = STATE["sessions"][session]
    for r in s["requests"]:
        if r["id"] == request:
            r["status"] = status


def begin_turn(session, request, text):
    s = STATE["sessions"][session]
    s["active"] = request
    set_status(session, request, "running")
    event(session, {"state": view_state(session)})
    event(session, observation(session, request, {"input_accepted": {"text": text}}))
    s["entries"].append(user_entry(text))
    save()
    event(session, {"checkpoint": {"cut": cut(session), "live_invocations": []}})


def finish_turn(session, request, body, status="completed", stop_reason="end_turn"):
    s = STATE["sessions"][session]
    if body:
        event(session, observation(session, request, {"text_delta": {"index": 0, "value": body}}))
        s["entries"].append(assistant_entry(body))
    s["active"] = None
    set_status(session, request, status)
    save()
    event(session, {"checkpoint": {"cut": cut(session), "live_invocations": []}})
    event(session, {"state": view_state(session)})
    event(session, {"completed": {"request_id": request, "stop_reason": stop_reason, "error": None}})


def run_turn(session, request, text):
    if text.startswith("tool "):
        return tool_turn(session, request, text, text.split()[1])
    if text == "child tool":
        return child_tool_turn(session, request, text)
    if text == "child twice":
        return child_twice_turn(session, request, text)
    if text == "ask":
        return ask_turn(session, request, text)
    begin_turn(session, request, text)
    if SCENARIO.get("hold_turns"):
        return
    if SCENARIO.get("crash_after_save") and once("crashed"):
        event(session, observation(session, request, {"text_delta": {"index": 0, "value": "Hello"}}))
        STATE["sessions"][session]["entries"].append(assistant_entry("Hello"))
        save()
        os._exit(1)
    body = "x" * SCENARIO["reply_bytes"] if SCENARIO.get("reply_bytes") else f"echo: {text}"
    finish_turn(session, request, body)
    s = STATE["sessions"][session]
    if SCENARIO.get("reset_after_prompt") and not STATE["flags"].get("reset"):
        STATE["flags"]["reset"] = True
        s["entries"] = s["entries"][:-1] + [assistant_entry("rewritten on another branch")]
        s["branch"] = s.get("branch", 0) + 1
        save()
        event(session, {"reset": start_item(session)})


def once(flag):
    if STATE["flags"].get(flag):
        return False
    STATE["flags"][flag] = True
    save()
    return True


def entry_bytes(entry):
    return len(json.dumps(entry, separators=(",", ":")).encode())


def read_entries(session, params):
    entries = journal_entries(session, params["cut"]["journal"])
    # Reads are pinned to the cut: nothing saved after it is visible.
    leaf = (params.get("cut") or {}).get("leaf")
    ids = [e["id"] for e in entries]
    if leaf is None:
        entries = []
    elif leaf in ids:
        entries = entries[: ids.index(leaf) + 1]
    else:
        return None
    anchor = params.get("anchor", "latest")
    if isinstance(anchor, dict) and "after" in anchor:
        ids = [e["id"] for e in entries]
        if anchor["after"] not in ids:
            return None
        entries = entries[ids.index(anchor["after"]) + 1 :]
    budget = SCENARIO.get("page_bytes", params.get("max_bytes", 1 << 22))
    page = []
    for entry in entries:
        size = entry_bytes(entry)
        page.append({"oversized": {"id": entry["id"], "bytes": size}} if size > budget else {"entry": entry})
    return {"cut": params["cut"], "entries": page, "bytes": 0, "older": False, "newer": False}


def handle(message):
    id = message.get("id")
    method = message.get("method")
    params = message.get("params") or {}
    record(method, params)
    if method == "initialize":
        reply(id, {"protocol_version": 1, "bridge": {"id": "sh.roboco.bridge", "name": "Roboco bridge", "version": "0.1.0"},
                   "client": params.get("client"), "limits": {"max_frame_bytes": 8 << 20, "chunk_bytes": 1 << 20},
                   "host_contracts": SCENARIO.get("contracts", CONTRACTS), "capabilities": [], "methods": METHODS,
                   "features": {"fragments": "bridge.fragment"}, "ready": True})
    elif method == "catalog":
        reply(id, {"providers": [{"id": "fake", "name": "Fake", "models": [
            {"id": "fake-model", "name": "Fake model", "reasoning": "unsupported"}]}]})
    elif method == "session.create":
        session = f"s-{uuid.uuid4()}"
        STATE["sessions"][session] = {"cwd": params["cwd"], "requests": [], "entries": [], "active": None}
        save()
        reply(id, {"session": session, "state": view_state(session), "reused": False})
    elif method == "session.open":
        session = params["session"]
        if session not in STATE["sessions"]:
            return error(id, -32003, "not_found", "no such session")
        s = STATE["sessions"][session]
        if not s.get("held_tool") and not s.get("held_child"):
            for r in s["requests"]:
                if r["status"] in ("queued", "running"):
                    r["status"] = "interrupted"
            s["active"] = None
            s["live_invocations"] = []
        save()
        reply(id, {"session": session, "state": view_state(session), "reused": False})
    elif method == "session.close":
        if SCENARIO.get("close_fails"):
            return error(id, -32007, "failed", "the host could not release the session")
        reply(id, {"released": True})
    elif method == "session.state":
        reply(id, view_state(params["session"]))
    elif method == "session.open_view":
        view = f"v{len(VIEWS) + 1}"
        VIEWS[view] = params["session"]
        reply(id, {"view": view, "session": params["session"]})
        event(params["session"], {"start": start_item(params["session"])})
        s = STATE["sessions"][params["session"]]
        held = s.get("held_tool")
        if held and STATE["flags"].get("tool_held") and once("tool_resumed"):
            s.pop("held_tool")
            finish_tool(params["session"], held["request"], held["call"], held["inv"], held["variant"])
        if s.get("held_child") and STATE["flags"].get("tool_held") and once("tool_resumed"):
            finish_child_tool(params["session"])
    elif method == "view.close":
        VIEWS.pop(params["view"], None)
        reply(id, {"closed": True})
    elif method in ("session.commands", "session.skills"):
        reply(id, {"commands": []} if method == "session.commands" else {"skills": []})
    elif method == "session.attach_mcp":
        reply(id, {"attachment": "a1", "session": params["session"]})
    elif method == "session.read_entries":
        page = read_entries(params["session"], params)
        if page is None:
            return error(id, -32003, "not_found", "anchor is not on the branch")
        reply(id, page)
    elif method == "session.read_entry_chunk":
        journal = journal_entries(params["session"], params["cut"]["journal"])
        entry = next(e for e in journal if e["id"] == params["id"])
        data = json.dumps(entry, separators=(",", ":")).encode()
        offset = params.get("offset", 0)
        size = min(params.get("max_bytes", 1 << 20), 100_000)
        chunk = data[offset : offset + size]
        reply(id, {"id": entry["id"], "offset": offset, "total": len(data), "length": len(chunk),
                   "encoding": "base64", "data": base64.b64encode(chunk).decode()})
    elif method == "session.prompt":
        session = params["session"]
        s = STATE["sessions"][session]
        key = params.get("submission_key")
        existing = next((r for r in s["requests"] if key and r["submission_key"] == key), None)
        if existing:
            return reply(id, {"request_id": existing["id"]})
        if SCENARIO.get("stall_prompt") and once("stalled"):
            return
        if SCENARIO.get("refuse_prompt_once") and once("refused"):
            return error(id, -32004, "busy", "work is in the way")
        request = f"prompt-{uuid.uuid4()}"
        s["requests"].append({"id": request, "status": "queued", "submission_key": key})
        save()
        if SCENARIO.get("lose_prompt_reply") and once("lost"):
            os._exit(1)
        reply(id, {"request_id": request})
        run_turn(session, request, params["input"]["text"])
    elif method == "session.steer":
        session = params["session"]
        s = STATE["sessions"][session]
        if SCENARIO.get("lose_steer_reply") and once("lost_steer"):
            os._exit(1)
        if not s.get("active"):
            return error(id, -32004, "busy", "no request is running")
        event(session, observation(session, s["active"], {"input_accepted": {"text": params["text"]}}))
        s["entries"].append(user_entry(params["text"]))
        save()
        event(session, {"checkpoint": {"cut": cut(session), "live_invocations": []}})
        reply(id, {})
    elif method == "session.command":
        session = params["session"]
        if SCENARIO.get("lose_command_reply") and once("lost_command"):
            os._exit(1)
        if SCENARIO.get("refuse_command_while_running") and STATE["sessions"][session].get("active"):
            return error(id, -32004, "busy", "wait for the running request to finish")
        if params["name"] == "goal":
            return reply(id, {"result": "handled"})
        if params["name"] == "rewind":
            s = STATE["sessions"][session]
            s["saved_branch"] = list(s["entries"])
            s["entries"] = s["entries"][: int(params["tail"] or 0)]
            reply(id, {"result": "handled"})
            return reset(session)
        if params["name"] == "restore":
            s = STATE["sessions"][session]
            s["entries"] = s.pop("saved_branch")
            reply(id, {"result": "handled"})
            return reset(session)
        if params["name"] == "plan":
            request = f"plan-{uuid.uuid4()}"
            STATE["sessions"][session]["requests"].append({"id": request, "status": "queued", "submission_key": None})
            save()
            reply(id, {"result": {"submitted": request}})
            return run_turn(session, request, params["tail"])
        error(id, -32007, "failed", f"unknown command: {params['name']}")
    elif method == "session.children":
        handles = STATE["sessions"][params["session"]].get("children", {})
        reply(id, {"children": [child_info(params["session"], handle) for handle in handles]})
    elif method == "session.child_outcome":
        child = STATE["sessions"][params["session"]].get("children", {}).get(params["handle"])
        if child is None:
            return error(id, -32003, "not_found", f"this session has no child '{params['handle']}'")
        attempt = next((a for a in child["attempts"] if a["attempt"] == params["attempt"]), None)
        if attempt is None:
            return error(id, -32003, "not_found", f"'{params['handle']}' has no attempt {params['attempt']}")
        reply(id, {"outcome": child_outcome(params["handle"], attempt) if attempt["status"] in TERMINAL else None})
    elif method == "session.answer":
        session = params["session"]
        s = STATE["sessions"][session]
        pending = s.get("user_request")
        if not pending or pending["id"] != params["request_id"]:
            return error(id, -32003, "not_found", f"no pending request '{params['request_id']}'")
        s["user_request"] = None
        save()
        labels = [label for answer in params["answers"] for label in answer["selected_options"]]
        finish_turn(session, s["active"], "answered: " + ", ".join(labels))
        if SCENARIO.get("lose_answer_reply") and once("lost_answer"):
            os._exit(1)
        reply(id, {})
    elif method == "session.change_goal":
        if SCENARIO.get("lose_control_reply") and once("lost_control"):
            os._exit(1)
        reply(id, {"request_id": None})
    elif method == "session.cancel_request":
        session = params["session"]
        s = STATE["sessions"][session]
        if s.get("active") == params["request_id"]:
            reply(id, {"cancelled": True})
            return finish_turn(session, params["request_id"], None, "cancelled", "cancelled")
        reply(id, {"cancelled": False})
    elif method == "bridge.shutdown":
        reply(id, {"closed_sessions": 0})
    else:
        error(id, -32601, "method_not_found", f"{method} is not scripted")


def main():
    for line in sys.stdin:
        line = line.strip()
        if line:
            handle(json.loads(line))


if __name__ == "__main__":
    main()
