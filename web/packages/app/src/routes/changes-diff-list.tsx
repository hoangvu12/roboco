import { useCallback, useEffect, useMemo, useRef } from "react";
import { Icon } from "@roboco/icons";
import { CodeView, type CodeViewHandle, type CodeViewItem, type CodeViewReactOptions } from "@pierre/diffs/react";
import type { FileDiffMetadata, DiffLineAnnotation, LineAnnotation, SelectedLineRange } from "@pierre/diffs";
import { reviewCommentStore, type DiffCommentDraft } from "../state/review-comments";
import { useUiSettings } from "../state/ui-settings";
import { changesSurfaceStore } from "../state/changes-surface";
import type { ReviewComment } from "../lib/review-comments";
import { diffAdderAnchor, diffCodeItems, diffCommentAnnotations, fileDiffNotices, type DiffCommentAnnotationData } from "../lib/changes-diff";
import { robocoDiffsThemes } from "../lib/pierre-theme";
import { diffLineHeight, diffTextSize } from "../lib/typography";
import type { FileFold } from "../lib/diff";
import type { Appearance } from "@roboco/theme";
import { CommentCard } from "../components/review-comments/comment-card";
import { CommentDraft } from "../components/review-comments/comment-draft";

/**
 * The diff list itself — the library's mixed virtualized code/diff list
 * (ticket 02). Everything the surface chrome owns maps onto the library's
 * contract here: the surface store's layout/wrap become `diffStyle`/
 * `overflow` options, its fold map becomes per-item `collapsed` (+ the
 * version bump the controlled items need — `lib/changes-diff.ts`), and the
 * per-file fold chevron + notices compose INTO the library's default file
 * header through its header slots (light-DOM children the shadow tree
 * slots in), so the header keeps the library's look with our controls.
 *
 * Ticket 03 rides the library's review surfaces:
 *
 * - **Cards + draft** — the visible staged comments and the open draft map
 *   onto the items' `annotations` (side/line matching the comment store's
 *   anchors) and re-mount `CommentCard`/`CommentDraft` through the library's
 *   `renderAnnotation` slot: the shadow tree hosts one slot per annotated
 *   line, and our light-DOM cards portal into it at that line. Commit /
 *   cancel / edit / remove flow through the unchanged comment store — this
 *   component only re-renders what that store stages.
 * - **The adder** — the library's built-in gutter utility (a `+` on hovered
 *   lines) with `onGutterUtilityClick` resolving the clicked (file, side,
 *   line) into `openDiffDraft` (pre-rename `oldPath` included). Commit-
 *   pinned tabs don't offer it — a commit diff is a record, not a review
 *   surface (the old `renderAdder` gate).
 *
 * The scroll epoch (scope/base/layout/wrap switches) resets the list to the
 * top through the handle's `scrollTo` — the old viewer's code-plane reset.
 * Fonts/sizes/metrics and the diff add/delete colors map from web tokens on
 * the host class (`changes-code-host`, app.css); the code colors come from
 * the registered Roboco theme pair, whose `themeType` follows the resolved
 * appearance.
 *
 * This module is the Changes pane's LAZY entry: the page
 * (`routes/changes-page.tsx`) mounts it through `React.lazy` + `Suspense`,
 * so the library's CodeView rendering machinery loads on first diff body
 * mount (a brief "Preparing diff…" fallback — the pane's own loading state)
 * instead of riding the main bundle. The pane's chrome (banner, scope,
 * base, tools) and the parse (`parseDiffFiles`) stay eager on the page.
 */
interface ChangesDiffListProps {
  readonly chatId: string;
  readonly surfaceId: string;
  readonly files: readonly FileDiffMetadata[];
  readonly folds: ReadonlyMap<string, FileFold>;
  readonly layout: "unified" | "split";
  readonly wrap: boolean;
  readonly appearance: Appearance;
  readonly scrollEpoch: number;
  /** The visible staged diff comments (edited + file-sourced excluded). */
  readonly comments: readonly ReviewComment[];
  /** The open diff-side draft; its card replaces the edited comment's. */
  readonly draft: DiffCommentDraft | null;
  /** Whether the "+" adder is offered (commit-pinned tabs don't). */
  readonly offerAdder: boolean;
}

