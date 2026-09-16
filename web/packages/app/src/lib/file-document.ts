import type { WorkspaceFileText, WorkspaceReadOnlyReason } from "@roboco/proto";
import type { WorkspaceFilesClient } from "./files-client";
import { describeFilesError } from "./files-client";
import { fileReadOnlyReason, writableEncoding, writableLineEnding } from "./files";

/**
 * One open workspace file: load, edit, save, and the desktop's write-outcome
 * state machine (crates/ui/src/files/document.rs). Saves carry the read
 * snapshot's checkout identity and content hash, so a file that changed on
 * disk since the read answers `conflict` — the buffer is preserved and the
 * phase banner offers a reload, exactly like the desktop.
 */

export type DocumentPhase =
  | { readonly kind: "loading" }
  | { readonly kind: "ready" }
  | { readonly kind: "saving" }
  | { readonly kind: "saveFailed"; readonly message: string }
  | { readonly kind: "conflict"; readonly diskHash: string | null }
  | { readonly kind: "externallyModified"; readonly diskHash: string | null }
  | { readonly kind: "deletedOnDisk" }
  | { readonly kind: "readOnly"; readonly reason: WorkspaceReadOnlyReason }
  | { readonly kind: "error"; readonly message: string };

export interface FileDocumentSnapshot {
  readonly phase: DocumentPhase;
  /** The editor buffer (== the last read text until edited). */
  readonly text: string;
  readonly file: WorkspaceFileText | null;
  /** The document accepts edits (loaded text, writable shape). */
  readonly editable: boolean;
  /** Edits exist that the engine has not acknowledged. */
  readonly dirty: boolean;
}

interface PendingSave {
  readonly revision: number;
  readonly text: string;
  readonly expectedContentHash: string;
  readonly expectedCheckoutId: string;
}

export class FileDocument {
  readonly #client: WorkspaceFilesClient;
  readonly path: string;
  readonly #listeners = new Set<() => void>();

  #phase: DocumentPhase = { kind: "loading" };
  #file: WorkspaceFileText | null = null;
  #text = "";
  #generation = 0;
  #revision = 0;
  #savedRevision = 0;
  #savedHash: string | null = null;
  #pendingSave: PendingSave | null = null;
  #snapshot: FileDocumentSnapshot;
  #disposed = false;

  constructor(client: WorkspaceFilesClient, path: string) {
    this.#client = client;
    this.path = path;
    this.#snapshot = this.#takeSnapshot();
  }

  getSnapshot(): FileDocumentSnapshot {
    return this.#snapshot;
  }

  subscribe(listener: () => void): () => void {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  }

  dispose(): void {
    this.#disposed = true;
    this.#generation += 1;
    this.#listeners.clear();
  }

  /** (Re)read the file from the engine; the fresh snapshot replaces the buffer. */
  load(): void {
    const generation = ++this.#generation;
    this.#phase = { kind: "loading" };
    this.#pendingSave = null;
    this.#commit();
    void this.#client
      .readFile(this.path)
      .then((file) => {
        if (this.#accepts(generation)) {
          this.#setLoaded(file);
        }
      })
      .catch((error: unknown) => {
        if (this.#accepts(generation)) {
          this.#phase = { kind: "error", message: describeFilesError(error) };
          this.#commit();
        }
      });
  }

  /** Desktop `mark_user_edit`: dirty the buffer; a failed save retries cleanly. */
  edit(text: string): void {
    if (!this.isEditable()) {
      return;
    }
    this.#text = text;
    this.#revision += 1;
    if (this.#phase.kind === "saveFailed") {
      this.#phase = { kind: "ready" };
    }
    this.#commit();
  }

