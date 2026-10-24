//! Owned agent processes. Non-Windows builds retain Tokio's command and child.
//! Windows uses native creation-time Job Object membership, never an implicit shell.
//!
//! Crash ownership (unix): agent CLIs outlive nothing — [`dies_with_parent`]
//! makes the kernel SIGKILL a child the moment this process dies (a crash or
//! force-quit otherwise leaks every parked session and its MCP bridge), and
//! [`ownership`] + [`scan_adopted_zombies`] bury the orphaned descendants
//! nothing will ever wait for when this process is PID 1 of its container.
#[cfg(not(windows))]
pub use std::process::Stdio;
#[cfg(not(windows))]
pub use tokio::process::{Child, ChildStdin, ChildStdout, Command};
#[cfg(windows)]
mod windows;
#[cfg(windows)]
pub use windows::{Child, ChildStdin, ChildStdout, Command, Stdio};

#[cfg(unix)]
pub(crate) fn signal_target(child: &Child) -> Option<i32> {
    let pid = child.id()? as i32;
    // ACP children lead a private group; other harnesses retain pid signaling.
    // SAFETY: getpgid only inspects the owned, unreaped child.
    Some(if unsafe { libc::getpgid(pid) } == pid {
        -pid
    } else {
        pid
    })
}

// ── crash ownership ─────────────────────────────────────────────────────────

/// Ask the kernel to SIGKILL this child the moment the spawning process
/// dies, so an engine crash or force-quit can never leak a live agent CLI
/// (and its MCP bridge) — the graceful teardown paths already own the clean
/// exits; this is only the net under them. Windows needs nothing: Job
/// Object membership already kills the tree with the owner.
///
/// SAFETY constraints: the hook runs between `fork` and `exec` in a
/// multithreaded process, so it may only call async-signal-safe functions —
/// `prctl`, `getppid`, `_exit` — which it does. It must only be used from a
/// long-lived thread (an async-context `Command::spawn`, i.e. a tokio core
/// worker): `PR_SET_PDEATHSIG` fires on the death of the parent *thread*
/// that forked, so a child stamped from a `spawn_blocking` worker that later
/// exits would be killed while healthy.
#[cfg(unix)]
pub fn dies_with_parent(command: &mut Command) {
    #[cfg(not(target_os = "linux"))]
    {
        // macOS has no PR_SET_PDEATHSIG: the graceful teardown paths and
        // the host's init cover those engines (roboco is never PID 1 there).
        let _ = command;
    }
    #[cfg(target_os = "linux")]
    {
        use std::os::unix::process::CommandExt;
        // Capture the parent (this process) before the fork: inside the
        // child it is the pid to compare `getppid()` against.
        let parent_pid = std::process::id() as libc::pid_t;
        // tokio's Command does not implement CommandExt; stamp the std
        // command it wraps (supported mutation — the wrapper reads it at
        // spawn time).
        let std_command = command.as_std_mut();
        // SAFETY: pre_exec itself and everything the hook calls (prctl,
        // getppid, _exit) is async-signal-safe; the block covers the hook
        // body.
        unsafe {
            std_command.pre_exec(move || {
                if libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL) != 0 {
                    // Best effort: a failed prctl only loses the crash net.
                    return Ok(());
                }
                // The race the man page warns about: the parent may have
                // died between fork and prctl, in which case no signal will
                // ever come. The child has been reparented, so a mismatched
                // getppid means the parent is already gone — exit now
                // instead of running on.
                if libc::getppid() != parent_pid {
                    libc::_exit(129);
                }
                Ok(())
            });
        }
    }
}

/// Pids this process spawned directly and may still wait on — the orphan
/// reaper ([`scan_adopted_zombies`]) skips these, because reaping a child
/// out from under its owner steals the exit status its `wait` reads.
/// Entries are advisory and self-pruning: a pid that no longer exists is
/// dropped on the next scan, so a stale entry can only ever make the reaper
/// skip a reap (a pid-reuse window), never take one it shouldn't.
#[cfg(unix)]
pub mod ownership {
    use std::collections::HashSet;
    use std::sync::{LazyLock, Mutex};

