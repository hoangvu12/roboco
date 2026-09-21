import { useNewThreadBackground } from "../state/appearance";
import { NewThreadBackground } from "../components/new-thread-background";

/**
 * The blank-canvas route's hero (ticket 15) — the desktop's
 * `new_thread_background` layer (shell.rs:857-914), mounted as the FIRST
 * child of the conversation column whenever no chat is selected (or the
 * dock is still dissolving one away). The composer itself is the SAME
 * persistent entity the chat route renders, vertically re-anchored by the
 * dock — see `routes/chat-page.tsx`'s `ConversationPage`, which hosts both
 * routes so the composer is never remounted.
 *
 * The hero is deliberately OUTSIDE the transcript's edge-fade scope: it
 * paints under the overlaid titlebar instead of going transparent across
 * the titlebar band, and uses the full conversation-canvas width even
 * while the right pane clips it — navigation must never rescale the
 * artwork.
 */
export function NewThreadCanvas({
  viewportHeight,
  heroWidth,
  dissolve,
}: {
  readonly viewportHeight: number;
  readonly heroWidth: number;
  /** The dock's `dissolve` channel: 0 = the hero, 1 = the established thread. */
  readonly dissolve: number;
}) {
  const background = useNewThreadBackground();
  const artwork =
    background.url === null
      ? null
      : { url: background.url, id: background.url as string | number };
  return (
    <NewThreadBackground
      artwork={artwork}
      viewportHeight={viewportHeight}
      heroWidth={heroWidth}
      dissolve={dissolve}
      effect={background.effect}
    />
  );
}
