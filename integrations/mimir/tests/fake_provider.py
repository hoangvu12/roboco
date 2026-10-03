import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Text:
    def __init__(self, chunks, gate=None, started=None):
        self.chunks = [chunks] if isinstance(chunks, str) else list(chunks)
        self.gate = gate
        self.started = started


class ToolCall:
    def __init__(self, name, arguments, call_id="call_1"):
        self.name = name
        self.arguments = arguments
        self.call_id = call_id


class FakeProvider:
    """OpenAI chat-completions streaming endpoint on loopback, scripted per request."""

    def __init__(self):
        self.script = []
        self.child_script = []
        self.requests = []
        self.lock = threading.Lock()
        provider = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *_):
                pass

            def do_GET(self):
                self.send_response(404)
                self.send_header("content-length", "0")
                self.end_headers()

            def do_POST(self):
                length = int(self.headers.get("content-length", "0"))
                body = json.loads(self.rfile.read(length) or b"{}")
                with provider.lock:
                    provider.requests.append(body)
                    system = next((m.get("content") for m in body.get("messages", []) if m.get("role") == "system"), "")
                    if isinstance(system, str) and system.startswith("You are Mimir's mode router"):
                        reply = Text("MODE: [CHAT]")
                    elif not provider.is_parent(body) and provider.child_script:
                        reply = provider.child_script.pop(0)
                    elif provider.script:
                        reply = provider.script.pop(0)
                    else:
                        reply = Text("fake reply")
                if callable(reply):
                    reply = reply(body)
                self.stream(reply)

            def event(self, payload):
                self.wfile.write(b"data: " + json.dumps(payload).encode() + b"\n\n")
                self.wfile.flush()

            def stream(self, reply):
                self.send_response(200)
                self.send_header("content-type", "text/event-stream")
                self.send_header("cache-control", "no-cache")
                self.send_header("connection", "close")
                self.end_headers()
                self.close_connection = True
                base = {"id": "chatcmpl-fake", "object": "chat.completion.chunk", "created": 0, "model": "fake-model"}
                try:
                    if isinstance(reply, ToolCall):
                        delta = {
                            "role": "assistant",
                            "tool_calls": [
                                {
                                    "index": 0,
                                    "id": reply.call_id,
                                    "type": "function",
                                    "function": {"name": reply.name, "arguments": json.dumps(reply.arguments)},
                                }
                            ],
                        }
                        self.event({**base, "choices": [{"index": 0, "delta": delta, "finish_reason": None}]})
                        finish = "tool_calls"
                    else:
                        first = True
                        for chunk in reply.chunks:
                            delta = {"content": chunk}
                            if first:
                                delta["role"] = "assistant"
                            self.event({**base, "choices": [{"index": 0, "delta": delta, "finish_reason": None}]})
                            if first and reply.started is not None:
                                reply.started.set()
                            if first and reply.gate is not None:
                                reply.gate.wait(120)
                            first = False
                        finish = "stop"
                    usage = {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18}
                    self.event({**base, "choices": [{"index": 0, "delta": {}, "finish_reason": finish}], "usage": usage})
                    self.wfile.write(b"data: [DONE]\n\n")
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    @staticmethod
    def tool_names(body):
        return {t.get("function", {}).get("name") for t in body.get("tools") or []}

    def is_parent(self, body):
        return "agent" in self.tool_names(body)

    @property
    def base_url(self):
        return f"http://127.0.0.1:{self.server.server_address[1]}/v1"

    def models_json(self):
        return {
            "providers": {
                "fake": {
                    "name": "Fake",
                    "baseUrl": self.base_url,
                    "api": "openai-chat-completions",
                    "auth": "none",
                    "defaultModel": "fake-model",
                    "models": {
                        "fake-model": {
                            "name": "Fake model",
                            "limit": {"context": 1000000, "output": 200000},
                            "input": ["text"],
                            "reasoning": False,
                            "toolCall": True,
                        }
                    },
                }
            }
        }

    def start(self):
        self.thread.start()

    def stop(self):
        if self.thread.is_alive():
            self.server.shutdown()
        self.server.server_close()
