//! Host-side path helpers (ticket 09): the forms a remote engine reports
//! and expects back.
//!
//! A Windows engine answers with `C:\Users\me`-style paths (drive letter,
//! backslashes); everything else is POSIX. Joining, going up and comparing
//! must keep the host's own style, or the paths the client computes from a
//! listing (`ListFolders` rows, "Parent Folder", row taps) come out mixed
//! when they are sent back (`ListFolders` again, `createSpace`, the
//! worktree spec).
//!
//! Pure functions over strings — a Rust port of upstream PR #621's
//! `FolderBrowser.kt` `HostPaths` (lines 58-130), plus `same_folder`
//! (#621's same-folder dedupe for `create_project`). The FFI re-exports
//! them as free functions so the Kotlin folder browser shares one rule
//! set.

/// Matches `^[A-Za-z]:([\\/].*)?$` — `C:`, `C:\`, `C:/x` — or starts with
/// `\\` (UNC). Byte-based on purpose: the ASCII prefix and separators
/// never split a UTF-8 sequence.
fn is_drive_prefixed(path: &str) -> bool {
    let bytes = path.as_bytes();
    match bytes.first() {
        Some(&b) if b.is_ascii_alphabetic() => match bytes.get(1) {
            Some(&b':') => bytes.len() == 2 || matches!(bytes[2], b'/' | b'\\'),
            _ => false,
        },
        _ => false,
    }
}

/// A Windows-style path: `C:`, `C:\…`, `C:/…`, or a UNC `\\server\share`.
pub fn is_windows(path: &str) -> bool {
    is_drive_prefixed(path) || path.starts_with("\\\\")
}

fn is_sep(c: char, windows: bool) -> bool {
    c == '/' || (windows && c == '\\')
}

/// A drive root (`C:`, `C:\`, `C:/`), a share root (`\\server\share`, with
/// at most one separator after the server), or `/`. Everything else —
/// including `C:\Users` and `\\server\share\x` — has a parent.
pub fn is_root(path: &str) -> bool {
    if is_windows(path) {
        let trimmed = path.trim_end_matches(['\\', '/']);
        if trimmed.chars().count() == 2 && trimmed.chars().nth(1) == Some(':') {
            return true;
        }
        if path.starts_with("\\\\") {
            // `drop(2)` semantics: a path made only of separators counts
            // as a root (zero separators after the share).
            let rest: String = trimmed.chars().skip(2).collect();
            return rest.chars().filter(|&c| is_sep(c, true)).count() <= 1;
        }
        return false;
    }
    path.trim_end_matches('/').is_empty()
}

/// Join `name` under `base`, in the base's own separator style (`\` on a
/// Windows base, else `/`); no separator when `base` already ends in one.
/// An empty base is just `name`.
pub fn join(base: &str, name: &str) -> String {
    if base.is_empty() {
        return name.to_owned();
    }
    let windows = is_windows(base);
    let sep = if windows { '\\' } else { '/' };
    if base.chars().last().is_some_and(|c| is_sep(c, windows)) {
        format!("{base}{name}")
    } else {
        format!("{base}{sep}{name}")
    }
}

/// One level up, or `None` at a root. `C:\Users` → `C:\`, `C:\` → `None`,
/// `/home` → `/`, `/` → `None`, `\\srv\share\x` → `\\srv\share`.
pub fn parent(path: &str) -> Option<String> {
    if path.is_empty() || is_root(path) {
        return None;
    }
    let windows = is_windows(path);
    // Trailing separators go, always keeping at least one character.
    let chars: Vec<char> = path.chars().collect();
    let mut end = chars.len();
    while end > 1 && is_sep(chars[end - 1], windows) {
        end -= 1;
    }
    let trimmed: String = chars[..end].iter().collect();
    let cut = trimmed.rfind(|c: char| is_sep(c, windows))?;
    let up = &trimmed[..cut];
    Some(match up {
        _ if windows && up.chars().count() == 2 && up.chars().nth(1) == Some(':') => {
            format!("{up}\\")
        }
        _ if windows && up.is_empty() => return None,
        _ if !windows && up.is_empty() => "/".to_owned(),
        _ => up.to_owned(),
    })
}

/// The last component, or the path itself for a root (`C:\`, `/`).
pub fn name(path: &str) -> String {
    if is_root(path) {
        return path.to_owned();
    }
    let windows = is_windows(path);
    let trimmed = path.trim_end_matches(|c: char| is_sep(c, windows));
    match trimmed.rfind(|c: char| is_sep(c, windows)) {
        // The separator is one ASCII byte, so `at + 1` is a char boundary.
        Some(at) => trimmed[at + 1..].to_owned(),
        None => trimmed.to_owned(),
    }
}

