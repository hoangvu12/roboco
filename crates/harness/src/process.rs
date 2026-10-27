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
#[cfg(any(windows, test))]
pub(crate) mod cmd_shim;
#[cfg(windows)]
mod windows;
#[cfg(windows)]
pub use windows::{Child, ChildStdin, ChildStdout, Command, Stdio};

/// Backoff for a Windows process creation that failed transiently.
#[cfg(any(windows, test))]
const SPAWN_RETRY_DELAYS: [std::time::Duration; 3] = [
    std::time::Duration::from_millis(100),
    std::time::Duration::from_millis(300),
    std::time::Duration::from_millis(1000),
];

/// Sleep in a synchronous caller without stalling the async runtime it may
/// run on: a multi-thread runtime's worker hands its other tasks off first
/// (`block_in_place`). Outside a runtime, on a current-thread runtime (whose
/// only thread would block regardless) or on a blocking-pool thread, a plain
/// sleep.
#[cfg(any(windows, test))]
fn sleep_off_runtime(delay: std::time::Duration) {
    match tokio::runtime::Handle::try_current() {
        Ok(handle) if handle.runtime_flavor() == tokio::runtime::RuntimeFlavor::MultiThread => {
            tokio::task::block_in_place(|| std::thread::sleep(delay));
        }
        _ => std::thread::sleep(delay),
    }
}

/// Whether a failed Windows process creation is worth retrying: a sharing
/// or lock violation on the image, or access denied on one modified in the
/// last minute (the malware scanner holding a CLI that just updated).
/// `image_age` is how long ago the launched image was last written.
#[cfg(any(windows, test))]
fn spawn_retryable(error: &std::io::Error, image_age: Option<std::time::Duration>) -> bool {
    match error.raw_os_error() {
        Some(32 | 33) => true,
        Some(5) => image_age.is_some_and(|age| age < std::time::Duration::from_secs(60)),
        _ => false,
    }
}

/// Mark an agent that does not exit promptly on stdin EOF: Windows' teardown
/// then terminates it rather than waiting out a grace for an exit that will
/// not come. Unix teardown signals instead; nothing changes there.
/// (Roboco partial #977 port: the Windows adapter callers are not yet
/// carried, so the Unix build sees it unused.)
#[cfg_attr(not(windows), allow(dead_code))]
pub(crate) fn ignores_stdin_eof(child: &mut Child) {
    #[cfg(windows)]
    child.ignore_stdin_eof();
    #[cfg(not(windows))]
    let _ = child;
}

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

/// Windows has no death signal: Job Object membership and the teardown
/// paths cover orphaned children there, so stamping is a unix-only
/// hardening and this is a deliberate no-op (the compile fix for the ungated
/// callers — restored after the wave-9 merge dropped it again).
#[cfg(windows)]
pub fn dies_with_parent(_command: &mut Command) {}

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

/// One process a descendant snapshot found: its pid and, where the process
/// table reports it, its start time, which tells it apart from an unrelated
/// process that later inherits the pid. Each snapshot carries its own
/// identities to [`terminate_tree`]: runs never share or overwrite them.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[cfg_attr(not(unix), allow(dead_code))]
pub(crate) struct Tracked {
    pub(crate) pid: i32,
    start: Option<u64>,
}

#[cfg(unix)]
impl Tracked {
    /// `pid` as the process table describes it right now.
    #[cfg_attr(not(test), allow(dead_code))]
    pub(crate) fn now(pid: i32) -> Self {
        Self {
            pid,
            start: proc_table::start_time(pid),
        }
    }
}

/// Every live descendant of `root` — children, grandchildren, … — from one
/// process-table snapshot. Agent CLIs start each shell in its OWN process
/// group (Claude's background Bash, Codex's exec sessions), so neither a
/// signal to the agent nor to its group reaches them, and a background
/// shell outlives its agent (verified live, Claude 2.1.286: its own task
/// stop and SIGTERM both leave the shell running). Snapshot while the agent
/// is alive: once it exits, its orphans are reparented and untraceable.
///
/// Each descendant carries its start time, so [`terminate_tree`] can tell
/// the process it found from an unrelated one that inherited the pid after
/// it exited.
#[cfg(unix)]
pub(crate) async fn descendants(root: u32) -> Vec<Tracked> {
    let mut table = tokio::task::spawn_blocking(proc_table::read)
        .await
        .unwrap_or_default();
    if table.is_empty() {
        // No /proc or libproc view (or a sandbox hid it): ask ps.
        table = ps_table().await;
    }
    tree_of(root as i32, &table)
        .into_iter()
        .map(|pid| Tracked {
            pid,
            start: table
                .iter()
                .find(|entry| entry.pid == pid)
                .and_then(|entry| entry.start),
        })
        .collect()
}

