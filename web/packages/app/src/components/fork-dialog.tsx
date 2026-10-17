import { memo } from "react";
import { RbDialogGlass } from "./base/dialog";
import { HarnessChip, harnessChipsEmptyText, ModelList } from "./ui/HarnessChoice";
import { forkDialogOffered, forkDialogStore, useForkDialog } from "../state/fork-dialog";
import { dialogError, dialogLoading, dialogRows } from "../lib/harness-choice";

/**
 * The fork dialog (t3-orchestration-v2 tickets 21+22) — the web peer of
 * the desktop's `Shell::render_fork_dialog` (`shell/fork_dialog.rs`):
 * the harness and model a fork runs on, chosen when the fork starts.
 *
 * Both fork entry points open it: the files panel's classic "Fork this
 * chat" button and the transcript's "Fork from here" hover action (the
 * ticket-21 boundary rides the spec, flipping the title/body). It
 * defaults to the source's own harness and model (spec story 61), so
 * confirming without touching anything forks exactly as before; picking
 * another harness makes the fork a cross-harness one — the engine serves
 * it with the budgeted history instead of a native session fork, and the
 * note under the models says so.
 *
 * Headless state in `state/fork-dialog.ts` (the add-space palette's
 * store shape); this layer is the only mount — `AppShell` renders it once,
 * over everything, and the entry points call `forkDialogStore.open()`.
 * The dialog is deliberately local to the fork flow: the composer's own
 * harness/model picker (and its unlock in ticket 24's one-way switch)
 * stays untouched, so the two features never share state.
 */

/** The mounted fork dialog layer — null while closed. */
export const ForkDialogLayer = memo(function ForkDialogLayer() {
  const state = useForkDialog();
  if (!state.open || state.spec === null) {
    return null;
  }
  const boundarySet = state.spec.boundary !== null;
  const sourceHarness = state.spec.source.config?.harness ?? null;
  const offered = forkDialogOffered(state);
  const selectedHarness = state.harness;
  const selectedModel = state.model;
  const modelLoad = selectedHarness === null ? "idle" : state.models.get(selectedHarness) ?? "idle";
  const crossHarness = sourceHarness !== null && selectedHarness !== null && sourceHarness !== selectedHarness;

  return (
    <RbDialogGlass
      open={state.open}
      onOpenChange={(next) => {
        if (!next) {
          forkDialogStore.close();
        }
      }}
      ariaLabel={boundarySet ? "Fork from here" : "Fork this chat"}
      overlaySource="fork-dialog"
      cardClassName="fork-dialog-card"
    >
      <div className="fork-dialog-body">
        <div className="fork-dialog-title">{boundarySet ? "Fork from here" : "Fork this chat"}</div>
        <div className="fork-dialog-body-text">
          {boundarySet
            ? "A new side chat continues from this reply. Files are not rewound."
            : "A new side chat continues from the latest reply. Files are not rewound."}
        </div>

        <div className="harness-choice-heading">Harness</div>
        <div className="harness-choice-chips">
          {offered.map((descriptor) => (
            <HarnessChip
              key={descriptor.id}
              descriptor={descriptor}
              selected={selectedHarness === descriptor.id}
              onPick={() => forkDialogStore.pickHarness(descriptor.id)}
            />
          ))}
          {offered.length === 0 && (
            <span className="harness-choice-empty">
              {harnessChipsEmptyText(state.harnesses, "No harnesses are available on this device.")}
            </span>
          )}
        </div>

        <div className="harness-choice-heading">Model</div>
        <ModelList
          models={dialogRows(modelLoad)}
          error={dialogError(modelLoad)}
          loading={dialogLoading(modelLoad)}
          selectedModel={selectedModel}
          onPick={(model) => forkDialogStore.pickModel(model)}
        />

        {crossHarness && (
          <div className="harness-choice-note">
            A different harness starts fresh and receives a size-limited transcript of this chat,
            not its full transcript.
          </div>
        )}
        {state.error !== null && <div className="harness-choice-error">{state.error}</div>}

        <div className="fork-dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={() => forkDialogStore.close()}>
            Cancel
          </button>
          <button
            type="button"
            className="btn btn-solid"
            disabled={state.creating || selectedHarness === null}
            onClick={() => forkDialogStore.confirm()}
          >
            {state.creating ? "Forking…" : "Fork"}
          </button>
        </div>
      </div>
    </RbDialogGlass>
  );
});
