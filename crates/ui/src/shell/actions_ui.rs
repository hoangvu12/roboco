//! Shell glue for host-owned project Actions (titlebar control + setup
//! attach). The control/editor surface arrives with the ticket-27 port; this
//! file starts with the worktree-setup terminal attach.

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

        let panel = self.right_terminal_panel(cx);
        let title = format!("{} (setup)", run.action_name);
        let tab = panel.update(cx, |panel, cx| {
            panel.reserve_tab_for_chat(chat_id.clone(), title, cx)
        });
        self.right_tabs
            .entry(chat_id.clone())
            .or_default()
            .push(RightSurface::Terminal(tab));
        let attached = panel.update(cx, |panel, cx| {
            panel.attach_reserved_session(&chat_id, tab, run.terminal, target_device_id, cx)
        });
        if !attached {
            self.sidebar_notice =
                Some("Setup action started, but its terminal could not be attached".into());
        }

        if self.active_chat == chat_id {
            panel.update(cx, |panel, cx| panel.set_open(true, cx));
            if !self.right_pane_open(cx) {
                self.toggle_right_pane(cx);
            }
            self.set_right_active(RightSurface::Terminal(tab), cx);
        }
        cx.notify();
    }
}
