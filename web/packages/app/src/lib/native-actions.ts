import type {
  NativeBlobSeries,
  NativeChatCatalog,
  NativeChild,
  NativeChildOutcome,
  NativeControl,
  NativePlanArtifact,
  NativeReadiness,
  SessionCommandEntry,
  ToolBlobWindow,
} from "@roboco/proto";
import { methods } from "@roboco/engine-client";
import { composeExport, parseChildOutcome, parseToolDetail, receiptFromCommand, type ControlReceipt } from "./native";

/** The minimal caller shape; `EngineClient` satisfies it. */
export interface NativeCaller {
  call<T>(method: string, params?: unknown): Promise<T>;
}

/** Queue one typed control. The reply is only the ledger command id. */
export async function queueNativeControl(caller: NativeCaller, chatId: string, control: NativeControl): Promise<string> {
  const reply = await caller.call<{ commandId: string }>(methods.QUEUE_COMMAND, {
    chatId,
    command: { kind: "native", control },
    transfers: [],
  });
  return reply.commandId;
}

/** A read-only look at one ledger entry in this chat. It never dispatches or retries. */
export function getCommand(caller: NativeCaller, chatId: string, commandId: string): Promise<SessionCommandEntry | null> {
  return caller.call<SessionCommandEntry | null>(methods.GET_COMMAND, { chatId, commandId });
}

/** Waits between receipt reads. The sum is the longest a control is watched. */
export const RECEIPT_DELAYS_MS: readonly number[] = [150, 300, 600, 1000, 1500, 2000, 2000, 2000, 2000, 2000, 2000];

export interface ReceiptWatch {
  readonly signal?: AbortSignal;
  readonly delaysMs?: readonly number[];
  readonly onReceipt: (receipt: ControlReceipt) => void;
}

function pause(ms: number, signal: AbortSignal | undefined): Promise<void> {
  return new Promise((resolve) => {
    if (signal?.aborted === true) {
      resolve();
      return;
    }
    const done = () => {
      clearTimeout(timer);
      signal?.removeEventListener("abort", done);
      resolve();
    };
    const timer = setTimeout(done, ms);
    signal?.addEventListener("abort", done, { once: true });
  });
}

