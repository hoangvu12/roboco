//! ACP process ownership, including descendants that outlive their adapter.
use std::ops::{Deref, DerefMut};

use crate::process::{Child as ProcessChild, Command};

/// Creation-time process ownership. Unix puts the adapter in a private
/// process group so descendants reap with it and stamps crash ownership
/// (the kernel kills the child if we die); Windows spawns job-contained
/// (`process::windows`), which already owns the whole tree.
pub(crate) fn configure(command: &mut Command) {
    #[cfg(unix)]
    command.process_group(0);
    #[cfg(unix)]
    crate::process::dies_with_parent(command);
    for key in [
        "CLAUDECODE",
        "CLAUDE_CODE_ENTRYPOINT",
        "CLAUDE_CODE_SSE_PORT",
        "CLAUDE_AGENT_SDK_VERSION",
    ] {
        command.env_remove(key);
    }
}

pub(crate) struct Child {
    inner: ProcessChild,
    #[cfg(unix)]
    group: Option<i32>,
}

impl Child {
    pub(crate) fn new(inner: ProcessChild) -> Self {
        // The orphan reaper must never steal this child's exit status from
        // its owner: record it until it is gone (the registry self-prunes).
        #[cfg(unix)]
        crate::process::ownership::register(inner.id());
        Self {
            #[cfg(unix)]
            group: Some(-(inner.id().expect("newly spawned ACP child") as i32)),
            inner,
        }
    }

    pub(crate) async fn shutdown(&mut self, grace: std::time::Duration) {
        // Everything the agent started, taken while it is still attached:
        // a command the agent ran in its own session (Devin does) is not in
        // the agent's process group and would outlive the runtime.
        #[cfg(unix)]
        let tree = match (self.group, self.inner.id()) {
            (Some(_), Some(pid)) => crate::process::descendants(pid).await,
            _ => Vec::new(),
        };
        // Children FIRST, while the adapter still lives to reap them: a
        // simultaneous group TERM lets the adapter win the exit race, and
        // every child that dies after it is reparented to init as a zombie
        // (one `roboco mcp` bridge leaked per reaped session, measured).
        // Signaling the tree first means the adapter buries its own
        // children; only a child that outlives `grace` is killed past it.
        #[cfg(unix)]
        if !tree.is_empty() {
            crate::process::terminate_tree(&tree, grace).await;
        }
        #[cfg(unix)]
        if let Some(group) = self.group.take() {
            crate::send_signal(&group, crate::Signal::Term);
            // Pi uses detached bash groups and cleans them in its TERM handler.
            // Give descendants their grace even when the adapter exited first.
            let deadline = tokio::time::Instant::now() + grace;
            loop {
                let _ = self.inner.try_wait();
                // SAFETY: signal 0 only checks the private process group.
                if unsafe { libc::kill(group, 0) } != 0 {
                    break;
                }
                if tokio::time::Instant::now() >= deadline {
                    crate::send_signal(&group, crate::Signal::Kill);
                    break;
                }
                tokio::time::sleep(std::time::Duration::from_millis(10)).await;
            }
        }
        crate::shutdown_child(&mut self.inner, grace).await;
        // A second, cheap sweep for anything that respawned or appeared
        // between the snapshot and the adapter's death.
        #[cfg(unix)]
        if !tree.is_empty() {
            crate::process::terminate_tree(&tree, grace).await;
        }
    }

    pub(crate) fn request_group_shutdown(&self) {
        #[cfg(unix)]
        if let Some(group) = self.group {
            crate::send_signal(&group, crate::Signal::Term);
        }
    }

    pub(crate) fn terminate_group(&mut self) {
        #[cfg(unix)]
        if let Some(group) = self.group.take() {
            crate::send_signal(&group, crate::Signal::Kill);
        }
    }
}

impl Deref for Child {
    type Target = ProcessChild;
    fn deref(&self) -> &Self::Target {
        &self.inner
    }
}

impl DerefMut for Child {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.inner
    }
}

impl Drop for Child {
    fn drop(&mut self) {
        // Tokio's kill_on_drop covers the direct child. Keep the group id
        // even after wait() has reaped it, so descendants cannot escape cleanup.
        self.terminate_group();
    }
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    #[test]
    fn acp_environment_removes_nested_claude_markers() {
        let mut command = Command::new("unused");
        configure(&mut command);
        let removed: Vec<_> = command
            .as_std()
            .get_envs()
            .filter(|(_, value)| value.is_none())
            .map(|(key, _)| key.to_str().unwrap())
            .collect();
        assert_eq!(
            removed,
            vec![
                "CLAUDECODE",
                "CLAUDE_AGENT_SDK_VERSION",
                "CLAUDE_CODE_ENTRYPOINT",
                "CLAUDE_CODE_SSE_PORT"
            ]
        );
    }
}