export function ChangesDiffList({
  chatId,
  surfaceId,
  files,
  folds,
  layout,
  wrap,
  appearance,
  scrollEpoch,
  comments,
  draft,
  offerAdder,
}: ChangesDiffListProps) {
  const codeFontSize = useUiSettings().codeFontSize;
  // The annotation arrays derive memoized — same inputs (files, staged set,
  // draft) → same arrays, so unrelated re-renders keep the item versions
  // (and the library's annotation identity comparison) quiet.
  const annotationsByFile = useMemo(
    () => diffCommentAnnotations(files, comments, draft),
    [files, comments, draft],
  );
  const items = useMemo(
    () => diffCodeItems(files, folds, annotationsByFile),
    [files, folds, annotationsByFile],
  );
  // The adder's click handler resolves the library's range into the store's
  // draft anchor; identity follows only (chat, layout) so typing in a draft
  // never churns the library's options comparison.
  const onGutterUtilityClick = useCallback(
    (range: SelectedLineRange, context: { item: CodeViewItem<DiffCommentAnnotationData> }) => {
      const item = context.item;
      if (item.type !== "diff") {
        return;
      }
      const anchor = diffAdderAnchor(item.fileDiff, layout, range);
      if (anchor === null) {
        return;
      }
      reviewCommentStore.openDiffDraft(chatId, anchor);
    },
    [chatId, layout],
  );
  const options = useMemo<CodeViewReactOptions<DiffCommentAnnotationData, undefined>>(
    () => ({
      theme: robocoDiffsThemes(),
      themeType: appearance,
      diffStyle: layout === "split" ? "split" : "unified",
      overflow: wrap ? "wrap" : "scroll",
      stickyHeaders: true,
      // The library's built-in "+" gutter affordance (hover a line, click,
      // `onGutterUtilityClick` fires with the clicked side + line).
      enableGutterUtility: offerAdder,
      ...(offerAdder ? { onGutterUtilityClick } : {}),
    }),
    [appearance, layout, wrap, offerAdder, onGutterUtilityClick],
  );
  // The annotation card: the metadata identifies WHICH staged comment (or
  // the draft) anchors here; the live object resolves from the CURRENT
  // staged set — the callback identity moves with (chat, set, draft), which
  // is exactly what re-renders the cards through the library's slot portal.
  const renderAnnotation = useCallback(
    (annotation: LineAnnotation<DiffCommentAnnotationData> | DiffLineAnnotation<DiffCommentAnnotationData>) => {
      const data = annotation.metadata;
      if (data.kind === "draft") {
        if (draft === null) {
          return null;
        }
        return (
          <CommentDraft
            // `draft_cite_path` (changes.rs:3291-3294): the header cites
            // the same path the staged card and the prompt bullet will —
            // the pre-rename path on the Old side.
            path={draft.side === "old" && draft.oldPath !== null ? draft.oldPath : draft.path}
            line={draft.line}
            body={draft.body}
            editing={draft.editingId !== null}
            onBody={(body) => reviewCommentStore.setDiffDraftBody(chatId, body)}
            onCancel={() => reviewCommentStore.cancelDiffDraft(chatId)}
            onCommit={() => reviewCommentStore.commitDiffDraft(chatId)}
          />
        );
      }
      const comment = comments.find((candidate) => candidate.id === data.id);
      if (comment === undefined) {
        return null;
      }
      return (
        <CommentCard
          comment={comment}
          onEdit={(id) => reviewCommentStore.editDiffComment(chatId, id)}
          onRemove={(id) => reviewCommentStore.removeComment(chatId, id)}
        />
      );
    },
    [chatId, comments, draft],
  );
  const handleRef = useRef<CodeViewHandle<DiffCommentAnnotationData, undefined> | null>(null);
  useEffect(() => {
    // The epoch moves exactly when the horizontal-extent inputs do; the
    // virtualized list restarts at the top instead of keeping a stale
    // anchor into differently-shaped content.
    handleRef.current?.scrollTo({ type: "position", position: 0, behavior: "instant" });
  }, [scrollEpoch]);
  const renderHeaderPrefix = useCallback(
    (item: CodeViewItem<DiffCommentAnnotationData>) => {
      if (item.type !== "diff") {
        return null;
      }
      return (
        <button
          type="button"
          className="changes-fold-toggle"
          aria-expanded={!item.collapsed}
          aria-label={item.collapsed ? "Expand file" : "Collapse file"}
          onClick={() => changesSurfaceStore.toggleFold(chatId, surfaceId, item.fileDiff.name)}
        >
          <Icon name={item.collapsed ? "altArrowRight" : "altArrowDown"} size={13} />
        </button>
      );
    },
    [chatId, surfaceId],
  );
  const renderHeaderMetadata = useCallback((item: CodeViewItem<DiffCommentAnnotationData>) => {
    if (item.type !== "diff") {
      return null;
    }
    const notices = fileDiffNotices(item.fileDiff);
    return notices.length === 0 ? null : (
      <span className="changes-file-notices">{notices.join("  ·  ")}</span>
    );
  }, []);
  return (
    <CodeView
      ref={handleRef}
      className="changes-code-host"
      style={{
        ["--diffs-font-size" as string]: `${diffTextSize(codeFontSize)}px`,
        ["--diffs-line-height" as string]: `${diffLineHeight(codeFontSize)}px`,
      }}
      items={items}
      options={options}
      renderHeaderPrefix={renderHeaderPrefix}
      renderHeaderMetadata={renderHeaderMetadata}
      renderAnnotation={renderAnnotation}
    />
  );
}

// The page's `React.lazy(() => import("./changes-diff-list"))` resolves
// this default; the named export stays for direct (non-lazy) test use.
export default ChangesDiffList;
