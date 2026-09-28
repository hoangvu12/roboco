import { Icon } from "@roboco/icons";
import { RbSwitch } from "../components/base/switch";
import { ThreadNamingCard } from "../components/thread-naming-card";
import {
  uiSettings,
  useUiSettings,
  type ComposerSendBehavior,
} from "../state/ui-settings";
import { isMacPlatform } from "../state/shortcuts";
import { modifierSendLabel } from "../lib/shortcuts-editor";

/**
 * General — the conversation-behavior page the desktop's modal redesign
 * added (upstream b782d043, tickets 22/26): the composer-send control,
 * compact mode, and the Escape behavior that used to live on the web's
 * Shortcuts and Appearance pages moved here onto their own page, plus the
 * thread-naming card. One section card carries the three behavior rows,
 * exactly `render_general_page`'s layout (shortcuts.rs:412-526); the
 * thread-naming card is its own card below.
 */

export function GeneralSettingsPage() {
  const settings = useUiSettings();
  const isMac = isMacPlatform();

  /** `set_composer_send_behavior`: write through, debounced like the desktop's. */
  function setSendBehavior(behavior: ComposerSendBehavior) {
    if (settings.composerSendBehavior === behavior) {
      return;
    }
    uiSettings.updateImmediate({ composerSendBehavior: behavior });
  }

  const behaviors: readonly { readonly behavior: ComposerSendBehavior; readonly label: string }[] = [
    { behavior: "enter", label: "Enter" },
    { behavior: "modEnter", label: modifierSendLabel(isMac) },
  ];

  return (
    <div className="settings-page">
      <h1 className="settings-title">General</h1>

      <section className="settings-card shortcuts-card-mt32">
        <div className="settings-row settings-row-min84">
          <div className="settings-row-main">
            <span className="settings-row-title">Send messages with</span>
          </div>
          <div className="send-behavior-control">
            {settings.composerSendBehavior !== "enter" && (
              <button
                type="button"
                className="send-behavior-reset"
                aria-label="Reset send behavior to Enter"
                onClick={() => setSendBehavior("enter")}
              >
                <Icon name="restart" size={13} />
              </button>
            )}
            <div className="segmented-control" role="radiogroup" aria-label="Send messages with">
              {behaviors.map(({ behavior, label }) => {
                const selected = settings.composerSendBehavior === behavior;
                return selected ? (
                  <span
                    key={behavior}
                    role="radio"
                    aria-checked={true}
                    className="segmented-option segmented-option-selected"
                  >
                    {label}
                  </span>
                ) : (
                  <button
                    key={behavior}
                    type="button"
                    role="radio"
                    aria-checked={false}
                    className="segmented-option"
                    onClick={() => setSendBehavior(behavior)}
                  >
                    {label}
                  </button>
                );
              })}
            </div>
          </div>
        </div>
        <div className="settings-row">
          <div className="settings-row-main">
            <span className="settings-row-title">Compact mode</span>
            <span className="shortcuts-row-description">Collapse thinking and tools.</span>
          </div>
          <RbSwitch
            checked={settings.transcriptCompactMode}
            onCheckedChange={() =>
              uiSettings.updateImmediate({ transcriptCompactMode: !settings.transcriptCompactMode })
            }
            aria-label="Compact mode"
          />
        </div>
        <div className="settings-row">
          <div className="settings-row-main">
            <span className="settings-row-title">Stop agent with Escape</span>
            <span className="shortcuts-row-description">When no dialog or menu is open.</span>
          </div>
          <RbSwitch
            checked={settings.escapeStopsActiveAgent}
            onCheckedChange={() =>
              uiSettings.updateImmediate({ escapeStopsActiveAgent: !settings.escapeStopsActiveAgent })
            }
            aria-label="Stop agent with Escape"
          />
        </div>
      </section>

      <ThreadNamingCard />
    </div>
  );
}
