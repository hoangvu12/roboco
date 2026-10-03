#!/usr/bin/env python3
"""Real-process acceptance tests for the sh.roboco.bridge package.

    transport.py --mimir /path/to/mimir [--package out/sh.roboco.bridge-0.1.0] [--keep] [-v]

Installs the built package into a fresh isolated MIMIR_CODING_AGENT_DIR, points a
custom provider at a loopback fake, and drives `mimir plugin run sh.roboco.bridge`
over real pipes. No account, credential or network provider is used.
"""

import argparse
import base64
import json
import os
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from bridge_client import Bridge, RpcError, isolated_env  # noqa: E402
from fake_provider import FakeProvider, Text, ToolCall  # noqa: E402

FIXTURE = None

EXPECTED_METHODS = [
    "initialize", "bridge.status", "bridge.shutdown", "catalog", "session.list", "session.create",
    "session.open", "session.close", "session.state", "session.open_view", "view.close",
    "session.read_entries", "session.read_entry_chunk", "session.commands", "session.command",
    "session.skills", "session.plan", "session.children", "session.child_outcome",
    "session.steer_child", "session.stop_child", "session.prompt", "session.steer",
    "session.configure", "session.decide_plan", "session.change_goal", "session.answer",
    "session.cancel_request", "session.cancel_all", "session.attach_mcp", "session.detach_mcp",
]
EXPECTED_CONTRACTS = {
    "mimir:sessions/session-control": "8.0.0",
    "mimir:observations/session-observation": "7.0.0",
    "mimir:presentation/types": "3.0.0",
    "mimir:plugin-core/plugin-runtime": "3.0.0",
    "mimir:frontend/frontend": "1.0.0",
}
FAKE = {"provider": "fake", "model": "fake-model"}


