import { useEffect, useMemo, useState } from "react";
import { useEngineSession } from "../state/session-provider";
import { WorkspaceFilesClient } from "../lib/files-client";
import { FileTreeModel } from "../lib/file-tree";
import { FileTreePanel } from "../components/files/file-tree-panel";
import { rightPaneStore } from "../state/right-pane";
import { uiSettings } from "../state/ui-settings";

/**
 * The right pane's Files surface — the desktop's browser-mode `tree_pane`
 * (mod.rs render_header + watch banner + tree). The pane is chat-scoped
 * chrome with no URL of its own, and its target is the **active chat's
 * checkout** (`{ chatId }`, the desktop's `FilesRequestContext::for_chat`),
 * never a space picked independently of the open chat: a chat running in a
 * worktree sees the worktree's files.
 *
 * Opening a file from the tree or the search asks the pane host (ticket 07)
 * for that file's own tab (`rightPaneStore.addFileSurface`) — the desktop's
 * `FilesEvent::OpenFile` → `shell.rs::add_file_surface`. The viewer itself
 * is ticket 25's file-surface body.
 *
 * The tree model (and its workspace change watch) lives as long as the
 * (session, chat) pair it browses; `filesShowAll` seeds the model and every
 * open Files surface re-applies the stored preference when it changes (the
 * desktop shell's `set_show_all_files` fan-out).
 */
export function FilesSurface({ chatId }: { chatId: string }) {
  const session = useEngineSession();

  const client = useMemo(
    () => (session !== null ? new WorkspaceFilesClient(session.client, { chatId }) : null),
    [session, chatId],
  );

  const [model, setModel] = useState<FileTreeModel | null>(null);

  useEffect(() => {
    if (client === null || session === null) {
      setModel(null);
      return;
    }
    const created = new FileTreeModel({
      client,
      watch: (handlers) => client.watchFiles(session.client, handlers),
      includeIgnored: uiSettings.getSnapshot().filesShowAll,
    });
    created.start();
    setModel(created);
    return () => {
      created.dispose();
      setModel((current) => (current === created ? null : current));
    };
  }, [client, session]);

  return (
    <div className="files-page">
      {model !== null && client !== null ? (
        <FileTreePanel
          model={model}
          client={client}
          onOpenFile={(path) => rightPaneStore.addFileSurface(chatId, path)}
        />
      ) : (
        <div className="files-tree-panel" />
      )}
    </div>
  );
}
