import base64
import json
import os
import queue
import subprocess
import threading
from pathlib import Path


class RpcError(Exception):
    def __init__(self, error):
        super().__init__(f"{error.get('code')} {error.get('message')}")
        self.code = error.get("code")
        self.message = error.get("message")
        self.data = error.get("data") or {}
        self.error = error


class Reassembler:
    def __init__(self):
        self.transfers = {}

    def feed(self, frame):
        if frame.get("method") != "bridge.fragment":
            return frame
        params = frame["params"]
        entry = self.transfers.setdefault(
            params["transfer"], {"count": params["count"], "total": params["total_bytes"], "parts": {}}
        )
        assert entry["count"] == params["count"] and entry["total"] == params["total_bytes"]
        assert params["index"] not in entry["parts"], "fragment delivered twice"
        entry["parts"][params["index"]] = base64.b64decode(params["data"])
        if len(entry["parts"]) < entry["count"]:
            return None
        del self.transfers[params["transfer"]]
        data = b"".join(entry["parts"][i] for i in range(entry["count"]))
        assert len(data) == entry["total"], "reassembled length differs from total_bytes"
        return json.loads(data)


def isolated_env(root: Path) -> dict:
    root.mkdir(parents=True, exist_ok=True)
    for name in ("home", "agent", "work"):
        (root / name).mkdir(exist_ok=True)
    env = {
        key: value
        for key, value in os.environ.items()
        if not key.startswith(("MIMIR_", "ANTHROPIC_", "OPENAI_", "GEMINI_", "GOOGLE_"))
    }
    env.update(
        HOME=str(root / "home"),
        MIMIR_CODING_AGENT_DIR=str(root / "agent"),
        MIMIR_MODELS_PATH=str(root / "absent-models.json"),
        MIMIR_DISABLE_MODELS_FETCH="1",
        MIMIR_HYPERLINKS="0",
        NO_COLOR="1",
    )
    return env


class Bridge:
    def __init__(self, mimir: Path, env: dict, cwd: Path, plugin="sh.roboco.bridge", read_stdout=True):
        self.process = subprocess.Popen(
            [str(mimir), "plugin", "run", plugin],
            cwd=cwd,
            env=env,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        self.lock = threading.Lock()
        self.waiters = {}
        self.early = {}
        self.notifications = queue.Queue()
        self.raw_frames = []
        self.lines = []
        self.unmatched = queue.Queue()
        self.stderr = bytearray()
        self.eof = threading.Event()
        self.fragment_count = 0
        self.sample = []
        self.reader_error = None
        self.next_id = 0
        self.reassembler = Reassembler()
        self.reader = threading.Thread(target=self.read_stdout, daemon=True)
        self.err_reader = threading.Thread(target=self.read_stderr, daemon=True)
        if read_stdout:
            self.reader.start()
        self.err_reader.start()

    def read_stderr(self):
        for chunk in iter(lambda: self.process.stderr.read1(65536), b""):
            self.stderr.extend(chunk)

    def read_stdout(self):
        try:
            for line in self.process.stdout:
                body = line.rstrip(b"\n")
                assert b"\n" not in body and line.endswith(b"\n"), "a frame was not newline terminated"
                self.lines.append(len(body))
                if len(body) < 4096:
                    self.sample.append(body)
                parsed = json.loads(line)
                if parsed.get("method") == "bridge.fragment":
                    self.fragment_count += 1
                frame = self.reassembler.feed(parsed)
                if frame is not None:
                    self.route(frame)
        except Exception as error:
            self.reader_error = error
        self.eof.set()
        with self.lock:
            for slot in self.waiters.values():
                slot.put(None)

    def route(self, frame):
        if "method" in frame and "id" not in frame:
            self.notifications.put(frame)
            return
        key = json.dumps(frame.get("id"), sort_keys=True)
        with self.lock:
            slot = self.waiters.pop(key, None)
        if slot is None:
            self.unmatched.put(frame)
        else:
            slot.put(frame)

    def send_line(self, data: bytes):
        self.process.stdin.write(data)
        self.process.stdin.flush()

    def raw_request(self, line: bytes, id, timeout=60):
        slot = queue.Queue(maxsize=1)
        with self.lock:
            self.waiters[json.dumps(id, sort_keys=True)] = slot
        self.send_line(line if line.endswith(b"\n") else line + b"\n")
        return self.settle(slot, timeout)

    def expect(self, id):
        slot = queue.Queue(maxsize=1)
        with self.lock:
            self.waiters[json.dumps(id, sort_keys=True)] = slot
        return slot

    def sampled(self, needle: bytes):
        return [line for line in self.sample if needle in line]

    def send(self, message):
        self.send_line(json.dumps(message, separators=(",", ":")).encode() + b"\n")

    def submit(self, method, params=None, id=...):
        if id is ...:
            self.next_id += 1
            id = self.next_id
        slot = queue.Queue(maxsize=1)
        with self.lock:
            self.waiters[json.dumps(id, sort_keys=True)] = slot
        message = {"jsonrpc": "2.0", "id": id, "method": method}
        if params is not None:
            message["params"] = params
        self.send(message)
        return slot

    @staticmethod
    def settle(slot, timeout):
        try:
            frame = slot.get(timeout=timeout)
        except queue.Empty:
            raise TimeoutError("no response within the deadline") from None
        if frame is None:
            raise EOFError("bridge output ended before the response")
        return frame

    def request(self, method, params=None, id=..., timeout=60):
        frame = self.settle(self.submit(method, params, id), timeout)
        if "error" in frame:
            raise RpcError(frame["error"])
        return frame["result"]

    def request_frame(self, method, params=None, id=..., timeout=60):
        return self.settle(self.submit(method, params, id), timeout)

    def notification(self, timeout=60):
        return self.notifications.get(timeout=timeout)

    def initialize(self, limits=None, versions=(1,), timeout=120):
        params = {"protocol_versions": list(versions), "client": {"name": "transport-test", "version": "1"}}
        if limits:
            params["limits"] = limits
        return self.request("initialize", params, timeout=timeout)

    def finish(self, timeout=60):
        try:
            self.process.stdin.close()
        except BrokenPipeError:
            pass
        code = self.process.wait(timeout=timeout)
        self.reader.join(timeout)
        self.err_reader.join(timeout)
        return code

    def kill(self):
        if self.process.poll() is None:
            self.process.kill()
            self.process.wait()

    @property
    def stderr_text(self):
        return self.stderr.decode(errors="replace")