    static OWNED: LazyLock<Mutex<HashSet<i32>>> = LazyLock::new(|| Mutex::new(HashSet::new()));

    /// Record a directly-spawned child pid. Best called right after spawn;
    /// `Option` so call sites can pass `child.id()` without unwrapping.
    pub fn register(pid: Option<u32>) {
        if let Some(pid) = pid.and_then(|p| i32::try_from(p).ok()) {
            lock().insert(pid);
        }
    }

    fn lock() -> std::sync::MutexGuard<'static, HashSet<i32>> {
        OWNED
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    pub(super) fn is_registered(pid: i32) -> bool {
        lock().contains(&pid)
    }

    /// Drop registry entries whose pid no longer exists: the child was
    /// reaped (or never spawned), so the pid no longer needs protecting.
    pub(super) fn prune_gone() {
        let mut owned = lock();
        owned.retain(|&pid| unsafe {
            // SAFETY: signal 0 only probes for the pid's existence.
            libc::kill(pid, 0) == 0
                || std::io::Error::last_os_error().raw_os_error() != Some(libc::ESRCH)
        });
    }
}

/// One scan of `/proc` for zombie children nobody will ever wait for, and
/// the burial of the ones that have been dead long enough to prove it.
///
/// When this process is PID 1 (a headless engine in a container), every
/// orphaned descendant — an MCP bridge whose agent won the exit race, a
/// tool process killed with its run, anything started inside a terminal
/// pane — is reparented here, and nothing reaps it: the zombie census grows
/// forever (observed ~1000 in 8 days). The scan enumerates zombie children
/// of ours from `/proc`, then `waitpid`s (reaps) only the ones that
///
/// - are not in [`ownership`] (an owner may still be about to read them),
/// - and have been zombies for at least `min_age` — confirmed by appearing
///   in two consecutive scans — so an ephemeral helper whose owner waits it
///   milliseconds later is never stolen.
///
/// `seen` carries the first-sighting timestamps between calls (pass the
/// same map every time; entries for pids that vanished are dropped).
/// Returns how many zombies were reaped this scan. Linux only: it reads
/// `/proc`; on other unixes PID 1 is the platform init, which reaps.
#[cfg(target_os = "linux")]
pub fn scan_adopted_zombies(
    seen: &mut std::collections::HashMap<i32, std::time::Instant>,
    min_age: std::time::Duration,
) -> usize {
    let parent = std::process::id() as i32;
    let mut zombies = Vec::new();
    let dir = match std::fs::read_dir("/proc") {
        Ok(dir) => dir,
        Err(_) => return 0,
    };
    for entry in dir.flatten() {
        let name = entry.file_name();
        let Some(pid) = name.to_str().and_then(|s| s.parse::<i32>().ok()) else {
            continue;
        };
        // A zombie's stat: `pid (comm) Z ppid …` — comm may contain spaces,
        // so parse from the last ')'.
        let Ok(stat) = std::fs::read_to_string(entry.path().join("stat")) else {
            continue;
        };
        let Some(rest) = stat.rsplit_once(')') else {
            continue;
        };
        let mut fields = rest.1.split_whitespace();
        let state = fields.next();
        let ppid = fields.next().and_then(|f| f.parse::<i32>().ok());
        if state == Some("Z") && ppid == Some(parent) {
            zombies.push(pid);
        }
    }
    let now = std::time::Instant::now();
    let mut reaped = 0;
    for pid in zombies {
        if ownership::is_registered(pid) {
            continue; // an owner may still be about to wait it
        }
        let first_seen = *seen.entry(pid).or_insert(now);
        if now.duration_since(first_seen) < min_age {
            continue; // not yet proven ownerless
        }
        // SAFETY: waitpid with WNOHANG on a pid that is our zombie child
        // reaps it; anything else returns 0 or ECHILD harmlessly.
        let mut status = 0;
        let waited = unsafe { libc::waitpid(pid, &mut status, libc::WNOHANG) };
        if waited == pid {
            reaped += 1;
            seen.remove(&pid);
        } else if waited == 0 {
            // Running again (state raced): forget the sighting.
            seen.remove(&pid);
        }
    }
    // Drop sightings for pids that vanished between scans (reaped by their
    // owners, or never were), and registry entries that no longer exist.
    seen.retain(|&pid, _| unsafe {
        libc::kill(pid, 0) == 0
            || std::io::Error::last_os_error().raw_os_error() != Some(libc::ESRCH)
    });
    ownership::prune_gone();
    reaped
}
#[cfg(windows)]
pub(crate) fn signal_target(child: &Child) -> Option<std::sync::Arc<crate::windows_process::Job>> {
    Some(child.job.clone())
}

