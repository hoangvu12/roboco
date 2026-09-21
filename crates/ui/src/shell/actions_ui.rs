//! Shell glue for host-owned project Actions. The worktree-setup terminal
//! attaches to the chat's bottom drawer; the titlebar control + editor
//! surface arrives with the ticket-27 port.

use super::*;

use roboco_proto::ProjectActionRun;

impl Shell {
    pub(super) fn attach_worktree_setup(
        &mut self,
        chat_id: String,
        setup_action: Option<ProjectActionRun>,
        setup_error: Option<String>,
        target_device_id: Option<String>,
        cx: &mut Context<Self>,
    ) {
        if let Some(error) = setup_error {
            self.sidebar_notice = Some(format!("Setup action failed: {error}").into());
        }
        let Some(run) = setup_action else {
            cx.notify();
            return;
        };

        let panel = self.terminal_panel(cx);
        let title = format!("{} (setup)", run.action_name);
        let tab = panel.update(cx, |panel, cx| {
            panel.reserve_tab_for_chat(chat_id.clone(), title, cx)
        });
        let attached = panel.update(cx, |panel, cx| {
            panel.attach_reserved_session(&chat_id, tab, run.terminal, target_device_id, cx)
        });
        if !attached {
            self.sidebar_notice =
                Some("Setup action started, but its terminal could not be attached".into());
        }

        let selected = self.active_chat == chat_id;
        self.panels
            .update(&chat_id, |panels| panels.terminal_open = true);
        if selected {
            self.terminal_tween = None;
            self.terminal_tween_task = None;
            panel.update(cx, |panel, cx| panel.set_open(true, cx));
            panel.update(cx, |panel, cx| panel.select_tab_by_key(tab, cx));
        }
        cx.notify();
    }
}
