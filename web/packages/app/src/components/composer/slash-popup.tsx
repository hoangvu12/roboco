import { useEffect, useRef, type ReactNode } from "react";
import { Icon } from "@roboco/icons";
import type { InvocationRow } from "../../lib/invocations";
import { skillDisplayName, slashDescription } from "../../lib/invocations";
import { rowUnavailable } from "../../lib/native";
import { MenuRow } from "../ui/MenuRows";
import { MenuScrollbar } from "../ui/Scrollbar";
import { SkeletonRows } from "../ui/Skeleton";
import type { CompletionToken } from "../../lib/mentions";
import { CompletionPopup } from "./mention-popup";

/**
 * The `/`+`$` invocation completion popup — `render_slash_popup`
 * (composer.rs:7059-7216): the mention popup's exact frame, card, scroll
 * host and height budget, with invocation rows instead of file rows.
 * Commands and skills share focus and keyboard handling; a catalog warning
 * renders ABOVE the rows (partial discovery is useful, and stays visible);
 * a warm cache keeps its rows on screen while a fresh open re-probes.
 */

export interface SlashPopupProps {
  readonly token: CompletionToken;
  /** The cached invocation rows for the current catalog context. */
  readonly rows: readonly InvocationRow[];
  /** Ranked indices into `rows` for the current query. */
  readonly filtered: readonly number[];
  readonly active: number | null;
  readonly loading: boolean;
  /** A catalog warning (partial discovery) or a hard error, already translated. */
  readonly error: string | null;
  /** `true` while a `$` token is open (skill wording for the empty state). */
  readonly skill: boolean;
  /** `false` only for a `$` token on a provider that does not advertise skills. */
  readonly supported: boolean;
  /** The harness's `separate_from_slash` preference (empty-state wording). */
  readonly separateFromSlash: boolean;
  /** The native conversation has active work: `idleOnly` rows show unavailable. */
  readonly busy?: boolean;
  /** Clicking (or Enter/Tab on) row `ix` of the FILTERED list accepts it. */
  readonly onAccept: (rowIx: number) => void;
  readonly onDismiss: () => void;
  readonly onCardMouseDown: () => void;
}

/** The slash popup: warning / skeleton / empty / rows (composer.rs:7071-7193). */
export function SlashPopup(props: SlashPopupProps) {
  const listRef = useRef<HTMLDivElement | null>(null);
  // A fresh query/reopen restarts the row stack at the top (composer.rs:6985).
  useEffect(() => {
    if (listRef.current !== null) {
      listRef.current.scrollTop = 0;
    }
  }, [props.filtered]);

  let body: ReactNode;
  if (props.loading && props.rows.length === 0) {
    body = <SkeletonRows count={3} />;
  } else if (props.filtered.length === 0 && props.error === null) {
    body = (
      <div className="composer-completion-empty">
        {props.rows.length === 0
          ? props.skill
            ? props.supported
              ? "No skills available for this project"
              : "This agent does not advertise skills"
            : props.separateFromSlash
              ? "No slash commands available in this integration"
              : "No commands or skills available"
          : props.skill
            ? "No matching skills"
            : props.separateFromSlash
              ? "No matching commands"
              : "No matching commands or skills"}
      </div>
    );
  } else {
    body = (
      <div className="composer-completion-host">
        <div className="composer-completion-list" ref={listRef}>
          {props.filtered.map((rowIx, listIx) => {
            const row = props.rows[rowIx];
            if (row === undefined) {
              return null;
            }
            return (
              <SlashRow
                key={`${row.invocation.kind}-${row.name}`}
                row={row}
                unavailable={rowUnavailable(row, props.busy === true)}
                selected={props.active === listIx}
                onAccept={() => props.onAccept(listIx)}
              />
            );
          })}
        </div>
        <MenuScrollbar scrollRef={listRef} />
      </div>
    );
  }

  return (
    <CompletionPopup
      onDismiss={props.onDismiss}
      onCardMouseDown={props.onCardMouseDown}
      ariaLabel={props.skill ? "Skills" : "Slash commands"}
    >
      {props.error !== null && (
        <div className="composer-completion-error" role="alert">
          {props.error}
        </div>
      )}
      {body}
    </CompletionPopup>
  );
}

/** One invocation row (composer.rs:7146-7181): the 14px glyph (command for
 * `/`, widget for `$`), the human-readable name (skills Title-case their
 * words, commands show `/{name}`), and the description (truncated). */
function SlashRow({
  row,
  unavailable,
  selected,
  onAccept,
}: {
  readonly row: InvocationRow;
  readonly unavailable: string | null;
  readonly selected: boolean;
  readonly onAccept: () => void;
}) {
  const skill = row.invocation.kind === "skill";
  const name = skill ? skillDisplayName(row.name) : `/${row.name}`;
  return (
    <MenuRow
      fadeKey={`slash-result-${row.invocation.kind}-${row.name}`}
      selected={selected}
      onClick={unavailable === null ? onAccept : () => {}}
      className="composer-completion-row"
      aria-disabled={unavailable !== null ? true : undefined}
      title={unavailable ?? undefined}
      style={unavailable !== null ? { opacity: 0.55 } : undefined}
    >
      <span className="composer-completion-row-icon">
        <Icon
          name={skill ? "widget" : "command"}
          size={14}
          className={skill ? "slash-row-widget" : "slash-row-command"}
        />
      </span>
      <span className="slash-row-name">{name}</span>
      <span className="slash-row-description">{unavailable ?? slashDescription(row)}</span>
    </MenuRow>
  );
}