class Fixture:
    def __init__(self, mimir, package, root, keep):
        self.mimir = Path(mimir)
        self.package = Path(package)
        self.root = Path(root)
        self.keep = keep
        self.env = isolated_env(self.root)
        self.provider = FakeProvider()
        self.counter = 0

    def cli(self, *args, timeout=900):
        done = subprocess.run(
            [str(self.mimir), *map(str, args)],
            env=self.env,
            cwd=self.root / "work",
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        return done

    def install(self):
        checked = self.cli("plugin", "check", self.package)
        assert "sh.roboco.bridge" in checked.stdout and checked.returncode == 0, checked.stdout + checked.stderr
        installed = self.cli("plugin", "install", self.package)
        assert installed.returncode == 0, installed.stdout + installed.stderr
        listed = self.cli("plugin", "list")
        assert "sh.roboco.bridge (Roboco bridge) 0.1.0 [compatible]" in listed.stdout, listed.stdout + listed.stderr
        return {"check": checked.stdout.strip(), "install": installed.stdout.strip(), "list": listed.stdout.strip()}

    def start_provider(self):
        self.provider.start()
        (self.root / "agent" / "models.json").write_text(json.dumps(self.provider.models_json()))

    def workdir(self, name):
        path = self.root / "work" / name
        path.mkdir(parents=True, exist_ok=True)
        return path.resolve()


def kind(value):
    return next(iter(value))


def view_item(note):
    return note["params"]["item"] if note.get("method") == "view.event" else None


def text_deltas(items):
    out = []
    for item in items:
        if kind(item) == "observation":
            event = item["observation"]["observation"]["event"]
            if kind(event) == "text_delta":
                out.append(event["text_delta"]["value"])
    return "".join(out)


def entry_texts(entry):
    texts = []

    def walk(node):
        if isinstance(node, dict):
            for key, value in node.items():
                if key in ("text", "display_text") and isinstance(value, str):
                    texts.append(value)
                else:
                    walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(entry)
    return texts


class BridgeCase(unittest.TestCase):
    def open(self, limits=None, name=None, initialize=True, read_stdout=True):
        cwd = FIXTURE.workdir(name or self.id().rsplit(".", 1)[-1])
        bridge = Bridge(FIXTURE.mimir, FIXTURE.env, cwd, read_stdout=read_stdout)
        self.addCleanup(self.reap, bridge)
        bridge.cwd = cwd
        if initialize:
            bridge.hello = bridge.initialize(limits)
        return bridge

    def reap(self, bridge):
        bridge.kill()
        if bridge.reader_error is not None:
            self.fail(f"bridge output was malformed: {bridge.reader_error!r}")

    def setUp(self):
        FIXTURE.provider.script.clear()
        FIXTURE.provider.requests.clear()

    def create(self, bridge, configuration=None):
        params = {"cwd": str(bridge.cwd)}
        if configuration:
            params["configuration"] = configuration
        return bridge.request("session.create", params)["session"]

    def drive(self, bridge, until, timeout=180):
        seen = []
        while True:
            note = bridge.notification(timeout=timeout)
            seen.append(note)
            if until(note):
                return seen

    def run_turn(self, bridge, session, text, timeout=180):
        request = bridge.request(
            "session.prompt", {"session": session, "input": {"text": text}, "delivery": "start"}
        )["request_id"]
        def done(note):
            item = view_item(note)
            return item is not None and kind(item) == "completed" and item["completed"]["request_id"] == request
        notes = self.drive(bridge, done, timeout)
        return request, [view_item(n) for n in notes if view_item(n) is not None]

    def last_cut(self, items):
        cuts = [item["checkpoint"]["cut"] for item in items if kind(item) == "checkpoint"]
        self.assertTrue(cuts, "no checkpoint was delivered")
        return cuts[-1]

    def assert_no_stray(self, bridge):
        self.assertTrue(bridge.unmatched.empty(), f"unexpected frame: {list(bridge.unmatched.queue)}")

    def read_chunks(self, bridge, session, cut, entry_id, expected_bytes, max_bytes=None):
        parts, offset, calls = [], 0, 0
        while True:
            params = {"session": session, "cut": cut, "id": entry_id, "offset": offset}
            if max_bytes:
                params["max_bytes"] = max_bytes
            chunk = bridge.request("session.read_entry_chunk", params)
            calls += 1
            self.assertEqual(chunk["encoding"], "base64")
            self.assertEqual(chunk["offset"], offset)
            self.assertEqual(chunk["total"], expected_bytes)
            data = base64.b64decode(chunk["data"])
            self.assertEqual(len(data), chunk["length"])
            self.assertGreater(len(data), 0)
            parts.append(data)
            offset += len(data)
            if offset >= chunk["total"]:
                return b"".join(parts), calls


class Handshake(BridgeCase):
    def test_negotiation_gates_and_contracts(self):
        b = self.open(initialize=False)
        early = b.request_frame("bridge.status")
        self.assertEqual(early["error"]["code"], -32013)
        wrong = b.request_frame("initialize", {"protocol_versions": [7]})
        self.assertEqual(wrong["error"]["code"], -32015)
        self.assertEqual(wrong["error"]["data"]["supported"], [1])
        tiny = b.request_frame("initialize", {"protocol_versions": [1], "limits": {"max_frame_bytes": 1000}})
        self.assertEqual(tiny["error"]["code"], -32602)
        hello = b.request(
            "initialize",
            {
                "protocol_versions": [0, 1],
                "client": {"name": "t", "version": "9"},
                "limits": {"max_frame_bytes": 65536, "chunk_bytes": 4096},
            },
        )
        self.assertEqual(hello["protocol_version"], 1)
        self.assertTrue(hello["ready"])
        self.assertEqual(hello["limits"], {"max_frame_bytes": 65536, "chunk_bytes": 4096})
        self.assertEqual(hello["methods"], EXPECTED_METHODS)
        self.assertEqual(hello["host_contracts"], EXPECTED_CONTRACTS)
        self.assertEqual(hello["bridge"]["id"], "sh.roboco.bridge")
        self.assertEqual(hello["features"]["fragments"], "bridge.fragment")
        again = b.request_frame("initialize", {"protocol_versions": [1]})
        self.assertEqual(again["error"]["code"], -32014)
        status = b.request("bridge.status")
        self.assertTrue(status["initialized"])
        self.assertEqual(status["limits"], hello["limits"])
        self.assertEqual(b.finish(), 0)

    def test_chunk_ceiling_follows_the_frame_budget(self):
        b = self.open(limits={"max_frame_bytes": 65536, "chunk_bytes": 1 << 20})
        self.assertLessEqual(b.hello["limits"]["chunk_bytes"], (65536 - 512) // 4 * 3)
        self.assertGreater(b.hello["limits"]["chunk_bytes"], 32768)


class WireErrors(BridgeCase):
    def test_typed_errors_and_ids(self):
        b = self.open()
        parse = b.raw_request(b"{not json", None)
        self.assertEqual(parse["error"]["code"], -32700)
        self.assertIsNone(parse["id"])
        batch = b.raw_request(b'[{"jsonrpc":"2.0","id":1,"method":"bridge.status"}]', None)
        self.assertEqual(batch["error"]["code"], -32600)
        scalar = b.raw_request(b"42", None)
        self.assertEqual(scalar["error"]["code"], -32600)
        no_method = b.raw_request(b'{"jsonrpc":"2.0","id":3}', 3)
        self.assertEqual(no_method["error"]["code"], -32600)
        version = b.raw_request(b'{"jsonrpc":"1.0","id":4,"method":"bridge.status"}', 4)
        self.assertEqual(version["error"]["code"], -32600)
        unknown = b.raw_request(b'{"jsonrpc":"2.0","id":5,"method":"nope.nothing"}', 5)
        self.assertEqual(unknown["error"]["code"], -32601)
        self.assertEqual(unknown["error"]["data"]["kind"], "method_not_found")
        array_params = b.raw_request(b'{"jsonrpc":"2.0","id":6,"method":"session.state","params":[1]}', 6)
        self.assertEqual(array_params["error"]["code"], -32602)
        extra = b.raw_request(b'{"jsonrpc":"2.0","id":7,"method":"session.state","params":{"session":"s","x":1}}', 7)
        self.assertEqual(extra["error"]["code"], -32602)
        missing = b.raw_request(b'{"jsonrpc":"2.0","id":8,"method":"session.state","params":{"session":"nope"}}', 8)
        self.assertEqual(missing["error"]["code"], -32010)
        self.assertEqual(missing["error"]["data"]["kind"], "not_attached")
        for literal in (b"true", b'{"a":1}', b"[1]", b'"' + b"x" * 255 + b'"'):
            bad = b.raw_request(b'{"jsonrpc":"2.0","id":' + literal + b',"method":"bridge.status"}', None)
            self.assertEqual(bad["error"]["code"], -32600, literal[:20])
            self.assertIsNone(bad["id"])
        null_id = b.raw_request(b'{"jsonrpc":"2.0","id":null,"method":"bridge.status"}', None)
        self.assertEqual(null_id["error"]["code"], -32600)
        b.send_line(b'{"jsonrpc":"2.0","id":5,"result":{}}\n')
        b.send_line(b'{"jsonrpc":"2.0","method":"bridge.status"}\n')
        self.assertTrue(b.request("bridge.status")["initialized"])
        self.assert_no_stray(b)

    def test_ids_are_echoed_verbatim(self):
        b = self.open()
        long_id = '"' + "i" * 254 + '"'
        literals = [
            "0", "-7", "1.5", "1e2", "1.0", "12345678901234567890123", '""', '"uni \\u00e9 \U0001f980"',
            '"quote \\" and \\n newline and \\\\ slash"', long_id,
        ]
        for literal in literals:
            line = ('{"jsonrpc":"2.0","id":%s,"method":"bridge.status"}' % literal).encode()
            frame = b.raw_request(line, json.loads(literal))
            self.assertIn("result", frame, literal)
            echoed = b.sampled(b'"id":' + literal.encode() + b',"result"') if len(literal) < 300 else b.sampled(b'"id":' + literal.encode())
            self.assertEqual(len(echoed), 1, f"id {literal} was not echoed byte for byte")


class Framing(BridgeCase):
    def test_fragmented_crlf_and_blank_lines(self):
        b = self.open()
        line = b'{"jsonrpc":"2.0","id":"bb","method":"bridge.status"}\n'
        slot = b.expect("bb")
        for index in range(len(line)):
            b.send_line(line[index : index + 1])
        self.assertIn("result", b.settle(slot, 30))
        one, two = b.expect("one"), b.expect("two")
        b.send_line(
            b'\n  \r\n{"jsonrpc":"2.0","id":"one","method":"bridge.status"}\r\n\r\n'
            b'{"jsonrpc":"2.0","id":"two","method":"bridge.status"}\n'
        )
        self.assertIn("result", b.settle(one, 30))
        self.assertIn("result", b.settle(two, 30))
        text = '{"jsonrpc":"2.0","id":"é🦀","method":"bridge.status"}\n'.encode()
        cut = text.index("🦀".encode()) + 2
        slot = b.expect("é🦀")
        b.send_line(text[:cut])
        b.send_line(text[cut:])
        self.assertIn("result", b.settle(slot, 30))

    def test_oversized_line_is_reported_and_the_stream_recovers(self):
        b = self.open(limits={"max_frame_bytes": 65536})
        junk = b"x" * (65536 + 100)
        slot = b.expect(None)
        for start in range(0, len(junk), 7000):
            b.send_line(junk[start : start + 7000])
        b.send_line(b"\n")
        frame = b.settle(slot, 30)
        self.assertEqual(frame["error"]["code"], -32012)
        self.assertEqual(frame["error"]["data"]["kind"], "frame_too_large")
        self.assertTrue(b.request("bridge.status")["initialized"])
        exact = b'{"jsonrpc":"2.0","id":"fit","method":"bridge.status","params":{"pad":"'
        exact += b"p" * (65536 - len(exact) - 3) + b'"}}'
        self.assertEqual(len(exact), 65536)
        reply = b.raw_request(exact, "fit")
        self.assertIn("error", reply)
        self.assertNotEqual(reply["error"]["code"], -32012)

    def test_unterminated_final_frame_is_served_at_eof(self):
        b = self.open()
        slot = b.expect("tail")
        b.send_line(b'{"jsonrpc":"2.0","id":"tail","method":"bridge.status"}')
        b.process.stdin.close()
        self.assertIn("result", b.settle(slot, 30))
        self.assertEqual(b.process.wait(timeout=60), 0)

    def test_newlines_inside_values_never_split_a_frame(self):
        b = self.open()
        session = self.create(b, FAKE)
        b.request("session.open_view", {"session": session})
        noisy = "line one\nline two\r\nthree \u2028 four \x00 nul \U0001f980"
        FIXTURE.provider.script[:] = [Text(noisy)]
        _, items = self.run_turn(b, session, noisy)
        self.assertEqual(text_deltas(items), noisy)
        sent = [
            m["content"]
            for request in FIXTURE.provider.requests
            for m in request.get("messages", [])
            if m.get("role") == "user" and isinstance(m.get("content"), str)
        ]
        self.assertTrue(any(noisy in content for content in sent), "the provider did not receive the exact text")
        self.assertTrue(b.sampled(b"\\n"), "newlines should travel escaped")

    def test_responses_stay_inside_the_frame_budget_at_the_minimum(self):
        b = self.open(limits={"max_frame_bytes": 65536})
        session = self.create(b)
        for _ in range(5):
            b.request("session.state", {"session": session})
        b.request("session.commands", {"session": session})
        self.assertLessEqual(max(b.lines), 65536)


class Sessions(BridgeCase):
    def test_model_less_create_catalog_commands_view_close(self):
        b = self.open()
        self.assertEqual(b.request("catalog", {"cwd": str(b.cwd)})["providers"][0]["id"], "fake")
        created = b.request("session.create", {"cwd": str(b.cwd)})
        session = created["session"]
        self.assertFalse(created["reused"])
        self.assertEqual(created["state"]["info"]["id"], session)
        self.assertEqual(created["state"]["info"]["cwd"], str(b.cwd))
        self.assertIn("model", created["state"]["configuration"])
        again = b.request("session.open", {"session": session})
        self.assertTrue(again["reused"])
        listed = b.request("session.list", {"cwd": str(b.cwd)})
        self.assertIn(session, [item["id"] for item in listed["sessions"]])
        names = [c["name"] for c in b.request("session.commands", {"session": session})["commands"]]
        self.assertIn("plan", names)
        self.assertIn("plugins", names)
        self.assertEqual(b.request("session.skills", {"session": session}), {"skills": []})
        self.assertEqual(b.request("session.plan", {"session": session}), {"plan": None})
        self.assertEqual(b.request("session.children", {"session": session}), {"children": []})
        view = b.request("session.open_view", {"session": session})
        self.assertEqual(view["session"], session)
        note = b.notification(timeout=30)
        self.assertEqual(note["method"], "view.event")
        self.assertEqual(note["params"]["view"], view["view"])
        start = note["params"]["item"]["start"]
        self.assertEqual(start["state"]["info"]["id"], session)
        self.assertIn("leaf", start["root"]["cut"])
        page = b.request("session.read_entries", {"session": session, "cut": start["root"]["cut"]})
        self.assertEqual(page["cut"], start["root"]["cut"])
        status = b.request("bridge.status")
        self.assertEqual([s["session"] for s in status["sessions"]], [session])
        self.assertEqual(status["views"][0]["view"], view["view"])
        self.assertEqual(b.request("view.close", {"view": view["view"]}), {"closed": True})
        gone = b.request_frame("view.close", {"view": view["view"]})
        self.assertEqual(gone["error"]["code"], -32017)
        self.assertEqual(b.request("session.close", {"session": session}), {"released": True})
        self.assertEqual(b.request("session.close", {"session": session}), {"released": False})
        after = b.request_frame("session.state", {"session": session})
        self.assertEqual(after["error"]["code"], -32010)
        self.assertEqual(b.request("bridge.status")["sessions"], [])
        self.assertEqual(b.finish(), 0)

    def test_provider_free_commands_and_typed_command_errors(self):
        b = self.open()
        session = self.create(b)
        usage = b.request_frame("session.command", {"session": session, "name": "plugins"})
        self.assertEqual(usage["error"]["code"], -32007)
        self.assertIn("Usage", usage["error"]["message"])
        failed = b.request_frame("session.command", {"session": session, "name": "goal", "tail": "pause"})
        self.assertEqual(failed["error"]["code"], -32007)
        self.assertEqual(failed["error"]["data"]["kind"], "failed")
        self.assertEqual(FIXTURE.provider.requests, [])

    def test_unknown_methods_and_commands_never_become_prompts(self):
        b = self.open()
        session = self.create(b, FAKE)
        before = b.request("session.read_entries", {"session": session, "cut": self.cut_of(b, session)})
        for method, params in [
            ("session.command", {"session": session, "name": "not-a-command", "tail": "please act"}),
            ("session.command", {"session": session, "name": "", "tail": "just text"}),
            ("session.send", {"session": session, "text": "hi"}),
            ("session.prompt_text", {"session": session, "text": "hi"}),
            ("session.notify", {"session": session}),
            ("session.context_snapshot", {"session": session}),
            ("prompt", {"session": session}),
        ]:
            frame = b.request_frame(method, params)
            self.assertIn("error", frame, method)
        self.assertEqual(FIXTURE.provider.requests, [])
        after = b.request("session.read_entries", {"session": session, "cut": self.cut_of(b, session)})
        self.assertEqual(after["entries"], before["entries"])
        for withheld in ("session.notify", "session.context_snapshot"):
            frame = b.request_frame(withheld, {"session": session})
            self.assertEqual(frame["error"]["code"], -32601)
            self.assertIn("model-only", frame["error"]["data"]["reason"])

    def cut_of(self, bridge, session):
        view = bridge.request("session.open_view", {"session": session})
        note = bridge.notification(timeout=30)
        bridge.request("view.close", {"view": view["view"]})
        return note["params"]["item"]["start"]["root"]["cut"]


class Conversations(BridgeCase):
    def test_prompt_stream_history_and_state_with_a_fake_provider(self):
        b = self.open()
        session = self.create(b, FAKE)
        b.request("session.open_view", {"session": session})
        FIXTURE.provider.script[:] = [Text(["alpha ", "beta ", "gamma"])]
        request, items = self.run_turn(b, session, "say something")
        kinds = [kind(item) for item in items]
        self.assertEqual(kinds[0], "start")
        self.assertEqual(kinds[-1], "completed")
        self.assertEqual(items[-1]["completed"]["stop_reason"], "end_turn")
        self.assertIsNone(items[-1]["completed"]["error"])
        self.assertEqual(text_deltas(items), "alpha beta gamma")
        page = b.request("session.read_entries", {"session": session, "cut": self.last_cut(items)})
        flat = " ".join(t for entry in page["entries"] for t in entry_texts(entry))
        self.assertIn("alpha beta gamma", flat)
        self.assertIn("say something", flat)
        state = b.request("session.state", {"session": session})
        self.assertIsNone(state["active_request"])
        self.assertEqual([r["id"] for r in state["requests"]], [request])
        self.assertEqual(state["requests"][0]["status"], "completed")
        self.assertEqual(b.request("session.close", {"session": session}), {"released": True})

    def test_tool_call_streams_and_completes(self):
        b = self.open()
        (b.cwd / "note.txt").write_text("native tool result")
        session = self.create(b, FAKE)
        b.request("session.open_view", {"session": session})
        FIXTURE.provider.script[:] = [ToolCall("read_file", {"path": "note.txt"}), Text("tool done")]
        _, items = self.run_turn(b, session, "read the note")
        events = [
            kind(item["observation"]["observation"]["event"])
            for item in items
            if kind(item) == "observation"
        ]
        self.assertIn("tool_started", events)
        self.assertIn("tool_finished", events)
        self.assertEqual(text_deltas(items), "tool done")
        self.assertLess(events.index("tool_started"), events.index("tool_finished"))
        finished = next(
            item["observation"]["observation"]["event"]["tool_finished"]
            for item in items
            if kind(item) == "observation" and kind(item["observation"]["observation"]["event"]) == "tool_finished"
        )
        self.assertEqual(finished["name"], "read_file")
        self.assertFalse(finished["is_error"])
        self.assertIn("native tool result", finished["output"])

    def test_views_are_independent_and_tagged(self):
        b = self.open()
        session = self.create(b, FAKE)
        first = b.request("session.open_view", {"session": session})["view"]
        second = b.request("session.open_view", {"session": session, "detail": "lifecycle"})["view"]
        self.assertNotEqual(first, second)
        FIXTURE.provider.script[:] = [Text(["one ", "two"])]
        request = b.request("session.prompt", {"session": session, "input": {"text": "go"}, "delivery": "start"})["request_id"]
        per_view = {first: [], second: []}
        pending = {first, second}
        while pending:
            note = b.notification(timeout=180)
            item = view_item(note)
            per_view[note["params"]["view"]].append(item)
            if kind(item) == "completed" and item["completed"]["request_id"] == request:
                pending.discard(note["params"]["view"])
        self.assertEqual(text_deltas(per_view[first]), "one two")
        self.assertEqual(text_deltas(per_view[second]), "")
        b.request("view.close", {"view": first})
        self.assertEqual([v["view"] for v in b.request("bridge.status")["views"]], [second])

    def test_activity_does_not_block_control_requests(self):
        b = self.open()
        session = self.create(b, FAKE)
        b.request("session.open_view", {"session": session})
        gate, started = threading.Event(), threading.Event()
        FIXTURE.provider.script[:] = [Text(["first ", "second"], gate=gate, started=started)]
        request = b.request("session.prompt", {"session": session, "input": {"text": "slow"}, "delivery": "start"})["request_id"]
        self.assertTrue(started.wait(120))
        slots = []
        for number in range(20):
            slots.append(b.submit("bridge.status"))
            slots.append(b.submit("session.state", {"session": session}))
            slots.append(b.submit("session.commands", {"session": session}))
        for slot in slots:
            self.assertIn("result", b.settle(slot, 60))
        state = b.request("session.state", {"session": session})
        self.assertEqual(state["active_request"], request)
        self.assertFalse(gate.is_set())
        b.request("session.cancel_all", {"session": session})
        def finished(note):
            item = view_item(note)
            return item is not None and kind(item) == "completed"
        notes = self.drive(b, finished)
        completed = view_item(notes[-1])["completed"]
        self.assertEqual(completed["request_id"], request)
        gate.set()
        after = b.request("session.state", {"session": session})
        self.assertIsNone(after["active_request"])


class Multiplexing(BridgeCase):
    def test_concurrent_sessions_on_one_bridge(self):
        b = self.open()
        creates = [b.submit("session.create", {"cwd": str(FIXTURE.workdir(f"mux{n}")), "configuration": FAKE}, id=f"c{n}") for n in range(4)]
        sessions = []
        for slot in creates:
            frame = b.settle(slot, 120)
            sessions.append(frame["result"]["session"])
        self.assertEqual(len(set(sessions)), 4)
        views = {}
        for session in sessions:
            views[b.request("session.open_view", {"session": session})["view"]] = session
        FIXTURE.provider.script[:] = [Text(f"reply {n}") for n in range(4)]
        prompts = {
            session: b.submit("session.prompt", {"session": session, "input": {"text": f"hello {n}"}, "delivery": "start"}, id=f"p{n}")
            for n, session in enumerate(sessions)
        }
        requests = {session: b.settle(slot, 120)["result"]["request_id"] for session, slot in prompts.items()}
        done, deltas = set(), {session: [] for session in sessions}
        while len(done) < len(sessions):
            note = b.notification(timeout=180)
            item = view_item(note)
            session = views[note["params"]["view"]]
            deltas[session].append(item)
            if kind(item) == "completed" and item["completed"]["request_id"] == requests[session]:
                done.add(session)
        replies = sorted(text_deltas(items) for items in deltas.values())
        self.assertEqual(replies, [f"reply {n}" for n in range(4)])
        for session in sessions:
            start = next(i for i in deltas[session] if kind(i) == "start")
            self.assertEqual(start["start"]["state"]["info"]["id"], session)
        closes = [b.submit("session.close", {"session": s}) for s in sessions]
        for slot in closes:
            self.assertEqual(b.settle(slot, 120)["result"], {"released": True})
        self.assertEqual(b.request("bridge.status")["sessions"], [])

    def test_a_flood_gets_exactly_one_reply_each(self):
        b = self.open()
        total = 700
        payload = b"".join(
            b'{"jsonrpc":"2.0","id":%d,"method":"session.list","params":{"cwd":"%s"}}\n' % (n, str(b.cwd).encode())
            for n in range(total)
        )
        slots = [b.expect(n) for n in range(total)]
        b.send_line(payload)
        codes = {}
        for slot in slots:
            frame = b.settle(slot, 120)
            key = frame["error"]["code"] if "error" in frame else "ok"
            codes[key] = codes.get(key, 0) + 1
        self.assertEqual(sum(codes.values()), total)
        self.assertTrue(set(codes) <= {"ok", -32011}, codes)
        self.assertTrue(b.request("bridge.status")["initialized"])
        self.assert_no_stray(b)
        print(f"\nflood of {total}: {codes}")


class LargePayloads(BridgeCase):
    UNIT = 'q"uote \\ back é\U0001f980 tab\t nl\n ctl\x01 end. '

    def big_text(self, size):
        reps = size // len(self.UNIT.encode()) + 1
        return (self.UNIT * reps).encode()[:size].decode(errors="ignore")

    def test_ten_mebibyte_reply_streams_and_rereads_exactly(self):
        b = self.open(limits={"max_frame_bytes": 65536, "chunk_bytes": 16384})
        session = self.create(b, FAKE)
        b.request("session.open_view", {"session": session})
        text = self.big_text(10 << 20)
        pieces = [text[i : i + 100_000] for i in range(0, len(text), 100_000)]
        FIXTURE.provider.script[:] = [Text(pieces)]
        _, items = self.run_turn(b, session, "write a lot", timeout=600)
        self.assertEqual(text_deltas(items), text)
        self.assertGreater(b.fragment_count, 0, "the event stream never needed fragments")
        self.assertLessEqual(max(b.lines), 65536)
        cut = self.last_cut(items)
        page = b.request("session.read_entries", {"session": session, "cut": cut, "max_bytes": 4 << 20})
        oversized = [e["oversized"] for e in page["entries"] if "oversized" in e]
        self.assertEqual(len(oversized), 1, "the 10 MiB entry should arrive as a header")
        header = oversized[0]
        self.assertGreater(header["bytes"], len(text.encode()))
        raw, calls = self.read_chunks(b, session, cut, header["id"], header["bytes"])
        self.assertEqual(len(raw), header["bytes"])
        self.assertGreater(calls, 10)
        entry = json.loads(raw)
        self.assertEqual(entry["id"], header["id"])
        self.assertIn(text, entry_texts(entry))
        bigger, fewer = self.read_chunks(b, session, cut, header["id"], header["bytes"], max_bytes=4 << 20)
        self.assertEqual(bigger, raw)
        self.assertLessEqual(max(b.lines), 65536)

    def test_ten_mebibyte_chunk_reads_at_the_default_budget(self):
        b = self.open()
        session = self.create(b, FAKE)
        b.request("session.open_view", {"session": session})
        text = self.big_text(10 << 20)
        FIXTURE.provider.script[:] = [Text([text[i : i + 262_144] for i in range(0, len(text), 262_144)])]
        _, items = self.run_turn(b, session, "write a lot", timeout=600)
        cut = self.last_cut(items)
        page = b.request("session.read_entries", {"session": session, "cut": cut, "max_bytes": 4 << 20})
        header = next(e["oversized"] for e in page["entries"] if "oversized" in e)
        raw, calls = self.read_chunks(b, session, cut, header["id"], header["bytes"])
        self.assertIn(text, entry_texts(json.loads(raw)))
        self.assertLessEqual(max(b.lines), 1 << 20)

    def test_paging_walks_every_entry_under_a_small_budget(self):
        b = self.open(limits={"max_frame_bytes": 65536})
        session = self.create(b, FAKE)
        b.request("session.open_view", {"session": session})
        replies = [self.big_text(30_000 + n) for n in range(6)]
        for n, reply in enumerate(replies):
            FIXTURE.provider.script[:] = [Text(reply)]
            _, items = self.run_turn(b, session, f"turn {n}")
        cut = self.last_cut(items)
        def walk(max_entries, max_bytes):
            walked, entries, anchor, pages = [], [], None, 0
            while True:
                params = {"session": session, "cut": cut, "max_entries": max_entries, "max_bytes": max_bytes}
                if anchor:
                    params["anchor"] = {"before": anchor}
                page = b.request("session.read_entries", params)
                pages += 1
                ids = [(e["entry"] if "entry" in e else e["oversized"])["id"] for e in page["entries"]]
                self.assertTrue(0 < len(ids) <= max_entries)
                walked = ids + walked
                entries = page["entries"] + entries
                if not page["older"]:
                    return walked, entries, pages
                anchor = ids[0]

        full_ids, everything, singles = walk(1, 4 << 20)
        self.assertGreaterEqual(len(full_ids), 12)
        self.assertEqual(len(set(full_ids)), len(full_ids))
        walked, _, pages = walk(3, 4 << 20)
        self.assertGreaterEqual(pages, 4)
        self.assertLess(pages, singles)
        self.assertEqual(walked, full_ids)
        small_ids, small_entries, _ = walk(4096, 20_000)
        self.assertEqual(small_ids, full_ids)
        headers = [e["oversized"] for e in small_entries if "oversized" in e]
        self.assertGreaterEqual(len(headers), len(replies))
        for header in headers[:2]:
            raw, _ = self.read_chunks(b, session, cut, header["id"], header["bytes"])
            self.assertEqual(json.loads(raw)["id"], header["id"])
        texts = " ".join(t for e in everything if "entry" in e for t in entry_texts(e))
        for reply in replies:
            self.assertIn(reply, texts)
        self.assertLessEqual(max(b.lines), 65536)


class Processes(BridgeCase):
    def test_conflicting_process_gets_busy_then_handoff(self):
        a = self.open(name="shared")
        session = self.create(a)
        c = self.open(name="shared")
        busy = c.request_frame("session.open", {"session": session})
        self.assertEqual(busy["error"]["code"], -32004)
        self.assertEqual(busy["error"]["data"]["kind"], "busy")
        self.assertEqual(a.request("session.close", {"session": session}), {"released": True})
        opened = c.request("session.open", {"session": session})
        self.assertFalse(opened["reused"])
        self.assertEqual(opened["state"]["info"]["id"], session)
        again = a.request_frame("session.open", {"session": session})
        self.assertEqual(again["error"]["code"], -32004)
        self.assertEqual(c.request("session.close", {"session": session}), {"released": True})
        self.assertEqual(a.finish(), 0)
        self.assertEqual(c.finish(), 0)

    def test_stdin_eof_awaits_release_before_the_process_ends(self):
        a = self.open(name="eof")
        session = self.create(a)
        view = a.request("session.open_view", {"session": session})
        self.assertEqual(a.finish(), 0)
        self.assertEqual(a.stderr_text.count("panicked"), 0)
        c = self.open(name="eof")
        self.assertEqual(c.request("session.open", {"session": session})["state"]["info"]["id"], session)
        self.assertEqual(c.finish(), 0)

    def test_shutdown_replies_after_releasing_and_refuses_new_work(self):
        a = self.open(name="shutdown")
        s1, s2 = self.create(a), self.create(a)
        reply = a.request("bridge.shutdown")
        self.assertEqual(reply, {"closed_sessions": 2})
        self.assertEqual(a.finish(), 0)
        c = self.open(name="shutdown")
        for session in (s1, s2):
            self.assertEqual(c.request("session.open", {"session": session})["reused"], False)

    def test_requests_after_shutdown_are_refused(self):
        a = self.open(name="late")
        slot = a.submit("bridge.shutdown")
        late = a.submit("session.list", {"cwd": str(a.cwd)})
        first, second = a.settle(slot, 60), a.settle(late, 60)
        self.assertEqual(first["result"], {"closed_sessions": 0})
        self.assertEqual(second["error"]["code"], -32016)

    def test_closed_output_ends_the_run_after_releasing_sessions(self):
        a = self.open(name="outclosed", read_stdout=False, initialize=False)
        a.send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocol_versions": [1]}})
        ready = a.process.stdout.readline()
        self.assertIn(b'"ready":true', ready)
        a.send({"jsonrpc": "2.0", "id": 2, "method": "session.create", "params": {"cwd": str(a.cwd)}})
        created = json.loads(a.process.stdout.readline())
        session = created["result"]["session"]
        a.process.stdout.close()
        a.send({"jsonrpc": "2.0", "id": 3, "method": "bridge.status"})
        self.assertIsNotNone(a.process.wait(timeout=120))
        c = self.open(name="outclosed")
        self.assertEqual(c.request("session.open", {"session": session})["state"]["info"]["id"], session)

    def test_killed_bridge_leaves_a_recoverable_session(self):
        a = self.open(name="killed")
        session = self.create(a)
        a.process.kill()
        a.process.wait()
        c = self.open(name="killed")
        opened = c.request("session.open", {"session": session})
        self.assertEqual(opened["state"]["info"]["id"], session)


CHILD_BRIEF = "probe child work"


def launch_call(call_id="call_1", description="Probe child"):
    return ToolCall(
        "agent",
        {
            "operation": {"kind": "launch", "agent": "general-purpose"},
            "brief": {"objective": CHILD_BRIEF},
            "description": description,
        },
        call_id,
    )


class Delegation(BridgeCase):
    def children(self, bridge, session):
        return bridge.request("session.children", {"session": session})["children"]

    def await_child(self, bridge, session, wanted, timeout=120, handle=None):
        deadline = time.monotonic() + timeout
        last = None
        while time.monotonic() < deadline:
            for child in self.children(bridge, session):
                if handle is None or child["handle"] == handle:
                    last = child
                    if wanted(child):
                        return child
            time.sleep(0.1)
        self.fail(f"child never reached the wanted state, last seen {last}")

    def await_state(self, bridge, session, wanted, timeout=120):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            state = bridge.request("session.state", {"session": session})
            if wanted(state):
                return state
            time.sleep(0.1)
        self.fail("session state never reached the wanted shape")

    def drain_view(self, bridge):
        items = []
        while True:
            try:
                items.append(view_item(bridge.notifications.get_nowait()))
            except queue.Empty:
                return [item for item in items if item is not None]

    def child_items(self, items):
        return [item["child"]["child"] for item in items if kind(item) == "child" and "child" in item["child"]]

    def start_delegation(self, bridge, session, parent_tail, child_script):
        FIXTURE.provider.script[:] = [launch_call(), *parent_tail]
        FIXTURE.provider.child_script[:] = child_script
        return bridge.request(
            "session.prompt", {"session": session, "input": {"text": "delegate"}, "delivery": "start"}
        )["request_id"]

    def test_native_launch_discovery_history_and_pure_outcome_reads(self):
        b = self.open()
        session = self.create(b, FAKE)
        b.request("session.open_view", {"session": session})
        parent_gate, parent_started = threading.Event(), threading.Event()
        child_gate, child_started = threading.Event(), threading.Event()
        request = self.start_delegation(
            b,
            session,
            [Text("parent long", gate=parent_gate, started=parent_started)],
            [Text(["first ", "result"], gate=child_gate, started=child_started)],
        )
        self.assertTrue(child_started.wait(120))
        child = self.await_child(b, session, lambda c: c["status"] == "running")
        handle = child["handle"]
        self.assertRegex(handle, r"^agent-[0-9a-f]+$")
        self.assertEqual(child["attempt"], 1)
        self.assertEqual(child["profile"], "general-purpose")
        self.assertEqual(child["description"], "Probe child")
        self.assertEqual(child["model"], "fake/fake-model")
        self.assertIs(child["background"], True)
        self.assertIs(child["completion_pending"], False)
        self.assertEqual(child["cut"]["journal"], {"child": handle})
        self.assertIsInstance(child["cut"]["leaf"], str)
        self.assertEqual(b.request("session.child_outcome", {"session": session, "handle": handle, "attempt": 1}), {"outcome": None})

        late = b.request("session.open_view", {"session": session})["view"]
        start, replayed, earlier = None, [], []
        while not replayed:
            note = b.notification(timeout=30)
            item = view_item(note)
            if note["params"]["view"] != late:
                earlier.append(item)
                continue
            if kind(item) == "start":
                start = item["start"]
            elif start is not None and kind(item) == "child":
                replayed.append(item["child"]["child"])
        known = {entry["child"]["handle"]: entry["child"] for entry in start["children"]}
        known.update({c["handle"]: c for c in replayed})
        self.assertEqual(list(known), [handle])
        self.assertEqual((known[handle]["attempt"], known[handle]["status"]), (1, "running"))
        self.assertEqual(known[handle]["cut"]["journal"], {"child": handle})
        b.request("view.close", {"view": late})

        child_gate.set()
        done = self.await_child(b, session, lambda c: c["status"] == "completed")
        self.assertIs(done["completion_pending"], True)
        self.assertTrue(parent_started.is_set() and not parent_gate.is_set())
        self.assertEqual(b.request("session.state", {"session": session})["active_request"], request)
        first = b.request("session.child_outcome", {"session": session, "handle": handle, "attempt": 1})
        for _ in range(3):
            self.assertEqual(b.request("session.child_outcome", {"session": session, "handle": handle, "attempt": 1}), first)
        outcome = first["outcome"]
        self.assertEqual((outcome["handle"], outcome["attempt"], outcome["status"]), (handle, 1, "completed"))
        self.assertEqual(outcome["result"], "first result")
        self.assertIsNone(outcome["error"])
        self.assertEqual(outcome["usage"]["total_tokens"], 18)
        still = self.await_child(b, session, lambda c: True, handle=handle)
        self.assertIs(still["completion_pending"], True)

        page = b.request("session.read_entries", {"session": session, "cut": still["cut"]})
        flat = " ".join(t for entry in page["entries"] for t in entry_texts(entry))
        self.assertIn(CHILD_BRIEF, flat)
        self.assertIn("first result", flat)

        quiet = earlier + self.drain_view(b)
        self.assertFalse([i for i in quiet if kind(i) == "completed"], "the root settled while it still owed work")
        seen = self.child_items(quiet)
        self.assertTrue(seen)
        self.assertEqual({c["handle"] for c in seen}, {handle})
        self.assertIn("running", {c["status"] for c in seen})
        self.assertIn(("completed", True), {(c["status"], c["completion_pending"]) for c in seen})

        parent_gate.set()
        delivered = self.await_child(b, session, lambda c: not c["completion_pending"], handle=handle)
        self.assertEqual((delivered["handle"], delivered["attempt"], delivered["status"]), (handle, 1, "completed"))
        self.await_state(b, session, lambda s: s["active_request"] is None)
        self.assertEqual(b.request("session.child_outcome", {"session": session, "handle": handle, "attempt": 1}), first)
        self.assertEqual(len(self.children(b, session)), 1)
        settled = b.request("session.open_view", {"session": session})["view"]
        while True:
            note = b.notification(timeout=30)
            if note["params"]["view"] == settled and kind(view_item(note)) == "start":
                break
        listed = [entry["child"] for entry in view_item(note)["start"]["children"]]
        self.assertEqual([(c["handle"], c["attempt"], c["status"]) for c in listed], [(handle, 1, "completed")])
        self.assertEqual(listed[0]["cut"]["journal"], {"child": handle})
        again = b.request("session.read_entries", {"session": session, "cut": listed[0]["cut"]})
        self.assertIn("first result", " ".join(t for entry in again["entries"] for t in entry_texts(entry)))
        self.assert_no_stray(b)

    def test_exact_attempt_steer_and_stop(self):
        b = self.open()
        session = self.create(b, FAKE)
        b.request("session.open_view", {"session": session})
        parent_gate, child_gate, child_started = threading.Event(), threading.Event(), threading.Event()
        self.addCleanup(parent_gate.set)
        self.addCleanup(child_gate.set)
        self.start_delegation(
            b,
            session,
            [Text("parent long", gate=parent_gate)],
            [Text("never finishes", gate=child_gate, started=child_started)],
        )
        self.assertTrue(child_started.wait(120))
        child = self.await_child(b, session, lambda c: c["status"] == "running")
        handle = child["handle"]
        def steer(attempt):
            return b.request(
                "session.steer_child", {"session": session, "handle": handle, "attempt": attempt, "text": "please be brief"}
            )

        def stop(attempt):
            return b.request("session.stop_child", {"session": session, "handle": handle, "attempt": attempt})

        self.assertEqual(steer(2), {"control": {"attempt_changed": 1}})
        self.assertEqual(stop(2), {"control": {"attempt_changed": 1}})
        self.assertEqual(self.children(b, session)[0]["status"], "running")
        self.assertEqual(steer(1), {"control": "accepted"})
        with self.assertRaises(RpcError) as caught:
            b.request(
                "session.steer_child",
                {"session": session, "handle": "agent-ffffffffffffffff", "attempt": 1, "text": "x"},
            )
        self.assertEqual((caught.exception.code, caught.exception.data["kind"]), (-32003, "not_found"))
        self.assertEqual(b.request("session.child_outcome", {"session": session, "handle": handle, "attempt": 1}), {"outcome": None})

        self.assertEqual(stop(1), {"control": "accepted"})
        stopped = self.await_child(b, session, lambda c: c["status"] == "stopped", handle=handle)
        self.assertEqual((stopped["handle"], stopped["attempt"]), (handle, 1))
        outcome = b.request("session.child_outcome", {"session": session, "handle": handle, "attempt": 1})["outcome"]
        self.assertEqual((outcome["handle"], outcome["attempt"], outcome["status"]), (handle, 1, "stopped"))
        self.assertIsNone(outcome["result"])
        self.assertEqual(stop(1), {"control": "terminal"})
        self.assertEqual(steer(1), {"control": "terminal"})
        page = b.request("session.read_entries", {"session": session, "cut": stopped["cut"]})
        self.assertTrue(page["entries"])
        self.assertEqual(len(self.children(b, session)), 1)
        child_gate.set()
        parent_gate.set()
        self.await_state(b, session, lambda s: s["active_request"] is None)
        self.assert_no_stray(b)

    def test_continuation_keeps_the_handle_and_advances_the_attempt(self):
        b = self.open()
        session = self.create(b, FAKE)
        b.request("session.open_view", {"session": session})
        box = {}
        parent_gate, child_gate, child_started = threading.Event(), threading.Event(), threading.Event()
        self.addCleanup(parent_gate.set)
        self.addCleanup(child_gate.set)

        def continue_call(_body):
            return ToolCall(
                "agent",
                {
                    "operation": {"kind": "continue", "handle": box["handle"]},
                    "brief": {"objective": "continue the same work"},
                    "description": "Continue child",
                },
                "call_2",
            )

        self.start_delegation(
            b,
            session,
            [Text("parent long", gate=parent_gate), continue_call, Text("parent integrates")],
            [Text("first result"), Text("second result", gate=child_gate, started=child_started), Text("second result")],
        )
        done = self.await_child(b, session, lambda c: c["status"] == "completed")
        handle = box["handle"] = done["handle"]
        def outcome_of(attempt):
            return b.request("session.child_outcome", {"session": session, "handle": handle, "attempt": attempt})["outcome"]

        first = outcome_of(1)
        self.assertEqual((first["attempt"], first["result"]), (1, "first result"))
        parent_gate.set()
        self.assertTrue(child_started.wait(120))
        second = self.await_child(b, session, lambda c: c["attempt"] == 2 and c["status"] == "running", handle=handle)
        self.assertEqual(second["handle"], handle)
        self.assertEqual(second["cut"]["journal"], {"child": handle})
        self.assertEqual([c["handle"] for c in self.children(b, session)], [handle])
        self.assertEqual(
            b.request("session.steer_child", {"session": session, "handle": handle, "attempt": 1, "text": "late"}),
            {"control": {"attempt_changed": 2}},
        )
        self.assertEqual(
            b.request("session.stop_child", {"session": session, "handle": handle, "attempt": 1}),
            {"control": {"attempt_changed": 2}},
        )
        self.assertEqual(self.children(b, session)[0]["status"], "running")
        self.assertEqual(outcome_of(1), first, "attempt 1's outcome stays readable while attempt 2 runs")
        self.assertIsNone(outcome_of(2))
        with self.assertRaises(RpcError) as caught:
            outcome_of(3)
        self.assertEqual((caught.exception.code, caught.exception.data["kind"]), (-32003, "not_found"))
        self.assertEqual(
            b.request("session.steer_child", {"session": session, "handle": handle, "attempt": 2, "text": "stay brief"}),
            {"control": "accepted"},
        )
        child_gate.set()
        finished = self.await_child(b, session, lambda c: c["attempt"] == 2 and c["status"] == "completed", handle=handle)
        self.assertEqual(finished["handle"], handle)
        outcome = outcome_of(2)
        self.assertEqual((outcome["handle"], outcome["attempt"], outcome["status"]), (handle, 2, "completed"))
        self.assertEqual(outcome["result"], "second result")
        self.assertEqual(outcome_of(1), first, "a continuation never replaces an earlier outcome")
        self.await_state(b, session, lambda s: s["active_request"] is None)
        self.assert_no_stray(b)


class Control(BridgeCase):
    def settle_view(self, bridge, until, timeout=120):
        seen = []
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                note = bridge.notification(timeout=1)
            except queue.Empty:
                continue
            item = view_item(note)
            if item is None:
                continue
            seen.append(item)
            if until(item):
                return seen
        self.fail("the view never reached the wanted item")

    def state(self, bridge, session):
        return bridge.request("session.state", {"session": session})

    def wait_state(self, bridge, session, wanted, timeout=120):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            state = self.state(bridge, session)
            if wanted(state):
                return state
            time.sleep(0.1)
        self.fail("session state never reached the wanted shape")

    def test_pending_question_round_trip_with_complete_options(self):
        b = self.open()
        session = self.create(b, FAKE)
        b.request("session.open_view", {"session": session})
        options = [
            {"label": "Alpha", "description": "The first path. " + "It keeps every existing contract and costs a full migration. " * 4},
            {"label": "Beta", "description": "The second path, which trades the migration for a narrower contract."},
        ]
        question = {"id": "q1", "prompt": "Which path?", "options": options}
        FIXTURE.provider.script[:] = [ToolCall("ask_user", {"questions": [question]}), Text("answered")]
        request = b.request("session.prompt", {"session": session, "input": {"text": "ask me"}, "delivery": "start"})["request_id"]
        ready = self.settle_view(
            b,
            lambda i: kind(i) == "observation" and kind(i["observation"]["observation"]["event"]) == "user_request_ready",
        )
        event = ready[-1]["observation"]["observation"]["event"]["user_request_ready"]
        self.assertEqual(event["tool_call_id"], "call_1")
        pending = self.wait_state(b, session, lambda s: s["user_request"] is not None)["user_request"]
        self.assertEqual(pending["id"], event["request"]["id"])
        self.assertEqual(len(pending["questions"]), 1)
        asked = pending["questions"][0]
        self.assertEqual((asked["id"], asked["prompt"], asked["allow_multiple"]), ("q1", "Which path?", False))
        self.assertEqual(asked["options"], options)
        state = self.state(b, session)
        self.assertEqual(state["active_request"], request)
        self.assertEqual(state["requests"][0]["status"], "running")
        time.sleep(2)
        quiet = []
        while True:
            try:
                item = view_item(b.notifications.get_nowait())
            except queue.Empty:
                break
            if item is not None:
                quiet.append(item)
        self.assertFalse([i for i in quiet if kind(i) == "completed"], "a waiting question must not look settled")
        self.assertEqual(self.state(b, session)["active_request"], request)

        with self.assertRaises(RpcError) as caught:
            b.request("session.answer", {"session": session, "request_id": "not-pending", "answers": []})
        self.assertEqual((caught.exception.code, caught.exception.data["kind"]), (-32003, "not_found"))
        self.assertEqual(self.state(b, session)["user_request"]["id"], pending["id"])

        answer = {"question_id": "q1", "selected_options": ["Beta"], "freeform_text": None, "none_of_above": False}
        self.assertEqual(b.request("session.answer", {"session": session, "request_id": pending["id"], "answers": [answer]}), {})
        items = self.settle_view(b, lambda i: kind(i) == "completed" and i["completed"]["request_id"] == request)
        self.assertEqual(items[-1]["completed"]["stop_reason"], "end_turn")
        self.assertEqual(text_deltas(items), "answered")
        finished = next(
            i["observation"]["observation"]["event"]["tool_finished"]
            for i in items
            if kind(i) == "observation" and kind(i["observation"]["observation"]["event"]) == "tool_finished"
        )
        self.assertEqual(finished["name"], "ask_user")
        self.assertFalse(finished["is_error"])
        tool_text = [m["content"] for m in FIXTURE.provider.requests[-1]["messages"] if m["role"] == "tool"]
        self.assertIn("q1: Beta", " ".join(map(str, tool_text)))
        self.assertIsNone(self.wait_state(b, session, lambda s: s["user_request"] is None)["user_request"])
        with self.assertRaises(RpcError) as again:
            b.request("session.answer", {"session": session, "request_id": pending["id"], "answers": [answer]})
        self.assertEqual(again.exception.code, -32003)

    def test_mode_and_model_state_is_host_confirmed(self):
        b = self.open()
        session = self.create(b, FAKE)
        b.request("session.open_view", {"session": session})
        before = self.state(b, session)["configuration"]
        self.assertEqual(before, {"provider": "fake", "model": "fake-model", "reasoning": None, "mode": "build"})
        changed = b.request("session.configure", {"session": session, "change": {"mode": "plan"}})
        self.assertEqual(changed, {"configuration": {**before, "mode": "plan"}})
        self.assertEqual(self.state(b, session)["configuration"], changed["configuration"])
        announced = self.settle_view(b, lambda i: kind(i) == "state")[-1]
        self.assertEqual(announced["state"]["configuration"], changed["configuration"])
        for change, text in (
            ({"reasoning": "high"}, "reasoning"),
            ({"model": "nonesuch"}, "unavailable"),
            ({"provider": "nonesuch"}, "unavailable"),
        ):
            with self.assertRaises(RpcError) as caught:
                b.request("session.configure", {"session": session, "change": change})
            self.assertEqual((caught.exception.code, caught.exception.data["kind"]), (-32005, "invalid"))
            self.assertIn(text, caught.exception.message)
            self.assertEqual(self.state(b, session)["configuration"], changed["configuration"])
        back = b.request("session.configure", {"session": session, "change": {"mode": "build"}})
        self.assertEqual(back, {"configuration": before})
        announced = self.settle_view(b, lambda i: kind(i) == "state")[-1]
        self.assertEqual(announced["state"]["configuration"], before)
        catalog = b.request("catalog", {"cwd": str(b.cwd)})
        self.assertEqual(
            catalog["providers"],
            [{"id": "fake", "name": "Fake", "models": [{"id": "fake-model", "name": "Fake model", "reasoning": "unsupported"}]}],
        )

    def test_goal_lifecycle_is_host_driven_and_quiet_work_stays_active(self):
        b = self.open()
        session = self.create(b, FAKE)
        b.request("session.open_view", {"session": session})
        with self.assertRaises(RpcError) as none:
            b.request("session.change_goal", {"session": session, "change": "pause"})
        self.assertEqual((none.exception.code, none.exception.data["kind"]), (-32005, "invalid"))
        gate, started = threading.Event(), threading.Event()
        self.addCleanup(gate.set)
        FIXTURE.provider.script[:] = [Text("goal work", gate=gate, started=started)]
        start = b.request(
            "session.change_goal",
            {"session": session, "change": {"start": {"objective": "ship the thing", "duration_seconds": None}}},
        )
        request = start["request_id"]
        self.assertIsInstance(request, str)
        self.assertTrue(started.wait(120))
        goal = self.state(b, session)["goal"]
        self.assertEqual((goal["objective"], goal["phase"], goal["cause"]), ("ship the thing", "active", "started"))
        self.assertEqual(self.state(b, session)["active_request"], request)
        self.assertIsNone(goal["completion"])
        edited = b.request("session.change_goal", {"session": session, "change": {"edit": "ship the other thing"}})
        self.assertEqual(edited, {"request_id": None})
        goal = self.state(b, session)["goal"]
        self.assertEqual((goal["objective"], goal["phase"]), ("ship the other thing", "active"))
        paused = b.request("session.change_goal", {"session": session, "change": "pause"})
        self.assertEqual(paused, {"request_id": None})
        goal = self.wait_state(b, session, lambda s: s["goal"]["phase"] == "paused")["goal"]
        self.assertEqual(goal["cause"], "user_paused")
        gate.set()
        items = self.settle_view(b, lambda i: kind(i) == "completed" and i["completed"]["request_id"] == request)
        self.assertEqual(items[-1]["completed"]["stop_reason"], "cancelled")
        self.assertEqual(self.state(b, session)["goal"]["phase"], "paused")
        resume_gate, resume_started = threading.Event(), threading.Event()
        self.addCleanup(resume_gate.set)
        FIXTURE.provider.script[:] = [Text("resumed work", gate=resume_gate, started=resume_started)]
        resumed = b.request("session.change_goal", {"session": session, "change": {"resume": None}})
        self.assertIsInstance(resumed["request_id"], str)
        self.assertNotEqual(resumed["request_id"], request)
        self.assertTrue(resume_started.wait(120))
        goal = self.state(b, session)["goal"]
        self.assertEqual((goal["phase"], goal["cause"]), ("active", "resumed"))
        self.assertEqual(self.state(b, session)["active_request"], resumed["request_id"])
        cleared = b.request("session.change_goal", {"session": session, "change": "clear"})
        self.assertEqual(cleared, {"request_id": None})
        state = self.wait_state(b, session, lambda s: s["goal"] is None or s["goal"]["phase"] == "cleared")
        resume_gate.set()
        self.wait_state(b, session, lambda s: s["active_request"] is None)
        self.assertIn(self.state(b, session)["goal"], (None, state["goal"]))

    def test_plan_lifecycle_uses_the_hosts_artifact(self):
        b = self.open()
        session = self.create(b, {**FAKE, "mode": "plan"})
        b.request("session.open_view", {"session": session})
        self.assertEqual(b.request("session.plan", {"session": session}), {"plan": None})
        stages = "\n\n".join(f"### {n}. Stage {n}\nOutcome {n}: preserve behavior.\n\nBODY_{n}" for n in (1, 2, 3))
        markdown = f"# bridge-plan\n\n## Requirements\nShip the bridge.\n\n## Testing\nRun the suite.\n\n## Delivery Plan\n\n{stages}"
        FIXTURE.provider.script[:] = [Text("Here is the plan.\n\n<proposed_plan>\n" + markdown + "\n</proposed_plan>")]
        request, items = self.run_turn(b, session, "plan it")
        proposed = [
            i["observation"]["observation"]["event"]["plan_proposed"]
            for i in items
            if kind(i) == "observation" and kind(i["observation"]["observation"]["event"]) == "plan_proposed"
        ]
        self.assertEqual(len(proposed), 1)
        self.assertEqual(proposed[0]["name"], "bridge-plan")
        self.assertEqual(proposed[0]["markdown"], markdown)
        plan = self.wait_state(b, session, lambda s: s["plan"] is not None)["plan"]
        self.assertEqual((plan["name"], plan["status"]), ("bridge-plan", "review_pending"))
        artifact = b.request("session.plan", {"session": session})["plan"]
        self.assertEqual(artifact["id"], plan["id"])
        self.assertNotEqual(artifact["id"], proposed[0]["id"])
        self.assertEqual(artifact["markdown"], markdown)
        self.assertEqual([s["title"] for s in artifact["sections"]], ["Requirements", "Testing"])
        self.assertEqual([s["title"] for s in artifact["stages"]], ["Stage 1", "Stage 2", "Stage 3"])
        self.assertTrue(Path(artifact["path"]).is_file())
        on_disk = Path(artifact["path"]).read_text()
        self.assertTrue(on_disk.startswith("---\nsessionId: "))
        self.assertIn(markdown, on_disk)
        with self.assertRaises(RpcError) as stale:
            b.request("session.decide_plan", {"session": session, "plan_id": proposed[0]["id"], "decision": "implement"})
        self.assertEqual((stale.exception.code, stale.exception.data["kind"]), (-32007, "failed"))
        self.assertEqual(b.request("session.plan", {"session": session})["plan"]["status"], "review_pending")
        saved = b.request("session.decide_plan", {"session": session, "plan_id": artifact["id"], "decision": "save_and_stop"})
        self.assertEqual(saved, {"request_id": None})
        self.assertEqual(self.wait_state(b, session, lambda s: s["plan"]["status"] == "saved_stopped")["active_request"], None)
        FIXTURE.provider.script[:] = [Text("implementing")]
        implement = b.request("session.decide_plan", {"session": session, "plan_id": artifact["id"], "decision": "implement"})
        self.assertIsInstance(implement["request_id"], str)
        self.settle_view(b, lambda i: kind(i) == "completed" and i["completed"]["request_id"] == implement["request_id"])
        self.assertIn(
            self.state(b, session)["plan"]["status"], ("accepted", "implementing", "completed")
        )
        self.assert_no_stray(b)


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mimir", default=os.environ.get("MIMIR_BIN"), type=Path)
    parser.add_argument("--package", default=HERE.parent / "out" / "sh.roboco.bridge-0.1.0", type=Path)
    parser.add_argument("--root", type=Path, help="reuse this isolated root (default: a fresh temp dir)")
    parser.add_argument("--skip-install", action="store_true", help="only with --root, the package is already installed")
    parser.add_argument("--keep", action="store_true")
    parser.add_argument("--report", type=Path)
    parser.add_argument("-v", "--verbose", action="store_true")
    parser.add_argument("-k", dest="pattern", action="append")
    return parser.parse_args()


def main():
    global FIXTURE
    args = parse_args()
    if not args.mimir or not args.mimir.exists():
        sys.exit("--mimir (or MIMIR_BIN) must name a built mimir binary")
    if not (args.package / "plugin.wasm").exists():
        sys.exit(f"no built package at {args.package}; run build.py first")
    root = args.root or Path(tempfile.mkdtemp(prefix="roboco-bridge-"))
    if args.root is None and any(root.iterdir()):
        sys.exit("fresh root expected")
    FIXTURE = Fixture(args.mimir, args.package, root, args.keep)
    install = {}
    try:
        if not args.skip_install:
            install = FIXTURE.install()
            print("installed", install["install"])
        FIXTURE.start_provider()
        suite = unittest.TestSuite()
        loader = unittest.TestLoader()
        if args.pattern:
            loader.testNamePatterns = [f"*{p}*" for p in args.pattern]
        for case in (Handshake, WireErrors, Framing, Sessions, Conversations, Multiplexing, LargePayloads, Processes, Delegation, Control):
            suite.addTests(loader.loadTestsFromTestCase(case))
        result = unittest.TextTestRunner(verbosity=2 if args.verbose else 1).run(suite)
        summary = {
            "root": str(root),
            "mimir": str(args.mimir),
            "package": str(args.package),
            "ran": result.testsRun,
            "failures": [str(t) for t, _ in result.failures],
            "errors": [str(t) for t, _ in result.errors],
            "install": install,
        }
        if args.report:
            args.report.write_text(json.dumps(summary, indent=2) + "\n")
        print(json.dumps({k: v for k, v in summary.items() if k != "install"}, indent=2))
        return 0 if result.wasSuccessful() else 1
    finally:
        FIXTURE.provider.stop()
        if args.root is None and not args.keep:
            shutil.rmtree(root, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