/// Same folder, for `create_project`'s dedupe. Windows style (either
/// side): case-insensitive, `/` and `\` equal, trailing separators
/// trimmed. Otherwise: exact comparison after trimming trailing `/`.
pub fn same_folder(a: &str, b: &str) -> bool {
    let windows = is_windows(a) || is_windows(b);
    if windows {
        let norm = |p: &str| {
            p.replace('\\', "/")
                .trim_end_matches('/')
                .to_lowercase()
        };
        norm(a) == norm(b)
    } else {
        a.trim_end_matches('/') == b.trim_end_matches('/')
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // ── is_windows ─────────────────────────────────────────────────────

    #[test]
    fn drive_letters_forward_and_back_are_windows() {
        assert!(is_windows("C:\\"));
        assert!(is_windows("C:"));
        assert!(is_windows("D:/Users"));
        assert!(is_windows("Z:\\repo\\x"));
        assert!(is_windows("\\\\srv\\share"));
        assert!(!is_windows("C:x")); // relative drive path
        assert!(!is_windows("/home/vu"));
        assert!(!is_windows("home/vu"));
        assert!(!is_windows("~/x"));
        assert!(!is_windows(""));
    }

    // ── is_root ────────────────────────────────────────────────────────

    #[test]
    fn drive_and_share_roots() {
        assert!(is_root("C:"));
        assert!(is_root("C:\\"));
        assert!(is_root("C:/"));
        assert!(is_root("\\\\srv\\share"));
        assert!(is_root("\\\\srv\\share\\"));
        assert!(is_root("\\\\srv\\share")); // at most one separator after the server
        assert!(is_root("/"));
        assert!(is_root("//"));
        assert!(!is_root("\\\\srv\\share\\x")); // two separators after the server
        assert!(!is_root("C:\\Users"));
        assert!(!is_root("/home"));
        assert!(!is_root("C:Users"));
    }

    // ── join ───────────────────────────────────────────────────────────

    #[test]
    fn join_keeps_the_base_style() {
        assert_eq!(join("C:\\Users", "me"), "C:\\Users\\me");
        assert_eq!(join("C:\\Users\\", "me"), "C:\\Users\\me");
        assert_eq!(join("/home", "vu"), "/home/vu");
        assert_eq!(join("/home/", "vu"), "/home/vu");
        assert_eq!(join("", "x"), "x");
        // Mixed separators in the base: windows wins, exactly as the
        // Kotlin original — the host's style is the base's declared one,
        // not per-character.
        assert_eq!(join("C:/Users", "me"), "C:/Users\\me");
    }

    // ── parent ─────────────────────────────────────────────────────────

    #[test]
    fn parent_one_level_up_or_none_at_a_root() {
        assert_eq!(parent("C:\\Users"), Some("C:\\".into()));
        assert_eq!(parent("C:\\Users\\me"), Some("C:\\Users".into()));
        assert_eq!(parent("C:\\"), None);
        assert_eq!(parent("C:"), None);
        assert_eq!(parent("/home"), Some("/".into()));
        assert_eq!(parent("/home/vu"), Some("/home".into()));
        assert_eq!(parent("/home/"), Some("/home".into()));
        assert_eq!(parent("/"), None);
        assert_eq!(parent("\\\\srv\\share\\x"), Some("\\\\srv\\share".into()));
        assert_eq!(parent("\\\\srv\\share"), None);
        // A relative path has no parent to walk to.
        assert_eq!(parent("relative"), None);
        assert_eq!(parent(""), None);
    }

    // ── name ───────────────────────────────────────────────────────────

    #[test]
    fn name_is_the_last_component_or_the_root_itself() {
        assert_eq!(name("C:\\"), "C:\\");
        assert_eq!(name("C:"), "C:");
        assert_eq!(name("/"), "/");
        assert_eq!(name("/home/vu"), "vu");
        assert_eq!(name("/home/"), "home");
        assert_eq!(name("C:\\Users\\me"), "me");
        assert_eq!(name("C:\\Users\\"), "Users");
    }

    // ── same_folder ────────────────────────────────────────────────────

    #[test]
    fn same_folder_windows_is_case_and_separator_blind() {
        assert!(same_folder("C:\\Repo\\", "c:/repo"));
        assert!(same_folder("C:\\Repo", "C:\\Repo\\"));
        assert!(same_folder("\\\\srv\\share", "\\\\srv\\share\\"));
        assert!(!same_folder("C:\\Repo", "C:\\Repo2"));
    }

    #[test]
    fn same_folder_posix_is_case_sensitive() {
        assert!(same_folder("/home/vu", "/home/vu/"));
        assert!(!same_folder("/home/vu", "/home/VU"));
        assert!(!same_folder("/home/vu", "/home/vu/x"));
    }
}
