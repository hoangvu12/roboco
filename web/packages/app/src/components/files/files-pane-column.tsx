import { useEffect, useMemo, useState } from "react";
import { useEngineSession } from "../../state/session-provider";
import { useFleetSnapshot } from "../../state/fleet";
import { WorkspaceFilesClient } from "../../lib/files-client";
import { FileTreeModel } from "../../lib/file-tree";
import { FileTreePanel } from "./file-tree-panel";
import { rightPaneStore, type ChatPaneState } from "../../state/right-pane";
import { uiSettings } from "../../state/ui-settings";

/**
 * The docked explorer column — the desktop's `render_files_panel`
 * (shell/files_panel.rs): one portion of the one right pane, independent of
 * the surface host. The pane toggle drives only the host; this column opens
 * from its own toggle and shares the pane's height, hairline-separated on
 * its left edge.
 *
 * The tree model lives as long as the (session, chat) pair — mounting is
 * keyed to the pane's filesOpen flag but the model itself stays cached so a
 * toggle never loses the tree. Reveal requests from editor toolbars
 * (`RevealFile`) route through the pane store's pending reveal: dock, then
 * reveal in THIS tree (the editor surfaces carry no tree of their own).
 */
export function FilesPaneColumn({
  chatId,
  pane,
}: {
  chatId: string;
  pane: ChatPaneState;
}) {
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

  // Consume a pending reveal on the docked tree.
  useSyncExternalStoreConsume(chatId, model);

  // The desktop's projectless root label (files/mod.rs, 03b67beb): a chat
  // with no space roots its tree at the chat's own directory, called out so
  // the browsing boundary is visible.
  const snapshot = useFleetSnapshot();
  const projectlessRoot = useMemo(() => {
    if (!snapshot.chats.loaded) {
      return null;
    }
    const chat = snapshot.chats.rows.find((row) => row.id === chatId);
    if (chat === undefined || chat.spaceId != null) {
      return null;
    }
    const device =
      snapshot.devices.rows.find((row) => row.id === chat.deviceId)?.name ?? chat.deviceId;
    return `Files in ${chat.cwd ?? "~"} · ${device}`;
  }, [snapshot, chatId]);

  if (!pane.filesOpen) {
    return null;
  }
  return (
    <aside className="files-pane-column" aria-label="Files">
      {projectlessRoot !== null && (
        <div className="files-projectless-root" title={projectlessRoot}>
          {projectlessRoot}
        </div>
      )}
      {model !== null && client !== null && session !== null ? (
        <FileTreePanel
          model={model}
          client={client}
          onOpenFile={(path) => rightPaneStore.addFileSurface(chatId, path)}
          gitStatus={(handlers) => client.watchGitStatus(session.client, handlers)}
        />
      ) : (
        <div className="files-tree-panel" />
      )}
    </aside>
  );
}

/** Re-render on store bumps and consume the pending reveal when it lands. */
function useSyncExternalStoreConsume(chatId: string, model: FileTreeModel | null): void {
  const [reveal, setReveal] = useState(rightPaneStore.pendingFilesReveal());
  useEffect(() => {
    return rightPaneStore.subscribe(() => {
      setReveal(rightPaneStore.pendingFilesReveal());
    });
  }, []);
  useEffect(() => {
    if (reveal === null || reveal.chatId !== chatId || model === null) {
      return;
    }
    rightPaneStore.clearFilesReveal();
    void model.revealInTree(reveal.path);
  }, [reveal, chatId, model]);
}