/// One process-table row; `start` identifies the process across pid reuse.
#[cfg(unix)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct ProcEntry {
    pid: i32,
    ppid: i32,
    start: Option<u64>,
}

#[cfg(unix)]
fn tree_of(root: i32, table: &[ProcEntry]) -> Vec<i32> {
    let mut found = Vec::new();
    let mut frontier = vec![root];
    while let Some(parent) = frontier.pop() {
        for entry in table {
            if entry.ppid == parent && entry.pid > 1 && !found.contains(&entry.pid) {
                found.push(entry.pid);
                frontier.push(entry.pid);
            }
        }
    }
    found
}

#[cfg(unix)]
async fn ps_table() -> Vec<ProcEntry> {
    let Ok(out) = tokio::process::Command::new("ps")
        .args(["-A", "-o", "pid=,ppid="])
        .stdin(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .output()
        .await
    else {
        tracing::warn!("no process table (/proc or ps): agent descendants cannot be traced");
        return Vec::new();
    };
    String::from_utf8_lossy(&out.stdout)
        .lines()
        .filter_map(|line| {
            let mut cols = line.split_whitespace().map(str::parse::<i32>);
            let (pid, ppid) = (cols.next()?.ok()?, cols.next()?.ok()?);
            Some(ProcEntry {
                pid,
                ppid,
                start: proc_table::start_time(pid),
            })
        })
        .collect()
}

/// Native process-table readers: Linux's /proc (slim images often ship no
/// `ps`) and macOS's libproc. Both report each process's start time.
#[cfg(unix)]
mod proc_table {
    use super::ProcEntry;

    #[cfg(target_os = "linux")]
    pub(super) fn read() -> Vec<ProcEntry> {
        let Ok(dir) = std::fs::read_dir("/proc") else {
            return Vec::new();
        };
        dir.flatten()
            .filter_map(|entry| entry.file_name().to_str()?.parse::<i32>().ok())
            .filter_map(|pid| {
                let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
                let (ppid, start) = parse_stat(&stat)?;
                Some(ProcEntry {
                    pid,
                    ppid,
                    start: Some(start),
                })
            })
            .collect()
    }

    #[cfg(target_os = "linux")]
    pub(super) fn start_time(pid: i32) -> Option<u64> {
        let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
        parse_stat(&stat).map(|(_, start)| start)
    }

    #[cfg(target_os = "macos")]
    pub(super) fn read() -> Vec<ProcEntry> {
        // SAFETY: a null buffer asks only for the current pid count.
        let count = unsafe { libc::proc_listallpids(std::ptr::null_mut(), 0) };
        if count <= 0 {
            return Vec::new();
        }
        // Headroom for processes started between the two calls.
        let mut pids = vec![0 as libc::pid_t; count as usize + 64];
        let bytes = (pids.len() * std::mem::size_of::<libc::pid_t>()) as libc::c_int;
        // SAFETY: `bytes` is the buffer's true size.
        let filled = unsafe { libc::proc_listallpids(pids.as_mut_ptr().cast(), bytes) };
        if filled <= 0 {
            return Vec::new();
        }
        pids.truncate((filled as usize).min(pids.len()));
        pids.into_iter()
            .filter_map(|pid| {
                let info = bsd_info(pid)?;
                Some(ProcEntry {
                    pid,
                    ppid: info.pbi_ppid as i32,
                    start: Some(start_of(&info)),
                })
            })
            .collect()
    }

    #[cfg(target_os = "macos")]
    pub(super) fn start_time(pid: i32) -> Option<u64> {
        bsd_info(pid).map(|info| start_of(&info))
    }

    #[cfg(target_os = "macos")]
    fn bsd_info(pid: i32) -> Option<libc::proc_bsdinfo> {
        // SAFETY: proc_bsdinfo is plain data; proc_pidinfo writes at most
        // `size` bytes into it and reports how many it wrote.
        unsafe {
            let mut info: libc::proc_bsdinfo = std::mem::zeroed();
            let size = std::mem::size_of::<libc::proc_bsdinfo>() as libc::c_int;
            let written = libc::proc_pidinfo(
                pid,
                libc::PROC_PIDTBSDINFO,
                0,
                (&mut info as *mut libc::proc_bsdinfo).cast(),
                size,
            );
            (written == size).then_some(info)
        }
    }

    #[cfg(target_os = "macos")]
    fn start_of(info: &libc::proc_bsdinfo) -> u64 {
        info.pbi_start_tvsec * 1_000_000 + info.pbi_start_tvusec
    }

    #[cfg(not(any(target_os = "linux", target_os = "macos")))]
    pub(super) fn read() -> Vec<ProcEntry> {
        Vec::new()
    }

    #[cfg(not(any(target_os = "linux", target_os = "macos")))]
    pub(super) fn start_time(_pid: i32) -> Option<u64> {
        None
    }

    /// `(ppid, starttime)` from a `/proc/<pid>/stat` line: fields 4 and 22,
    /// counted after the parenthesized command name (which may itself hold
    /// spaces and parentheses).
    #[cfg_attr(not(target_os = "linux"), allow(dead_code))]
    pub(super) fn parse_stat(stat: &str) -> Option<(i32, u64)> {
        let rest = &stat[stat.rfind(')')? + 1..];
        let fields: Vec<&str> = rest.split_whitespace().collect();
        Some((fields.get(1)?.parse().ok()?, fields.get(19)?.parse().ok()?))
    }
}

/// Start times recorded by [`descendants`] snapshots, consulted before every
/// signal [`terminate_tree`] sends. The shared drivers pass bare pids
/// between the two, so the identity travels here.
/// Whether `pid` is still the process a snapshot found: same start time when
/// one was recorded, merely alive otherwise.
#[cfg(unix)]
fn still_same(pid: i32, recorded: Option<u64>) -> bool {
    match recorded {
        Some(start) => proc_table::start_time(pid) == Some(start),
        // SAFETY: signal 0 only checks for existence.
        None => (unsafe { libc::kill(pid, 0) }) == 0,
    }
}

/// Terminate `tracked` processes and the process groups they lead: SIGTERM,
/// then SIGKILL whatever is still running after `grace`. A pid whose process
/// has exited and been reused by another is never signaled.
#[cfg(unix)]
pub(crate) async fn terminate_tree(tracked: &[Tracked], grace: std::time::Duration) {
    let recorded: Vec<(i32, Option<u64>)> = tracked
        .iter()
        .map(|process| (process.pid, process.start))
        .collect();
    // A group leader's whole group (its own children included); anything
    // else by pid. A leader verified now names its group for as long as the
    // group has members: the kernel never hands out a live group's id.
    let targets: Vec<(i32, Option<u64>)> = recorded
        .iter()
        .filter(|&&(pid, start)| still_same(pid, start))
        .map(|&(pid, start)| {
            // SAFETY: getpgid only inspects; a vanished pid returns -1.
            if unsafe { libc::getpgid(pid) } == pid {
                (-pid, start)
            } else {
                (pid, start)
            }
        })
        .collect();
    // SAFETY: signal 0 only checks for existence.
    let alive = |target: i32| unsafe { libc::kill(target, 0) } == 0;
    // The grace can outlast a process; a plain pid is re-verified before
    // each signal, as it may have been reused in between.
    let current = |&(target, start): &(i32, Option<u64>)| {
        if target < 0 {
            alive(target)
        } else {
            still_same(target, start)
        }
    };
    for target in &targets {
        if current(target) {
            crate::send_signal(&target.0, crate::Signal::Term);
        }
    }
    let deadline = tokio::time::Instant::now() + grace;
    while targets.iter().any(current) && tokio::time::Instant::now() < deadline {
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    for target in &targets {
        if current(target) {
            crate::send_signal(&target.0, crate::Signal::Kill);
        }
    }
}

/// A retry's wait leaves a multi-thread runtime's other tasks running, and
/// is safe wherever a synchronous spawn can be called from.
#[cfg(test)]
#[test]
fn a_spawn_retry_wait_does_not_stall_the_runtime() {
    use std::time::Duration;
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(1)
        .enable_all()
        .build()
        .unwrap();
    runtime.block_on(async {
        let ticker = tokio::spawn(async {
            let mut ticks = 0;
            for _ in 0..5 {
                tokio::time::sleep(Duration::from_millis(20)).await;
                ticks += 1;
            }
            ticks
        });
        // On the runtime's only worker: without the hand-off the ticker
        // could not run until the wait was over.
        tokio::spawn(async { sleep_off_runtime(Duration::from_millis(400)) });
        let ticks = tokio::time::timeout(Duration::from_millis(300), ticker).await;
        assert_eq!(ticks.ok().and_then(Result::ok), Some(5));
        tokio::task::spawn_blocking(|| sleep_off_runtime(Duration::from_millis(1)))
            .await
            .unwrap();
    });
    tokio::runtime::Builder::new_current_thread()
        .build()
        .unwrap()
        .block_on(async { sleep_off_runtime(Duration::from_millis(1)) });
    sleep_off_runtime(Duration::from_millis(1));
}

#[cfg(test)]
#[test]
fn windows_spawn_retries_only_sharing_failures_and_fresh_images() {
    use std::io::Error;
    use std::time::Duration;
    let fresh = Some(Duration::from_secs(5));
    let old = Some(Duration::from_secs(3600));
    assert!(spawn_retryable(&Error::from_raw_os_error(32), old));
    assert!(spawn_retryable(&Error::from_raw_os_error(33), None));
    assert!(spawn_retryable(&Error::from_raw_os_error(5), fresh));
    assert!(!spawn_retryable(&Error::from_raw_os_error(5), old));
    assert!(!spawn_retryable(&Error::from_raw_os_error(5), None));
    // ERROR_FILE_NOT_FOUND, ERROR_BAD_EXE_FORMAT: retrying cannot help.
    assert!(!spawn_retryable(&Error::from_raw_os_error(2), fresh));
    assert!(!spawn_retryable(&Error::from_raw_os_error(193), fresh));
    assert_eq!(SPAWN_RETRY_DELAYS.len(), 3);
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
        assert!(tree.iter().any(|found| found.pid == shell), "{tree:?}");
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

    #[test]
    fn proc_stat_fields_survive_hostile_command_names() {
        // Field 22 (starttime) and 4 (ppid), with a comm holding ") (".
        let stat = "4242 (evil) (name) S 17 4242 4242 0 -1 4194560 100 0 0 0 1 2 0 0 20 0 1 0 987654 1000 10";
        assert_eq!(proc_table::parse_stat(stat), Some((17, 987654)));
        assert_eq!(proc_table::parse_stat("4242 (truncated) S 17"), None);
    }

    #[cfg(any(target_os = "linux", target_os = "macos"))]
    #[tokio::test]
    async fn native_table_finds_children_with_start_times() {
        let mut child = tokio::process::Command::new("sleep")
            .arg("30")
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        let pid = child.id().unwrap() as i32;
        let table = proc_table::read();
        let me = std::process::id() as i32;
        let entry = table
            .iter()
            .find(|entry| entry.pid == pid)
            .expect("child in the native table");
        assert_eq!(entry.ppid, me);
        assert!(entry.start.is_some());
        assert_eq!(proc_table::start_time(pid), entry.start);
        assert!(tree_of(me, &table).contains(&pid));
        child.kill().await.unwrap();
    }

    /// A pid whose recorded start time no longer matches belongs to some
    /// other process now: teardown must leave it alone.
    /// The child is registered in the ownership registry — roboco's zombie
    /// reaper (upstream has no such reaper) reaps unowned zombies
    /// process-wide, and the concurrent `scan_buries_unowned…` test would
    /// otherwise steal this child's exit status from under its wait.
    #[cfg(any(target_os = "linux", target_os = "macos"))]
    #[tokio::test]
    async fn teardown_skips_a_pid_that_was_reused() {
        let mut child = tokio::process::Command::new("sleep")
            .arg("30")
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        let pid = child.id().unwrap() as i32;
        crate::process::ownership::register(Some(pid as u32));
        let start = proc_table::start_time(pid).unwrap();
        let impostor = Tracked {
            pid,
            start: Some(start + 1),
        };
        terminate_tree(&[impostor], std::time::Duration::from_millis(200)).await;
        assert!(
            child.try_wait().unwrap().is_none(),
            "an impostor pid was signaled"
        );
        terminate_tree(&[Tracked::now(pid)], std::time::Duration::from_millis(200)).await;
        let status = tokio::time::timeout(std::time::Duration::from_secs(5), child.wait())
            .await
            .expect("the verified process is ended")
            .unwrap();
        assert!(!status.success());
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
