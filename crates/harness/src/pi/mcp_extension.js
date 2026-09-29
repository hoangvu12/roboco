/**
 * Roboco MCP bridge for pi — injected by the Roboco engine, never installed
 * by hand.
 *
 * pi's first-party JSONL RPC has no MCP surface: no `--mcp-config` flag, no
 * settings key, no runtime command that attaches a server. The one door it
 * does open at spawn time is `--extension`. The Roboco pi driver writes this
 * file into a per-run scratch dir, passes `--extension <path>`, and stamps
 * the whole server spec (command, args, env, name) into `ROBOCO_MCP_SERVER`.
 *
 * This bridge then does exactly what the pi-acp shim did for upstream
 * zeron, minus the adapter: it spawns the configured `roboco mcp` process,
 * speaks the stdio MCP subset (initialize / tools/list / tools/call), and
 * re-exposes every server tool as a first-class pi tool through
 * `pi.registerTool` — named `mcp__<server>__<tool>` so transcripts render
 * them like the MCP tools of every other harness.
 *
 * Failure policy: degrade, never break the run. A missing env var leaves
 * the extension inert; a bridge that cannot spawn or handshake logs to
 * stderr and registers nothing (the agent just runs without Roboco tools).
 */

import { spawn } from "node:child_process";

const CONNECT_TIMEOUT_MS = 15000;
const PROTOCOL_VERSION = "2025-06-18";

function serverSpec() {
  const raw = process.env.ROBOCO_MCP_SERVER;
  if (!raw) return undefined;
  try {
    const spec = JSON.parse(raw);
    if (spec && typeof spec.command === "string" && Array.isArray(spec.args)) {
      return spec;
    }
  } catch {
    // Malformed spec: stay inert rather than crash pi startup.
  }
  return undefined;
}