function describeFailure(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * Queue one control, then read its ledger entry on a bounded schedule until the
 * host settles it. Each state is reported as a `ControlReceipt`. A failed or
 * late read leaves the command id and an `unconfirmed` receipt: the command is
 * never queued a second time. An aborted watch stops reporting.
 */
export async function sendNativeControl(
  caller: NativeCaller,
  chatId: string,
  control: NativeControl,
  watch: ReceiptWatch,
): Promise<ControlReceipt> {
  const report = (receipt: ControlReceipt): ControlReceipt => {
    if (watch.signal?.aborted !== true) {
      watch.onReceipt(receipt);
    }
    return receipt;
  };
  report({ kind: "sending" });
  let commandId: string;
  try {
    commandId = await queueNativeControl(caller, chatId, control);
  } catch (error) {
    return report({ kind: "notQueued", message: describeFailure(error) });
  }
  let last: ControlReceipt = report({ kind: "pending", commandId });
  let reason = "the host has not settled it yet";
  for (const delay of watch.delaysMs ?? RECEIPT_DELAYS_MS) {
    await pause(delay, watch.signal);
    if (watch.signal?.aborted === true) {
      return last;
    }
    try {
      const entry = await getCommand(caller, chatId, commandId);
      if (entry === null) {
        return report({ kind: "unconfirmed", commandId, reason: "the engine has no record of it" });
      }
      const receipt = receiptFromCommand(entry);
      if (receipt.kind !== "pending") {
        return report(receipt);
      }
      reason = "the host has not settled it yet";
    } catch (error) {
      reason = `the receipt could not be read: ${describeFailure(error)}`;
    }
  }
  last = report({ kind: "unconfirmed", commandId, reason });
  return last;
}

export function getNativeReadiness(caller: NativeCaller, force = false): Promise<NativeReadiness> {
  return caller.call<NativeReadiness>(methods.GET_NATIVE_READINESS, { force });
}

export function getNativeCatalog(caller: NativeCaller, chatId: string): Promise<NativeChatCatalog> {
  return caller.call<NativeChatCatalog>(methods.GET_NATIVE_CATALOG, { chatId });
}

export function getNativePlan(caller: NativeCaller, chatId: string): Promise<NativePlanArtifact | null> {
  return caller.call<NativePlanArtifact | null>(methods.GET_NATIVE_PLAN, { chatId });
}

export function listNativeChildren(caller: NativeCaller, chatId: string): Promise<NativeChild[]> {
  return caller.call<NativeChild[]>(methods.LIST_NATIVE_CHILDREN, { chatId });
}

/** The named attempt's outcome, or null while it runs. A malformed reply is an error, not an empty outcome. */
export async function getNativeChildOutcome(
  caller: NativeCaller,
  chatId: string,
  handle: string,
  attempt: number,
): Promise<NativeChildOutcome | null> {
  const raw = await caller.call<unknown>(methods.GET_NATIVE_CHILD_OUTCOME, { chatId, handle, attempt });
  if (raw === null || raw === undefined) {
    return null;
  }
  const outcome = parseChildOutcome(raw);
  if (outcome === null) {
    throw new Error("The engine returned an outcome this version cannot read.");
  }
  return outcome;
}

/** A historical attempt's outcome, read from its `outcomeRef` blob in full. */
export async function readChildOutcomeBlob(caller: NativeCaller, ref: string): Promise<NativeChildOutcome> {
  const stream = new BlobStream(caller, [ref]);
  await stream.readAll();
  const outcome = parseChildOutcome(JSON.parse(stream.text()));
  if (outcome === null) {
    throw new Error("This attempt's outcome could not be read.");
  }
  return outcome;
}

// ---------------------------------------------------------------------------
// Blob windows
// ---------------------------------------------------------------------------

/** Window size for a "Load more" step. The engine caps one window at 1 MiB. */
export const BLOB_WINDOW_BYTES = 256 * 1024;

/** The refs of a numbered blob series, in read order. */
export function seriesRefs(series: NativeBlobSeries): string[] {
  return Array.from({ length: series.chunks }, (_, index) => `${series.blobRef}.${String(index).padStart(6, "0")}`);
}

function decodeBase64(text: string): Uint8Array {
  const binary = atob(text);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

/**
 * Windowed, byte-exact reader over one blob or a numbered series. Windows are
 * byte offsets and a chunk boundary may split a UTF-8 character, so bytes are
 * accumulated across windows and chunks and decoded together.
 */
export class BlobStream {
  readonly #caller: NativeCaller;
  readonly #refs: readonly string[];
  readonly #parts: Uint8Array[] = [];
  #ref = 0;
  #offset = 0;
  #loaded = 0;
  /** Reads run one at a time: two concurrent reads would both take the same offset. */
  #tail: Promise<unknown> = Promise.resolve();

  constructor(caller: NativeCaller, refs: readonly string[]) {
    this.#caller = caller;
    this.#refs = refs;
  }

  get loadedBytes(): number {
    return this.#loaded;
  }

  get done(): boolean {
    return this.#ref >= this.#refs.length;
  }

  /**
   * Read the next window, after any read already in flight. Resolves to the
   * number of bytes added. A failed read leaves the position where it was.
   */
  readNext(maxBytes = BLOB_WINDOW_BYTES): Promise<number> {
    const read = this.#tail.then(() => this.#readWindow(maxBytes));
    this.#tail = read.catch(() => undefined);
    return read;
  }

  async #readWindow(maxBytes: number): Promise<number> {
    const ref = this.#refs[this.#ref];
    if (ref === undefined) {
      return 0;
    }
    const window = await this.#caller.call<ToolBlobWindow>(methods.FETCH_TOOL_BLOB, {
      blobRef: ref,
      offset: this.#offset,
      maxBytes,
    });
    const bytes = window.encoding === "base64" ? decodeBase64(window.text) : new TextEncoder().encode(window.text);
    this.#parts.push(bytes);
    this.#loaded += bytes.length;
    if (window.nextOffset === null || window.nextOffset === undefined) {
      this.#ref += 1;
      this.#offset = 0;
    } else {
      this.#offset = window.nextOffset;
    }
    return bytes.length;
  }

  async readAll(): Promise<void> {
    while (!this.done) {
      await this.readNext(1024 * 1024);
    }
  }

  /** Everything read so far. A character cut by the window edge waits for the next window. */
  text(): string {
    return new TextDecoder().decode(this.bytes(), { stream: !this.done });
  }

  bytes(): Uint8Array {
    const all = new Uint8Array(this.#loaded);
    let at = 0;
    for (const part of this.#parts) {
      all.set(part, at);
      at += part.length;
    }
    return all;
  }
}

/** Hand the complete bytes to the user as a file download. */
export function downloadBytes(bytes: Uint8Array, filename: string, type = "text/plain;charset=utf-8"): void {
  const url = URL.createObjectURL(new Blob([bytes as BlobPart], { type }));
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

/**
 * The complete detail of one tool call: the record exactly as stored, then
 * every progress and stream chunk, each its own section. A record that is not
 * a tool detail is exported as stored, alone.
 */
export async function exportToolDetail(caller: NativeCaller, detailRef: string): Promise<Uint8Array> {
  const record = new BlobStream(caller, [detailRef]);
  await record.readAll();
  const bytes = record.bytes();
  let detail: NativeToolDetailShape | null = null;
  try {
    detail = parseToolDetail(JSON.parse(new TextDecoder().decode(bytes)));
  } catch {
    detail = null;
  }
  const parts = [
    {
      label: detail !== null ? `Detail record: ${detailRef} (the host's public JSON as stored)` : `Unrecognised record: ${detailRef} (as stored)`,
      bytes,
    },
  ];
  const series: readonly [string, NativeBlobSeries | null][] = [
    ["Progress lines (JSON lines)", detail?.progress ?? null],
    ["Streamed output", detail?.stream ?? null],
  ];
  for (const [what, entry] of series) {
    if (entry === null) {
      continue;
    }
    const stream = new BlobStream(caller, seriesRefs(entry));
    await stream.readAll();
    parts.push({ label: `${what}: ${entry.blobRef} (${entry.chunks} chunks, ${entry.records} records)`, bytes: stream.bytes() });
  }
  return composeExport("Mimir tool call detail", parts);
}

type NativeToolDetailShape = NonNullable<ReturnType<typeof parseToolDetail>>;
