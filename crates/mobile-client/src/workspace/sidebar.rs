//! Sidebar organization: the per-engine profile key, the pin projection
//! and the section edits the workspace mutations are built from, plus the
//! client-side validation of the engine's admission rules
//! (`crates/engine/src/sidebar_state.rs`).
//!
//! Roboco's engine store is an ordered-list replace, last write wins,
//! shared with the desktop and the web — not upstream's CRDT. The client
//! keeps the two states exclusive by construction and applies the
//! section-wins precedence when deriving.

use roboco_proto::{EngineInfo, SidebarSection, WorkspaceScope};

/// Admission limit for NEW pins — the engine's `MAX_SIDEBAR_PINS`.
pub const MAX_SIDEBAR_PINS: usize = 200;
/// A profile's section count bound — the engine's `MAX_SIDEBAR_SECTIONS`.
pub const MAX_SIDEBAR_SECTIONS: usize = 50;
/// The engine's `SIDEBAR_SECTION_NAME_MAX_CHARS`.
pub const SIDEBAR_SECTION_NAME_MAX_CHARS: usize = 120;

/// Stable key for the engine-local sidebar profile bucket (the desktop's
/// `sidebar_pin_profile_key`): `local` for a local-scope engine, otherwise
/// `"{scope}:{deviceId}"`. `None` until `EngineInfo` arrives.
pub fn profile_key(info: Option<&EngineInfo>) -> Option<String> {
    let info = info?;
    match info.workspace_scope {
        WorkspaceScope::Local => Some("local".to_owned()),
        WorkspaceScope::Synced => Some(format!("synced:{}", info.device_id)),
        WorkspaceScope::Development => Some(format!("development:{}", info.device_id)),
    }
}

/// One per-item pin intent, anchored to neighbour session ids (the
/// desktop's `SidebarPinChange` — the intent model survives even though
/// the CRDT sync layer does not).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PinChange {
    Pin {
        session_id: String,
        after: Option<String>,
        before: Option<String>,
    },
    Move {
        session_id: String,
        after: Option<String>,
        before: Option<String>,
    },
    Unpin { session_id: String },
}

impl PinChange {
    pub fn session_id(&self) -> &str {
        match self {
            Self::Pin { session_id, .. }
            | Self::Move { session_id, .. }
            | Self::Unpin { session_id } => session_id,
        }
    }

    /// Rebase the intent on the current raw pin list. A stale move never
    /// resurrects an unpinned item; a surviving right anchor (`before`)
    /// wins, else the left anchor (`after`) + 1, else append.
    pub fn project(&self, ids: &mut Vec<String>) {
        let id = self.session_id();
        if matches!(self, Self::Move { .. }) && !ids.iter().any(|v| v == id) {
            return;
        }
        ids.retain(|v| v != id);
        let (after, before) = match self {
            Self::Unpin { .. } => return,
            Self::Pin { after, before, .. } | Self::Move { after, before, .. } => (after, before),
        };
        let index = before
            .as_ref()
            .and_then(|v| ids.iter().position(|i| i == v))
            .or_else(|| {
                after
                    .as_ref()
                    .and_then(|v| ids.iter().position(|i| i == v).map(|i| i + 1))
            })
            .unwrap_or(ids.len());
        ids.insert(index, id.to_owned());
    }
}

/// Validate a projected pin list without truncating concurrent overflow:
/// the 201st pin is refused only when it is NEW (the engine's rule —
/// existing overflow stays valid, reorderable and removable).
pub fn validate_pin_list(current: &[String], next: &[String]) -> Result<(), &'static str> {
    let mut seen = std::collections::HashSet::new();
    if next.iter().any(|id| id.is_empty() || !seen.insert(id)) {
        return Err("Sidebar pins must be non-empty and unique");
    }
    if next.len() > MAX_SIDEBAR_PINS && next.iter().any(|id| !current.contains(id)) {
        return Err("You can pin up to 200 sessions");
    }
    Ok(())
}

