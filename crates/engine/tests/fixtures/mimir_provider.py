#!/usr/bin/env python3
"""Drive integrations/mimir/tests/fake_provider.py from a Rust test over stdio.

    mimir_provider.py <dir containing fake_provider.py> <MIMIR_CODING_AGENT_DIR>

Writes the agent directory's models.json for the loopback provider, prints one
ready line, then answers JSON commands, one per line:

    {"script": [...], "child_script": [...]}   replace the scripted replies
    {"open": "gate"}                           release a gated reply
    {"wait": "started", "timeout": 60}         wait until a gated reply began
    {"requests": true}                         how many model requests arrived

A reply is {"text": ["chunk", ...], "gate": name?, "started": name?} or
{"tool": name, "args": {...}, "id": "call_1"}.
"""

import json
import sys
import threading
from pathlib import Path

sys.path.insert(0, sys.argv[1])
from fake_provider import FakeProvider, Text, ToolCall  # noqa: E402

events = {}


def event(name):
    return events.setdefault(name, threading.Event())


def reply(spec):
    if "tool" in spec:
        return ToolCall(spec["tool"], spec.get("args", {}), spec.get("id", "call_1"))
    gate = event(spec["gate"]) if spec.get("gate") else None
    started = event(spec["started"]) if spec.get("started") else None
    return Text(spec["text"], gate=gate, started=started)


def main():
    provider = FakeProvider()
    provider.start()
    agent = Path(sys.argv[2])
    agent.mkdir(parents=True, exist_ok=True)
    (agent / "models.json").write_text(json.dumps(provider.models_json()))
    print(json.dumps({"ready": True, "base_url": provider.base_url}), flush=True)
    for line in sys.stdin:
        command = json.loads(line)
        answer = {"ok": True}
        if "script" in command or "child_script" in command:
            with provider.lock:
                provider.script[:] = [reply(s) for s in command.get("script", [])]
                provider.child_script[:] = [reply(s) for s in command.get("child_script", [])]
        elif "open" in command:
            event(command["open"]).set()
        elif "wait" in command:
            answer["ok"] = event(command["wait"]).wait(command.get("timeout", 60))
        elif "requests" in command:
            with provider.lock:
                answer["requests"] = len(provider.requests)
        print(json.dumps(answer), flush=True)
    for gate in events.values():
        gate.set()
    provider.stop()


if __name__ == "__main__":
    main()
