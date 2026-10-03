import { Icon } from "@roboco/icons";
import { RowTile } from "../components/settings-widgets";
import {
  RbSelect,
  RbSelectItem,
  RbSelectPopup,
  RbSelectPortal,
  RbSelectPositioner,
  RbSelectTrigger,
} from "../components/base/select";
import { RbSwitch } from "../components/base/switch";
import { uiSettings, useUiSettings } from "../state/ui-settings";

/**
 * Files settings (desktop settings/files.rs parity): local preferences for
 * workspace-file editing — autosave and its delay dropdown, word wrap, and
 * show-all. Every control commits immediately through ticket 03's settings
 * store; the live-apply side (file surfaces reading the store) already
 * rides `useUiSettings` in the viewers. The editor font size moved to
 * Appearance when the code font became its own setting (upstream #374).
 */

/** `DELAY_OPTIONS` (files.rs:8) — the autosave delay choices, in ms. */
const DELAY_OPTIONS: readonly number[] = [300, 600, 900, 1_500, 3_000];

function delayLabel(ms: number): string {
  return ms >= 1_000 ? `${ms / 1_000} s` : `${ms} ms`;
}

export function FilesSettingsPage() {
  const settings = useUiSettings();
  return (
    <div className="settings-page">
      <h1 className="settings-title">Files</h1>
      <p className="settings-subtitle">Control how workspace files are displayed and saved while you edit.</p>

      <section className="settings-card">
        <div className="settings-row settings-files-row settings-files-nosep">
          <RowTile icon="folder" />
          <div className="settings-row-main">
            <span className="settings-row-title">Autosave</span>
            <span className="settings-row-meta">Save edited workspace files to disk automatically.</span>
          </div>
          <RbSwitch
            checked={settings.filesAutosaveEnabled}
            onCheckedChange={() => uiSettings.updateImmediate({ filesAutosaveEnabled: !settings.filesAutosaveEnabled })}
            aria-label="Autosave"
          />
        </div>
        {settings.filesAutosaveEnabled && (
          <div className="settings-row settings-files-row settings-files-nosep">
            <RowTile icon="folder" />
            <div className="settings-row-main">
              <span className="settings-row-title">Autosave delay</span>
              <span className="settings-row-meta">Save files after editing has been idle for this long.</span>
            </div>
            <DelaySelect
              value={settings.filesAutosaveDelayMs}
              ariaLabel="Autosave delay"
              onCommit={(ms) => uiSettings.updateImmediate({ filesAutosaveDelayMs: ms })}
            />
          </div>
        )}
        <div className="settings-row settings-files-row settings-files-nosep">
          <RowTile icon="list" />
          <div className="settings-row-main">
            <span className="settings-row-title">Word wrap</span>
            <span className="settings-row-meta">Wrap long lines in every workspace file.</span>
          </div>
          <RbSwitch
            checked={settings.filesWordWrap}
            onCheckedChange={() => uiSettings.updateImmediate({ filesWordWrap: !settings.filesWordWrap })}
            aria-label="Word wrap"
          />
        </div>
        <div className="settings-row">
          <RowTile icon="eye" />
          <div className="settings-row-main">
            <span className="settings-row-title">Show hidden and ignored files</span>
            <span className="settings-row-meta">Include hidden and ignored files in every file tree.</span>
          </div>
          <RbSwitch
            checked={settings.filesShowAll}
            onCheckedChange={() => uiSettings.updateImmediate({ filesShowAll: !settings.filesShowAll })}
            aria-label="Show hidden and ignored files"
          />
        </div>
      </section>
    </div>
  );
}

/**
 * The autosave-delay dropdown (files.rs:88-112): the in-settings `RbSelect`
 * precedent (settings-appearance's font pickers) at the desktop's 112px —
 * the row's layout contract caps the trigger there (`.delay-trigger`).
 */
function DelaySelect(props: {
  readonly value: number;
  readonly ariaLabel: string;
  readonly onCommit: (ms: number) => void;
}) {
  return (
    <RbSelect<number>
      value={props.value}
      onValueChange={(next) => {
        if (next !== null) {
          props.onCommit(next);
        }
      }}
      overlaySource="settings-files-autosave-delay"
    >
      <RbSelectTrigger className="settings-select-trigger delay-trigger" aria-label={props.ariaLabel}>
        <span className="settings-select-label">{delayLabel(props.value)}</span>
        <Icon name="altArrowDown" size={14} className="settings-select-caret" />
      </RbSelectTrigger>
      <RbSelectPortal>
        <RbSelectPositioner>
          <RbSelectPopup className="popover-card settings-select-menu delay-menu">
            {DELAY_OPTIONS.map((ms) => (
              <RbSelectItem key={ms} value={ms} className="settings-select-item">
                <span className="settings-select-item-label">{delayLabel(ms)}</span>
                <span className="settings-select-check">
                  {ms === props.value && <Icon name="check" size={14} />}
                </span>
              </RbSelectItem>
            ))}
          </RbSelectPopup>
        </RbSelectPositioner>
      </RbSelectPortal>
    </RbSelect>
  );
}
