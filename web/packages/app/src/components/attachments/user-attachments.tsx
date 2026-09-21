import { useEffect, useState } from "react";
import type { EngineClient } from "@roboco/engine-client";
import {
  beginAttachmentLoad,
  loadAttachment,
  useAttachmentImage,
  type AttachmentImageSnapshot,
} from "../../state/attachment-cache";
import { bytesToImageDataUrl, type UserImageAttachment } from "../../lib/attachments";

/**
 * The user-row's attachment strip — the web port of the desktop's own
 * user-bubble 112×80 thumbnail strip (`transcript.rs` UserKind). Renders
 * above (or instead of) the bubble; the row's text-only path remains.
 *
 * Loads go through the module-level cache in
 * `../../state/attachment-cache.ts` so a re-render of the same row never
 * re-fetches. The cache seeds after a successful send (composer.tsx) so
 * the user's own bubble never round-trips.
 */

interface UserAttachmentsProps {
  readonly client: EngineClient;
  readonly deviceId: string | null;
  readonly attachments: readonly UserImageAttachment[];
}

export function UserAttachments({ client, deviceId, attachments }: UserAttachmentsProps) {
  if (attachments.length === 0) {
    return null;
  }
  return (
    <div className="user-attachments">
      {attachments.map((att) => (
        <UserAttachmentThumb
          key={att.id}
          client={client}
          deviceId={deviceId}
          attachment={att}
        />
      ))}
    </div>
  );
}

function UserAttachmentThumb({
  client,
  deviceId,
  attachment,
}: {
  client: EngineClient;
  deviceId: string | null;
  attachment: UserImageAttachment;
}) {
  // We don't know the deviceId for older chats, and a single-bubble render
  // with no device is harmless — the row paints a skeleton, the load is
  // skipped, the thumbnail never lands. In practice the engine always
  // includes the deviceId.
  const snapshot = useAttachmentImage(deviceId, attachment.path);
  const [lightbox, setLightbox] = useState<{
    name: string;
    src: string;
  } | null>(null);

  useEffect(() => {
    if (deviceId === null) {
      return;
    }
    if (snapshot.state !== "loading") {
      return;
    }
    void loadAttachment(client, deviceId, attachment.path);
  }, [client, deviceId, attachment.path, snapshot.state]);

  const onOpen = (next: { name: string; src: string } | null): void => {
    setLightbox(next);
  };

  if (lightbox !== null) {
    return (
      <div
        className="user-attachments-lightbox"
        role="dialog"
        aria-modal="true"
        onClick={() => onOpen(null)}
      >
        <img
          className="user-attachments-lightbox-img"
          src={lightbox.src}
          alt={lightbox.name}
        />
        <span className="user-attachments-lightbox-name">{lightbox.name}</span>
      </div>
    );
  }

  return (
    <button
      type="button"
      className="user-attachments-thumb"
      title={attachment.name}
      onClick={() => {
        if (snapshot.state === "loaded" && snapshot.image !== null) {
          onOpen({
            name: snapshot.image.name,
            src: bytesToImageDataUrl(snapshot.image.mime, snapshot.image.bytes),
          });
        }
      }}
    >
      <ThumbContent snapshot={snapshot} name={attachment.name} />
    </button>
  );
}

function ThumbContent({
  snapshot,
  name,
}: {
  snapshot: AttachmentImageSnapshot;
  name: string;
}) {
  if (snapshot.state === "loaded" && snapshot.image !== null) {
    return (
      <img
        className="user-attachments-thumb-img"
        src={bytesToImageDataUrl(snapshot.image.mime, snapshot.image.bytes)}
        alt={name}
        draggable={false}
      />
    );
  }
  if (snapshot.state === "error") {
    return (
      <div className="user-attachments-thumb-fail" aria-label="Failed to load image">
        <span aria-hidden>!</span>
        <span className="user-attachments-thumb-fail-label">Could not load</span>
      </div>
    );
  }
  return <div className="user-attachments-thumb-skeleton" aria-label="Loading" />;
}

/** Test-only: a no-side-effect way to claim the load slot. */
export const __beginLoadForTests = beginAttachmentLoad;