  /** Desktop `begin_save` + the write RPC; outcomes land as phases. */
  save(): void {
    if (!this.canSave()) {
      return;
    }
    const pending: PendingSave = {
      revision: this.#revision,
      text: this.#text,
      expectedContentHash: this.#savedHash!,
      expectedCheckoutId: this.#file!.checkoutId,
    };
    const file = this.#file!;
    this.#pendingSave = pending;
    this.#phase = { kind: "saving" };
    this.#commit();
    void this.#client
      .writeFile({
        expectedCheckoutId: pending.expectedCheckoutId,
        path: this.path,
        text: pending.text,
        expectedContentHash: pending.expectedContentHash,
        encoding: writableEncoding(file.encoding)!,
        lineEnding: writableLineEnding(file.lineEnding)!,
      })
      .then((outcome) => {
        if (this.#pendingSave?.revision !== pending.revision) {
          return;
        }
        if (outcome.status === "written") {
          this.#savedHash = outcome.file.contentHash;
          this.#savedRevision = pending.revision;
          this.#pendingSave = null;
          this.#phase = { kind: "ready" };
        } else {
          this.#pendingSave = null;
          this.#phase = { kind: "conflict", diskHash: outcome.currentContentHash ?? null };
        }
        this.#commit();
      })
      .catch((error: unknown) => {
        if (this.#pendingSave?.revision !== pending.revision) {
          return;
        }
        this.#pendingSave = null;
        this.#phase = { kind: "saveFailed", message: describeFilesError(error) };
        this.#commit();
      });
  }

  /**
   * Reload from disk, discarding the buffer (the desktop's explicit
   * discard-and-reload affordance behind the conflict/changed banners).
   */
  reloadFromDisk(): void {
    this.load();
  }

  /**
   * Watch reconcile (desktop `reconcile_document`): re-read and compare the
   * on-disk hash — a clean document silently picks up the new disk state; a
   * dirty one keeps its buffer and surfaces "changed on disk".
   */
  reconcile(): void {
    if (this.#phase.kind === "loading" || this.#phase.kind === "saving" || this.#disposed) {
      return;
    }
    const generation = this.#generation;
    void this.#client
      .readFile(this.path)
      .then((file) => {
        if (this.#disposed || this.#generation !== generation) {
          return;
        }
        const diskHash = file.contentHash ?? null;
        if (diskHash !== null && diskHash === this.#savedHash) {
          return;
        }
        if (this.isDirty()) {
          if (this.#phase.kind === "ready" || this.#phase.kind === "saveFailed") {
            this.#phase = { kind: "externallyModified", diskHash };
            this.#commit();
          }
          return;
        }
        this.#setLoaded(file);
      })
      .catch(() => {
        // A reconcile read rides the next change frame if it failed.
      });
  }

  /** Watch: the open file was removed on disk (buffer preserved). */
  markDeleted(): void {
    if (this.#disposed) {
      return;
    }
    this.#pendingSave = null;
    this.#phase = { kind: "deletedOnDisk" };
    this.#commit();
  }

  /** Watch: a path matching this document reappeared; restore it from disk. */
  restore(): void {
    if (this.#phase.kind === "deletedOnDisk") {
      this.load();
    }
  }

  isDirty(): boolean {
    return this.#revision !== this.#savedRevision;
  }

  isEditable(): boolean {
    return (
      this.#phase.kind !== "loading" &&
      this.#phase.kind !== "readOnly" &&
      this.#phase.kind !== "error" &&
      this.#file !== null &&
      this.#file.text !== null &&
      this.#file.text !== undefined &&
      this.#savedHash !== null &&
      writableEncoding(this.#file.encoding) !== null &&
      writableLineEnding(this.#file.lineEnding) !== null
    );
  }

  /** Desktop `can_save`: editable, dirty, idle phase, no save in flight. */
  canSave(): boolean {
    return (
      this.isEditable() &&
      this.isDirty() &&
      this.#pendingSave === null &&
      (this.#phase.kind === "ready" || this.#phase.kind === "saveFailed")
    );
  }

  #accepts(generation: number): boolean {
    return !this.#disposed && generation === this.#generation;
  }

  /** Desktop `set_loaded`: fresh disk state, clean buffer, read-only derived. */
  #setLoaded(file: WorkspaceFileText): void {
    const readOnly = fileReadOnlyReason(file);
    this.#file = file;
    this.#text = file.text ?? "";
    this.#savedHash = file.contentHash ?? null;
    this.#pendingSave = null;
    this.#phase = readOnly !== null ? { kind: "readOnly", reason: readOnly } : { kind: "ready" };
    // A programmatic reload lands clean: the saved revision meets the current
    // one, so reloaded content is never dirty (desktop apply_external_reload).
    this.#savedRevision = this.#revision;
    this.#commit();
  }

  #takeSnapshot(): FileDocumentSnapshot {
    return {
      phase: this.#phase,
      text: this.#text,
      file: this.#file,
      editable: this.isEditable(),
      dirty: this.isDirty(),
    };
  }

  #commit(): void {
    this.#snapshot = this.#takeSnapshot();
    for (const listener of this.#listeners) {
      listener();
    }
  }
}
