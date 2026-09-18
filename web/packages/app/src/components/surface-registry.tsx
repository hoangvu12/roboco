import { useEffect } from "react";
import type { ReactNode } from "react";
import type { IconName } from "@roboco/icons";
import { rightPaneStore, type RightSurface } from "../state/right-pane";
import { ChangesSurface, ChangesToolbar } from "../routes/changes-page";
import { FilesSurface } from "../routes/files-page";
import { FileSurface } from "./files/file-viewer";
import { TerminalDock } from "../terminal/terminal-dock";
import { useTerminalStore } from "../terminal/store";
import { SurfacePicker, SurfaceStubBody } from "./surface-picker";

/**
 * The right pane's surface registry — the seam between the pane HOST (this
 * ticket) and the surface BODIES (Changes 22, Files 24/25, Terminal 26,
 * History 27, subagent transcript 19). Later tickets replace an entry's
 * `render` through `registerRightSurface` without touching the host.
 *
 * Contextual chrome (title, detail, icon, dirty) reads the backing entities
 * out of `state/right-pane.ts` — the peer of the desktop's
 * `right_surface_rows` walking `file_surfaces` / `diffs` / `subagent_tabs`.
 */

export interface SurfaceContext {
  readonly chatId: string;
}

export interface RightSurfaceEntry {
  readonly kind: RightSurface["kind"];
  readonly title: (s: RightSurface, ctx: SurfaceContext) => string;
  readonly detail?: (s: RightSurface, ctx: SurfaceContext) => string | null;
  readonly icon: (s: RightSurface, ctx: SurfaceContext) => IconName;
  readonly isDirty?: (s: RightSurface, ctx: SurfaceContext) => boolean;
  /** Default true; the picker is the one unclosable surface. */
  readonly isClosable?: (s: RightSurface) => boolean;
  /** The `surface_chrome::toolbar` row above the body (Diff surfaces). */
  readonly toolbar?: (s: RightSurface, ctx: SurfaceContext) => ReactNode;
  readonly render: (s: RightSurface, ctx: SurfaceContext) => ReactNode;
}

const entries = new Map<RightSurface["kind"], RightSurfaceEntry>();

/** Register (or replace) a surface kind's entry. */
export function registerRightSurface(entry: RightSurfaceEntry): void {
  entries.set(entry.kind, entry);
}

export function surfaceEntry(kind: RightSurface["kind"]): RightSurfaceEntry | undefined {
  return entries.get(kind);
}

/** The backing facts for a surface, straight from the entity maps. */
function facts(surface: RightSurface) {
  return rightPaneStore.describe(surface);
}

function titleOf(fallback: string): (s: RightSurface) => string {
  return (s) => facts(s)?.title ?? fallback;
}

/**
 * The pane's content for a surface — `render_right_pane`'s match. A surface
 * whose backing entity is gone renders the picker (`… else the picker`).
 */
export function renderRightSurface(surface: RightSurface, ctx: SurfaceContext): ReactNode {
  if (surface.kind !== "picker" && facts(surface) === null) {
    return <SurfacePicker chatId={ctx.chatId} />;
  }
  return entries.get(surface.kind)?.render(surface, ctx) ?? null;
}

/**
 * `surface_chrome::toolbar` (§3.27): the 38 px border-box row a Diff surface
 * mounts above its body. Its controls — the scope chips, ref selector,
 * split/wrap/fold-all — are ticket 22's `ChangesToolbar`, reading and
 * mutating the same per-surface state store the body renders from.
 */

/**
 * The Terminal surface. The PTY is minted HERE, by the surface mounting —
 * not by an effect beside the pane (gap B2): a surface with no live panel
 * can no longer open one.
 */
function TerminalSurface({ chatId }: { chatId: string }) {
  const terminalStore = useTerminalStore();
  useEffect(() => {
    terminalStore.open(chatId);
  }, [terminalStore, chatId]);
  return <TerminalDock store={terminalStore} chatId={chatId} docked />;
}

let registered = false;

/** The stub + real bodies this ticket wires; later tickets re-register. */
function registerDefaults(): void {
  if (registered) {
    return;
  }
  registered = true;

  registerRightSurface({
    kind: "picker",
    title: () => "Picker",
    icon: () => "plus",
    isClosable: () => false,
    render: (_s, ctx) => <SurfacePicker chatId={ctx.chatId} />,
  });

  registerRightSurface({
    kind: "files",
    title: titleOf("Files"),
    icon: () => "folderWithFiles",
    render: (_s, ctx) => <FilesSurface chatId={ctx.chatId} />,
  });

  registerRightSurface({
    kind: "file",
    title: titleOf("File"),
    detail: (s) => facts(s)?.detail ?? null,
    // The tab strip's IconName slot is monochrome by design; the
    // polychrome file-type icon lives in the surface's breadcrumb toolbar
    // (`FileIcon`, ticket 24's manifest).
    icon: () => "document",
    render: (s, ctx) => (s.kind === "file" ? <FileSurface chatId={ctx.chatId} surfaceId={s.id} /> : null),
  });

  registerRightSurface({
    kind: "diff",
    title: titleOf("Diffs"),
    // `git-branch` when that `Changes` `is_history()`, else `list`.
    icon: (s) => (facts(s)?.isHistory === true ? "gitBranch" : "list"),
    toolbar: (s, ctx) => (s.kind === "diff" ? <ChangesToolbar chatId={ctx.chatId} surfaceId={s.id} /> : null),
    render: (s, ctx) => (s.kind === "diff" ? <ChangesSurface chatId={ctx.chatId} surfaceId={s.id} /> : null),
  });

  registerRightSurface({
    kind: "terminal",
    title: titleOf("Terminal"),
    icon: () => "terminal",
    render: (_s, ctx) => <TerminalSurface chatId={ctx.chatId} />,
  });

  registerRightSurface({
    kind: "subagent",
    title: titleOf("Subagent"),
    icon: () => "bot",
    // The read-only transcript + its jump pill is ticket 19's.
    render: () => <SurfaceStubBody label="Subagent" />,
  });
}

registerDefaults();