/// The visible pins: the raw list minus the ids claimed by sections
/// (section membership is exclusive — the derive hides those pins).
pub fn visible_pins(pins: &[String], sections: &[SidebarSection]) -> Vec<String> {
    pins.iter()
        .filter(|id| !sections.iter().any(|s| s.session_ids.contains(id)))
        .cloned()
        .collect()
}

/// Validate a section name (trimmed, non-empty, ≤ 120 chars).
pub fn validate_section_name(name: &str) -> Result<String, &'static str> {
    let trimmed = name.trim();
    if trimmed.is_empty() {
        return Err("Section names cannot be empty");
    }
    if trimmed.chars().count() > SIDEBAR_SECTION_NAME_MAX_CHARS {
        return Err("Section names must not exceed 120 characters");
    }
    Ok(trimmed.to_owned())
}

/// `submit_section_dialog`'s create arm: append an empty open section
/// (id = UUID v4). Caller checks the ≤ 50 bound first.
pub fn create_section(sections: &[SidebarSection], id: &str, name: &str) -> Vec<SidebarSection> {
    let mut next = sections.to_vec();
    next.push(SidebarSection {
        id: id.to_owned(),
        name: name.to_owned(),
        session_ids: Vec::new(),
        collapsed: false,
    });
    next
}

/// `submit_section_dialog`'s rename arm: a missing id is a no-op.
pub fn rename_section(
    sections: &[SidebarSection],
    id: &str,
    name: &str,
) -> Vec<SidebarSection> {
    sections
        .iter()
        .map(|section| {
            if section.id == id {
                SidebarSection {
                    name: name.to_owned(),
                    ..section.clone()
                }
            } else {
                section.clone()
            }
        })
        .collect()
}

/// `delete_sidebar_section`: membership dies with the section, never
/// chats — its sessions return to Recent.
pub fn delete_section(sections: &[SidebarSection], id: &str) -> Vec<SidebarSection> {
    sections
        .iter()
        .filter(|section| section.id != id)
        .cloned()
        .collect()
}

/// The section header's disclosure toggle: the flag is engine-synced and
/// shared with the desktop and web (unlike upstream iOS, which kept every
/// fold local).
pub fn set_section_collapsed(
    sections: &[SidebarSection],
    id: &str,
    collapsed: bool,
) -> Vec<SidebarSection> {
    sections
        .iter()
        .map(|section| {
            if section.id == id {
                SidebarSection {
                    collapsed,
                    ..section.clone()
                }
            } else {
                section.clone()
            }
        })
        .collect()
}

/// `assign_sidebar_section`: exclusive membership — the chat leaves every
/// section, joins the target (which un-collapses to reveal it); `None`
/// means back to Recent. A missing target leaves the list unchanged (the
/// caller refuses the assignment whole).
pub fn assign_section(
    sections: &[SidebarSection],
    chat_id: &str,
    target: Option<&str>,
) -> Vec<SidebarSection> {
    if target.is_some_and(|target| !sections.iter().any(|section| section.id == target)) {
        return sections.to_vec();
    }
    sections
        .iter()
        .map(|section| {
            if Some(section.id.as_str()) == target {
                let mut session_ids: Vec<String> = section
                    .session_ids
                    .iter()
                    .filter(|id| id.as_str() != chat_id)
                    .cloned()
                    .collect();
                session_ids.push(chat_id.to_owned());
                SidebarSection {
                    session_ids,
                    collapsed: false,
                    ..section.clone()
                }
            } else {
                SidebarSection {
                    session_ids: section
                        .session_ids
                        .iter()
                        .filter(|id| id.as_str() != chat_id)
                        .cloned()
                        .collect(),
                    ..section.clone()
                }
            }
        })
        .collect()
}