/// Every live descendant of `root` — children, grandchildren, … — from one
/// process-table snapshot. Agent CLIs start each shell in its OWN process
/// group (Claude's background Bash, Codex's exec sessions), so neither a
/// signal to the agent nor to its group reaches them, and a background
/// shell outlives its agent (verified live, Claude 2.1.286: its own task
/// stop and SIGTERM both leave the shell running). Snapshot while the agent
/// is alive: once it exits, its orphans are reparented and untraceable.
#[cfg(unix)]
pub(crate) async fn descendants(root: u32) -> Vec<i32> {
    let Ok(out) = tokio::process::Command::new("ps")
        .args(["-A", "-o", "pid=,ppid="])
        .stdin(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .output()
        .await
    else {
        return Vec::new();
    };
    let table: Vec<(i32, i32)> = String::from_utf8_lossy(&out.stdout)
        .lines()
        .filter_map(|line| {
            let mut cols = line.split_whitespace().map(str::parse::<i32>);
            Some((cols.next()?.ok()?, cols.next()?.ok()?))
        })
        .collect();
    let mut found = Vec::new();
    let mut frontier = vec![root as i32];
    while let Some(parent) = frontier.pop() {
        for &(pid, ppid) in &table {
            if ppid == parent && pid > 1 && !found.contains(&pid) {
                found.push(pid);
                frontier.push(pid);
            }
        }
    }
    found
}

/// Terminate `pids` and the process groups they lead: SIGTERM, then SIGKILL
/// whatever is still running after `grace`.
#[cfg(unix)]
pub(crate) async fn terminate_tree(pids: &[i32], grace: std::time::Duration) {
    // A group leader's whole group (its own children included); anything
    // else by pid.
    let targets: Vec<i32> = pids
        .iter()
        .map(|&pid| {
            // SAFETY: getpgid only inspects; a vanished pid returns -1.
            if unsafe { libc::getpgid(pid) } == pid {
                -pid
            } else {
                pid
            }
        })
        .collect();
    let alive = |target: i32| unsafe { libc::kill(target, 0) } == 0;
    for &target in &targets {
        crate::send_signal(&target, crate::Signal::Term);
    }
    let deadline = tokio::time::Instant::now() + grace;
    while targets.iter().any(|&t| alive(t)) && tokio::time::Instant::now() < deadline {
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    for &target in &targets {
        if alive(target) {
            crate::send_signal(&target, crate::Signal::Kill);
        }
    }
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use std::time::Duration;

    /// Helper leg of the crash-net test: spawned as its own test-binary
    /// process with `ROBOCO_TEST_DEATH_HELPER` pointing at a pid file. It
    /// stamps a `sleep` with `dies_with_parent`, publishes the pid, then
    /// SIGKILLs itself — no Drop, no wait: exactly an engine crash.
    #[cfg(target_os = "linux")]
    #[tokio::test]
    async fn dies_with_parent_helper() {
        let Some(out) = std::env::var_os("ROBOCO_TEST_DEATH_HELPER") else {
            return;
        };
        let mut cmd = Command::new("sleep");
        cmd.arg("30");
        dies_with_parent(&mut cmd);
        cmd.stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .kill_on_drop(true);
        let mut child = cmd.spawn().expect("helper child");
        std::fs::write(&out, child.id().unwrap().to_string()).unwrap();
        // Not kill-on-drop semantics here: crash the process itself, which
        // skips every Drop — the exact engine-crash shape.
        let _ = child.id();
        std::thread::sleep(Duration::from_millis(300));
        // SAFETY: SIGKILL to this process only.
        unsafe { libc::kill(libc::getpid(), libc::SIGKILL) };
    }

    /// `dies_with_parent`: a stamped child is SIGKILLed by the kernel the
    /// moment its spawner dies — however the spawner dies. The helper test
    /// process stamps a `sleep`, then crashes itself; the kernel must take
    /// the stamped child down with it (an unstamped child would sleep on).
    #[cfg(target_os = "linux")]
    #[tokio::test]
    async fn dies_with_parent_kills_the_child_when_the_spawner_dies() {
        let dir = tempfile::tempdir().unwrap();
        let pid_file = dir.path().join("pid");
        let mut helper = tokio::process::Command::new(std::env::current_exe().unwrap())
            .arg("dies_with_parent_helper")
            .env("ROBOCO_TEST_DEATH_HELPER", &pid_file)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        let mut stamped = None;
        for _ in 0..250 {
            if let Some(pid) = std::fs::read_to_string(&pid_file)
                .ok()
                .and_then(|s| s.trim().parse::<i32>().ok())
            {
                stamped = Some(pid);
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        let stamped = stamped.expect("the helper published its stamped child's pid");
        // The helper self-SIGKILLs ~300ms after publishing: poll until the
        // kernel's PDEATHSIG takes the stamped child down. A dead-but-
        // unreaped child is a ZOMBIE (kill(pid, 0) still succeeds on it —
        // exactly the PID-1 leak this feature set fixes), so liveness is
        // read from /proc state, not from signal 0.
        let alive = |pid: i32| {
            std::fs::read_to_string(format!("/proc/{pid}/stat"))
                .ok()
                .and_then(|s| {
                    s.rsplit_once(')')
                        .and_then(|(_, rest)| rest.split_whitespace().next().map(String::from))
                })
                .is_some_and(|state| state != "Z")
        };
        let mut died = false;
        for _ in 0..250 {
            if !alive(stamped) {
                died = true;
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        let _ = helper.wait().await; // reap the crashed helper itself
        assert!(died, "the stamped child must die with its spawner's crash");
    }

    /// `scan_adopted_zombies`: an unowned zombie (exited, nobody waiting) is
    /// sighted, then buried once past the proof window — while a zombie in
    /// the ownership registry is spared for its owner to wait. Pid-specific
    /// assertions only: other tests' children must never break this.
    #[cfg(target_os = "linux")]
    #[tokio::test]
    async fn scan_buries_unowned_zombies_and_spares_owned_ones() {
        /// Spawn a short-lived child and HOLD the handle: it exits and stays
        /// an unwaited zombie child of this process (tokio reaps on wait or
        /// drop only).
        async fn zombie_child() -> tokio::process::Child {
            let mut child = tokio::process::Command::new("sh")
                .arg("-c")
                .arg("exit 0")
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()
                .unwrap();
            let pid = child.id().unwrap() as i32;
            for _ in 0..250 {
                if proc_state(pid).as_deref() == Some("Z") {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
            child
        }
        fn proc_state(pid: i32) -> Option<String> {
            std::fs::read_to_string(format!("/proc/{pid}/stat"))
                .ok()
                .and_then(|s| {
                    s.rsplit_once(')')
                        .and_then(|(_, rest)| rest.split_whitespace().next().map(String::from))
                })
        }

        let mut owned = zombie_child().await;
        let owned_pid = owned.id().unwrap() as i32;
        ownership::register(Some(owned_pid as u32));

        let mut unowned = zombie_child().await;
        let unowned_pid = unowned.id().unwrap() as i32;

        // First scan (long window): both sighted, nothing reaped.
        let mut seen = std::collections::HashMap::new();
        let reaped = scan_adopted_zombies(&mut seen, Duration::from_secs(3600));
        assert_eq!(reaped, 0);
        assert!(
            seen.contains_key(&unowned_pid),
            "the unowned zombie was sighted"
        );
        // Second scan (window elapsed): the unowned one is buried, the
        // registered one is spared.
        let reaped = scan_adopted_zombies(&mut seen, Duration::from_secs(0));
        assert!(reaped >= 1, "the unowned zombie was buried");
        assert_eq!(
            proc_state(unowned_pid),
            None,
            "the unowned zombie no longer exists"
        );
        assert_eq!(
            proc_state(owned_pid).as_deref(),
            Some("Z"),
            "the owned zombie waits for its owner, untouched"
        );
        // Cleanup: the owner reaps its own; the stolen one is already gone.
        let _ = owned.wait().await;
        let _ = unowned.wait().await;
    }

    /// A grandchild in its own process group (how agent CLIs start shells)
    /// survives its parent's death; the descendant snapshot still finds it
    /// and `terminate_tree` ends it.
    #[tokio::test]
    async fn tree_teardown_reaches_shells_in_their_own_process_group() {
        /// Ends the fixture shell even when an assertion fails first.
        struct Reap(i32);
        impl Drop for Reap {
            fn drop(&mut self) {
                // SAFETY: SIGKILL to the fixture's own pid.
                unsafe { libc::kill(self.0, libc::SIGKILL) };
            }
        }
        let dir = tempfile::tempdir().unwrap();
        let ready = dir.path().join("ready");
        // The child reports its pid only once it leads its own group: waiting
        // for it to exist was not enough (it had not called setpgid yet).
        let script = format!(
            "python3 -c 'import os, time; os.setpgid(0, 0); open(\"{}\", \"w\").write(str(os.getpid())); time.sleep(60)' & sleep 60",
            ready.display()
        );
        let mut parent = tokio::process::Command::new("sh")
            .args(["-c", &script])
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        let root = parent.id().unwrap();
        let mut shell = None;
        for _ in 0..250 {
            if let Some(pid) = std::fs::read_to_string(&ready)
                .ok()
                .and_then(|s| s.trim().parse::<i32>().ok())
            {
                shell = Some(pid);
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        }
        let shell = shell.expect("the shell leads its own process group");
        let _reap = Reap(shell);
        assert_eq!(unsafe { libc::getpgid(shell) }, shell);
        let tree = descendants(root).await;
        assert!(tree.contains(&shell), "{tree:?}");
        // The parent dies hard (an unresponsive agent's SIGKILL): the shell
        // is orphaned, not killed.
        parent.kill().await.unwrap();
        assert_eq!(unsafe { libc::kill(shell, 0) }, 0, "orphan survives");
        terminate_tree(&tree, std::time::Duration::from_secs(2)).await;
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
        // "Ended" must tolerate an un-reaped zombie: containers that run the
        // engine itself as PID 1 inherit the terminated orphan and nothing
        // reaps it, yet signal 0 still succeeds on a zombie (the very leak
        // `scan_adopted_zombies` exists to bury). Read the process state
        // where /proc exists; elsewhere signal 0 not succeeding is the proof.
        let ended = || {
            // SAFETY: signal 0 probes existence only.
            if unsafe { libc::kill(shell, 0) } != 0 {
                return true;
            }
            std::fs::read_to_string(format!("/proc/{shell}/stat"))
                .ok()
                .and_then(|s| {
                    s.rsplit_once(')')
                        .and_then(|(_, rest)| rest.split_whitespace().next().map(str::to_owned))
                })
                .is_some_and(|state| state == "Z")
        };
        assert!(ended(), "orphan ended (or lies un-reaped as a zombie)");
    }

    #[tokio::test]
    async fn unix_launch_retains_tokio_types_and_output() {
        let mut command: tokio::process::Command = Command::new(std::env::current_exe().unwrap());
        command
            .arg("--list")
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .kill_on_drop(true);
        let child: tokio::process::Child = command.spawn().unwrap();
        let result = child.wait_with_output().await.unwrap();
        assert!(result.status.success());
        assert!(
            String::from_utf8_lossy(&result.stdout)
                .contains("unix_launch_retains_tokio_types_and_output")
        );
        let result = command.output().await.unwrap();
        assert!(result.status.success());
        assert!(!result.stdout.is_empty());
    }
}