export default function robocoMcpBridge(pi) {
  const server = serverSpec();
  if (!server) return;
  const prefix = `mcp__${server.name || "roboco"}__`;

  // One live connection at most; dropped (and re-dialed) if the child dies.
  let connection = null;
  let connecting = null;
  let instructions = "";
  let registered = false;

  function log(text) {
    console.error(`[roboco-mcp] ${text}`);
  }

  function withTimeout(promise, ms, what) {
    let timer = null;
    return Promise.race([
      promise,
      new Promise((_resolve, reject) => {
        timer = setTimeout(() => reject(new Error(`${what} timed out after ${ms}ms`)), ms);
      }),
    ]).finally(() => clearTimeout(timer));
  }

  /** Spawn the MCP child and complete the handshake. */
  function connect() {
    const child = spawn(server.command, server.args, {
      env: { ...process.env, ...(server.env || {}) },
      stdio: ["pipe", "pipe", "pipe"],
    });
    const pending = new Map();
    let nextId = 1;
    let buffer = "";

    child.stdout.setEncoding("utf8");
    // Swallow stream errors: a write into a dead pipe (EPIPE between the
    // child dying and the exit handler rejecting pending calls) must not
    // surface as an unhandled stream error and crash pi.
    child.stdin.on("error", () => {});
    child.stdout.on("error", () => {});
    child.stderr.on("error", () => {});
    child.stdout.on("data", (chunk) => {
      buffer += chunk;
      let newline = buffer.indexOf("\n");
      while (newline !== -1) {
        const line = buffer.slice(0, newline).trim();
        buffer = buffer.slice(newline + 1);
        if (line) dispatch(line);
        newline = buffer.indexOf("\n");
      }
    });
    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk) => log(`server stderr: ${chunk.trim()}`));
    child.on("exit", () => {
      const error = new Error("roboco mcp server exited");
      for (const { reject } of pending.values()) reject(error);
      pending.clear();
      if (connection && connection.child === child) connection = null;
    });
    // A spawn failure (missing binary, EACCES) never fires "exit" — reject
    // the pending handshake right away so startup does not wait out the
    // connect timeout for a server that was never alive.
    child.on("error", (error) => {
      log(`could not spawn ${server.command}: ${error.message}`);
      for (const { reject } of pending.values()) reject(error);
      pending.clear();
      if (connection && connection.child === child) connection = null;
    });

    function dispatch(line) {
      let message;
      try {
        message = JSON.parse(line);
      } catch {
        return;
      }
      const waiter = pending.get(message.id);
      if (!waiter) return;
      pending.delete(message.id);
      if (message.error) waiter.reject(new Error(message.error.message || "mcp error"));
      else waiter.resolve(message.result);
    }

    function request(method, params) {
      const id = nextId++;
      return new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject });
        child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", id, method, params })}\n`);
      });
    }

    const conn = { child, request };
    const handshake = request("initialize", {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: "roboco-pi-bridge", version: "1" },
    }).then((result) => {
      if (typeof result?.instructions === "string") instructions = result.instructions;
      child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" })}\n`);
      return conn;
    });
    return withTimeout(handshake, CONNECT_TIMEOUT_MS, "mcp initialize").catch((error) => {
      // Never leave a wedged child behind: a handshake that timed out or
      // failed owns nothing worth keeping alive.
      try {
        child.kill();
      } catch {
        // Already gone.
      }
      throw error;
    });
  }

  function ensureConnection() {
    if (connection) return Promise.resolve(connection);
    if (!connecting) {
      connecting = connect()
        .then((conn) => {
          connection = conn;
          connecting = null;
          return conn;
        })
        .catch((error) => {
          connecting = null;
          throw error;
        });
    }
    return connecting;
  }

  async function callTool(name, args) {
    const conn = await ensureConnection();
    const result = await conn.request("tools/call", { name, arguments: args });
    const text = (result?.content || [])
      .filter((block) => block && block.type === "text" && typeof block.text === "string")
      .map((block) => block.text)
      .join("\n");
    if (result?.isError) throw new Error(text || `roboco tool ${name} failed`);
    return text;
  }

  function raceAbort(promise, signal) {
    if (signal.aborted) return Promise.reject(new Error("Operation aborted"));
    return new Promise((resolve, reject) => {
      const onAbort = () => reject(new Error("Operation aborted"));
      signal.addEventListener("abort", onAbort, { once: true });
      // The server-side call keeps running (roboco mcp has no cancellation
      // surface); the agent-side turn just stops waiting on it, matching
      // how the other harnesses treat aborted MCP calls.
      promise.then(resolve, reject).finally(() => signal.removeEventListener("abort", onAbort));
    });
  }

  pi.on("session_start", async () => {
    if (registered) return;
    registered = true;
    try {
      const conn = await ensureConnection();
      const listed = await withTimeout(conn.request("tools/list", {}), CONNECT_TIMEOUT_MS, "mcp tools/list");
      for (const tool of listed?.tools || []) {
        if (!tool || typeof tool.name !== "string") continue;
        const name = tool.name;
        pi.registerTool({
          name: `${prefix}${name}`,
          label: `Roboco: ${name}`,
          description: typeof tool.description === "string" ? tool.description : name,
          // Raw JSON Schema (MCP inputSchema) — TypeBox validates plain schemas.
          parameters:
            tool.inputSchema && typeof tool.inputSchema === "object" && !Array.isArray(tool.inputSchema)
              ? tool.inputSchema
              : { type: "object", properties: {} },
          // Roboco tools watch chats for minutes; they must not serialize
          // behind unrelated tool calls.
          executionMode: "parallel",
          async execute(_toolCallId, params, signal) {
            const call = callTool(name, params || {});
            const text = signal ? await raceAbort(call, signal) : await call;
            return {
              content: [{ type: "text", text }],
              details: { tool: name },
            };
          },
        });
      }
      log(`registered ${listed?.tools?.length || 0} roboco tools`);
    } catch (error) {
      registered = false;
      log(`bridge failed: ${error?.message || error}`);
    }
  });

  // The server's cross-tool workflow guidance (parallel batches, wait
  // patterns) rides the system prompt once, not sixteen tool descriptions.
  pi.on("before_agent_start", (event) => {
    if (!instructions) return;
    const options = event.systemPromptOptions;
    if (options && Array.isArray(options.promptGuidelines) && !options.promptGuidelines.includes(instructions)) {
      options.promptGuidelines.push(instructions);
    }
  });

  function killChild() {
    const child = connection?.child;
    if (!child || child.exitCode !== null) return;
    try {
      child.kill();
    } catch {
      // Already gone.
    }
  }
  pi.on("session_shutdown", killChild);
  process.on("exit", killChild);
}