/// A placement (wave-10 41, upstream #909's `place_session`): the chat
/// leaves every section and joins `target` between its members — before
/// the surviving `before` anchor, else after the surviving `after`
/// anchor, else at the end — and the target un-collapses to show it.
/// `None` means back to Recent (membership only: Recent sorts by
/// activity). A missing target leaves the list unchanged.
pub fn place_in_section(
    sections: &[SidebarSection],
    chat_id: &str,
    target: Option<&str>,
    after: Option<&str>,
    before: Option<&str>,
) -> Vec<SidebarSection> {
    if target.is_some_and(|target| !sections.iter().any(|section| section.id == target)) {
        return sections.to_vec();
    }
    sections
        .iter()
        .map(|section| {
            let mut session_ids: Vec<String> = section
                .session_ids
                .iter()
                .filter(|id| id.as_str() != chat_id)
                .cloned()
                .collect();
            if Some(section.id.as_str()) == target {
                let at = before
                    .and_then(|before| session_ids.iter().position(|id| id == before))
                    .or_else(|| {
                        after
                            .and_then(|after| session_ids.iter().position(|id| id == after))
                            .map(|ix| ix + 1)
                    })
                    .unwrap_or(session_ids.len());
                session_ids.insert(at, chat_id.to_owned());
                SidebarSection {
                    session_ids,
                    collapsed: false,
                    ..section.clone()
                }
            } else {
                SidebarSection {
                    session_ids,
                    ..section.clone()
                }
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn placement_lands_between_the_anchors_and_leaves_other_sections() {
        let sections = vec![section("a", &["x", "y", "z"]), section("b", &["m", "n"])];
        let ids = |s: &[SidebarSection], ix: usize| s[ix].session_ids.clone();
        // Before wins.
        let next = place_in_section(&sections, "m", Some("a"), Some("x"), Some("z"));
        assert_eq!(ids(&next, 0), ["x", "y", "m", "z"]);
        assert_eq!(ids(&next, 1), ["n"], "it left its old section");
        // Only after: one past it.
        let next = place_in_section(&sections, "m", Some("a"), Some("x"), None);
        assert_eq!(ids(&next, 0), ["x", "m", "y", "z"]);
        // A stale anchor falls back to the other, then to the end.
        let next = place_in_section(&sections, "m", Some("a"), Some("gone"), None);
        assert_eq!(ids(&next, 0), ["x", "y", "z", "m"]);
        // Within its own section: a reorder.
        let next = place_in_section(&sections, "z", Some("a"), None, Some("x"));
        assert_eq!(ids(&next, 0), ["z", "x", "y"]);
        // Back to Recent: membership only.
        let next = place_in_section(&sections, "y", None, None, None);
        assert_eq!(ids(&next, 0), ["x", "z"]);
        // An unknown target changes nothing.
        assert_eq!(place_in_section(&sections, "m", Some("nope"), None, None), sections);
    }

    fn section(id: &str, sessions: &[&str]) -> SidebarSection {
        SidebarSection {
            id: id.into(),
            name: format!("Section {id}"),
            session_ids: sessions.iter().map(|s| (*s).to_owned()).collect(),
            collapsed: false,
        }
    }

    #[test]
    fn profile_keys_follow_the_engine_scope() {
        let mut info = EngineInfo {
            device_id: "dev-1".into(),
            workspace_scope: WorkspaceScope::Local,
            cursor_sdk_version: None,
            capabilities: Vec::new(),
            version: Some("1.0".into()),
            protocol_version: Some(1),
            min_client_protocol_version: Some(1),
        };
        assert_eq!(profile_key(Some(&info)).as_deref(), Some("local"));
        info.workspace_scope = WorkspaceScope::Synced;
        assert_eq!(profile_key(Some(&info)).as_deref(), Some("synced:dev-1"));
        info.workspace_scope = WorkspaceScope::Development;
        assert_eq!(
            profile_key(Some(&info)).as_deref(),
            Some("development:dev-1")
        );
        assert_eq!(profile_key(None), None);
    }

    #[test]
    fn pin_projection_appends_after_the_last_anchor() {
        let change = PinChange::Pin {
            session_id: "c".into(),
            after: Some("a".into()),
            before: None,
        };
        let mut ids = vec!["a".to_owned(), "b".to_owned()];
        change.project(&mut ids);
        assert_eq!(ids, ["a", "c", "b"]);
        // before wins over after.
        let change = PinChange::Pin {
            session_id: "z".into(),
            after: Some("a".into()),
            before: Some("b".into()),
        };
        let mut ids = vec!["a".to_owned(), "b".to_owned()];
        change.project(&mut ids);
        assert_eq!(ids, ["a", "z", "b"]);
        // No anchors → append.
        let change = PinChange::Pin {
            session_id: "z".into(),
            after: None,
            before: None,
        };
        let mut ids = vec!["a".to_owned()];
        change.project(&mut ids);
        assert_eq!(ids, ["a", "z"]);
    }

    #[test]
    fn a_stale_move_never_resurrects_an_unpinned_id() {
        let change = PinChange::Move {
            session_id: "gone".into(),
            after: None,
            before: None,
        };
        let mut ids = vec!["a".to_owned()];
        change.project(&mut ids);
        assert_eq!(ids, ["a"]);
        let unpin = PinChange::Unpin {
            session_id: "a".into(),
        };
        let mut ids = vec!["a".to_owned()];
        unpin.project(&mut ids);
        assert!(ids.is_empty());
    }

    #[test]
    fn the_pin_limit_refuses_only_new_pins() {
        let current: Vec<String> = (0..200).map(|i| format!("p{i}")).collect();
        // Reordering 200 existing pins is fine.
        let mut next = current.clone();
        next.reverse();
        assert!(validate_pin_list(&current, &next).is_ok());
        // The 201st, NEW pin is refused…
        let mut overflow = current.clone();
        overflow.push("new".into());
        assert_eq!(
            validate_pin_list(&current, &overflow),
            Err("You can pin up to 200 sessions")
        );
        // …but an existing overflow stays reorderable.
        let mut existing = current.clone();
        existing.push("old".into());
        let mut shuffled = existing.clone();
        shuffled.reverse();
        assert!(validate_pin_list(&existing, &shuffled).is_ok());
    }

    #[test]
    fn visible_pins_hide_sectioned_ids() {
        let sections = vec![section("s1", &["a"])];
        assert_eq!(
            visible_pins(&["a".into(), "b".into()], &sections),
            ["b".to_owned()]
        );
    }

    #[test]
    fn section_names_validate_like_the_engine() {
        assert_eq!(
            validate_section_name("  Folder  "),
            Ok("Folder".to_owned())
        );
        assert!(validate_section_name("   ").is_err());
        assert!(validate_section_name(&"x".repeat(121)).is_err());
        assert!(validate_section_name(&"x".repeat(120)).is_ok());
    }

    #[test]
    fn section_edits_match_the_desktop_rules() {
        let base = vec![section("s1", &["a"]), section("s2", &["b"])];
        let created = create_section(&base, "s3", "New");
        assert_eq!(created.len(), 3);
        assert!(created[2].session_ids.is_empty());
        assert!(!created[2].collapsed);

        let renamed = rename_section(&base, "s1", "Renamed");
        assert_eq!(renamed[0].name, "Renamed");

        let deleted = delete_section(&base, "s1");
        assert_eq!(deleted.len(), 1);
        assert_eq!(deleted[0].id, "s2");

        let folded = set_section_collapsed(&base, "s1", true);
        assert!(folded[0].collapsed);
        assert!(!folded[1].collapsed);

        // Assign moves membership and un-collapses the target.
        let assigned = assign_section(&base, "b", Some("s1"));
        assert_eq!(assigned[0].session_ids, ["a".to_owned(), "b".to_owned()]);
        assert!(!assigned[0].collapsed);
        assert!(assigned[1].session_ids.is_empty());
        // Back to Recent clears membership everywhere.
        let cleared = assign_section(&assigned, "b", None);
        assert_eq!(cleared[0].session_ids, ["a".to_owned()]);
        // A missing target changes nothing.
        assert_eq!(assign_section(&base, "a", Some("nope")), base);
    }
}
