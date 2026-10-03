//! Native conversation projection storage. A native harness's saved journal
//! is the authority; the chat doc holds its projection. Projected entries use
//! deterministic ids ([`native_entry_id`]) so replaying a checkpoint replaces
//! rather than duplicates, and every write of one checkpoint lands in a
//! single commit together with the projection cursor that records it.

use std::collections::BTreeMap;

use loro::{LoroList, LoroMap, LoroValue};
use serde::{Deserialize, Serialize};

use crate::parts::{MessagePart, MessageStatus};
use crate::schema::{DocError, SessionDoc, SessionMessageEntry};

/// The doc id of a projected native journal entry.
pub fn native_entry_id(native_id: &str) -> String {
    format!("m-{native_id}")
}

/// Engine-internal cursor of what this doc already projects from its journal.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeProjection {
    /// The journal entry at the last applied checkpoint; `None` before the first.
    #[serde(default)]
    pub leaf: Option<String>,
    /// Native user entries shown under the engine's own echo id (the message
    /// id the user's send already wrote), keyed by native entry id.
    #[serde(default)]
    pub echoes: BTreeMap<String, String>,
    /// Echo entries an exact rebuild took off the branch, keyed by native
    /// entry id, so returning to that branch restores them as they were.
    #[serde(default)]
    pub parked: BTreeMap<String, SessionMessageEntry>,
}

impl NativeProjection {
    pub fn doc_id(&self, native_id: &str) -> String {
        self.echoes
            .get(native_id)
            .cloned()
            .unwrap_or_else(|| native_entry_id(native_id))
    }

    /// Whether `doc_id` names a projected entry (as opposed to an engine-local one).
    pub fn projects(&self, doc_id: &str) -> bool {
        doc_id.starts_with("m-") || self.echoes.values().any(|echo| echo == doc_id)
    }
}

fn meta_json<T: for<'de> Deserialize<'de>>(doc: &SessionDoc, key: &str) -> Option<T> {
    let loro::ValueOrContainer::Value(LoroValue::String(value)) =
        doc.doc().get_map("meta").get(key)?
    else {
        return None;
    };
    serde_json::from_str(&value).ok()
}

impl SessionDoc {
    /// Host-confirmed native conversation state, as clients render it.
    pub fn native_state(&self) -> Option<roboco_proto::NativeChatState> {
        meta_json(self, "native")
    }

    pub fn set_native_state(&self, state: &roboco_proto::NativeChatState) -> Result<(), DocError> {
        if self.native_state().as_ref() != Some(state) {
            self.doc()
                .get_map("meta")
                .insert("native", serde_json::to_string(state)?)?;
            self.doc().commit();
        }
        Ok(())
    }

    pub fn native_projection(&self) -> NativeProjection {
        meta_json(self, "nativeProjection").unwrap_or_default()
    }

    /// Start a batch of projection writes that commit together.
    pub fn native_batch(&self) -> NativeBatch<'_> {
        NativeBatch {
            doc: self,
            projection: self.native_projection(),
            dirty: false,
        }
    }
}

/// Uncommitted projection writes. Nothing is visible to readers until
/// [`NativeBatch::commit`], so a checkpoint never shows half applied.
pub struct NativeBatch<'a> {
    doc: &'a SessionDoc,
    pub projection: NativeProjection,
    dirty: bool,
}

fn entry_maps(list: &LoroList) -> impl Iterator<Item = (usize, LoroMap)> + '_ {
    (0..list.len()).filter_map(|i| match list.get(i) {
        Some(loro::ValueOrContainer::Container(loro::Container::Map(map))) => Some((i, map)),
        _ => None,
    })
}

fn str_field(map: &LoroMap, key: &str) -> Option<String> {
    match map.get(key) {
        Some(loro::ValueOrContainer::Value(LoroValue::String(s))) => Some(s.to_string()),
        _ => None,
    }
}

impl NativeBatch<'_> {
    fn messages(&self) -> LoroList {
        self.doc.doc().get_list("messages")
    }

    fn index_of(&self, id: &str) -> Option<usize> {
        let messages = self.messages();
        (0..messages.len()).rev().find(|&i| {
            matches!(messages.get(i), Some(loro::ValueOrContainer::Container(loro::Container::Map(map)))
                if str_field(&map, "id").as_deref() == Some(id))
        })
    }

    pub fn contains(&self, id: &str) -> bool {
        self.index_of(id).is_some()
    }

    /// Write `entry` in place when its id exists (same position, parts
    /// rewritten only when they differ), else insert it at `at`.
    pub fn upsert(&mut self, entry: &SessionMessageEntry, at: usize) -> Result<(), DocError> {
        let messages = self.messages();
        if let Some(index) = self.index_of(&entry.id) {
            let Some(loro::ValueOrContainer::Container(loro::Container::Map(map))) =
                messages.get(index)
            else {
                return Ok(());
            };
            let current = self.read(index);
            if current.as_ref() == Some(entry) {
                return Ok(());
            }
            crate::schema::write_entry_scalar_fields(&map, entry)?;
            if entry.status.is_none() {
                map.delete("status")?;
            }
            if current.as_ref().map(|e| &e.parts) != Some(&entry.parts) {
                let parts = map.insert_container("parts", LoroList::new())?;
                for part in &entry.parts {
                    crate::schema::push_part(&parts, part)?;
                }
            }
        } else {
            let map = messages.insert_container(at.min(messages.len()), LoroMap::new())?;
            crate::schema::write_entry_scalar_fields(&map, entry)?;
            let parts = map.insert_container("parts", LoroList::new())?;
            for part in &entry.parts {
                crate::schema::push_part(&parts, part)?;
            }
        }
        self.dirty = true;
        Ok(())
    }

    pub fn remove(&mut self, id: &str) -> Result<bool, DocError> {
        let Some(index) = self.index_of(id) else {
            return Ok(false);
        };
        self.messages().delete(index, 1)?;
        self.dirty = true;
        Ok(true)
    }

    pub fn len(&self) -> usize {
        self.messages().len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// The entry ids in doc order.
    pub fn ids(&self) -> Vec<String> {
        entry_maps(&self.messages())
            .filter_map(|(_, map)| str_field(&map, "id"))
            .collect()
    }

    fn read(&self, index: usize) -> Option<SessionMessageEntry> {
        let messages = self.messages();
        let Some(loro::ValueOrContainer::Container(loro::Container::Map(map))) =
            messages.get(index)
        else {
            return None;
        };
        crate::schema::entry_from_map(&map)
    }

    pub fn entry(&self, id: &str) -> Option<SessionMessageEntry> {
        self.read(self.index_of(id)?)
    }

    /// Replace the tool part `part_id`, wherever it lives, keeping its position.
    /// Returns the owning entry id when found.
    pub fn replace_part(
        &mut self,
        part_id: &str,
        part: &MessagePart,
    ) -> Result<Option<String>, DocError> {
        let messages = self.messages();
        for index in (0..messages.len()).rev() {
            let Some(entry) = self.read(index) else {
                continue;
            };
            let Some(position) = entry.parts.iter().position(|p| p.id() == part_id) else {
                continue;
            };
            if &entry.parts[position] == part {
                return Ok(Some(entry.id));
            }
            let mut updated = entry.clone();
            updated.parts[position] = part.clone();
            self.upsert(&updated, index)?;
            return Ok(Some(entry.id));
        }
        Ok(None)
    }

    /// Find the part `part_id` without changing anything.
    pub fn part(&self, part_id: &str) -> Option<(String, MessagePart)> {
        let messages = self.messages();
        (0..messages.len()).rev().find_map(|index| {
            let entry = self.read(index)?;
            let part = entry.parts.iter().find(|p| p.id() == part_id)?.clone();
            Some((entry.id, part))
        })
    }

    pub fn set_status(&mut self, id: &str, status: MessageStatus) -> Result<(), DocError> {
        if let Some(mut entry) = self.entry(id)
            && entry.status != Some(status)
        {
            let index = self.index_of(id).expect("entry was just read");
            entry.status = Some(status);
            self.upsert(&entry, index)?;
        }
        Ok(())
    }

    /// Persist the cursor and make every write of this batch visible at once.
    pub fn commit(mut self) -> Result<(), DocError> {
        let stored = self.doc.native_projection();
        if stored != self.projection {
            self.doc
                .doc()
                .get_map("meta")
                .insert("nativeProjection", serde_json::to_string(&self.projection)?)?;
            self.dirty = true;
        }
        if self.dirty {
            self.doc.doc().commit();
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::schema::MessageRole;

    fn entry(id: &str, role: MessageRole, text: &str) -> SessionMessageEntry {
        SessionMessageEntry {
            id: id.into(),
            role,
            parts: vec![MessagePart::Text {
                id: "t0".into(),
                text: text.into(),
            }],
            created_at: 1,
            device_id: "host".into(),
            status: Some(MessageStatus::Complete),
            continuation_of: None,
            duration_ms: None,
        }
    }

    #[test]
    fn a_batch_is_invisible_until_commit_and_upserts_replace_in_place() {
        let doc = SessionDoc::init("chat").unwrap();
        doc.push_message(&entry("echo", MessageRole::User, "hello"))
            .unwrap();
        let mut batch = doc.native_batch();
        batch
            .upsert(&entry("m-a", MessageRole::Assistant, "first"), 0)
            .unwrap();
        batch.projection.leaf = Some("leaf-1".into());
        batch.projection.echoes.insert("u1".into(), "echo".into());
        batch.commit().unwrap();
        let ids: Vec<_> = doc
            .read_entries()
            .unwrap()
            .into_iter()
            .map(|e| e.id)
            .collect();
        assert_eq!(ids, ["m-a", "echo"]);
        assert_eq!(doc.native_projection().leaf.as_deref(), Some("leaf-1"));
        assert_eq!(doc.native_projection().doc_id("u1"), "echo");
        assert!(doc.native_projection().projects("echo"));
        assert!(!doc.native_projection().projects("other"));

        let mut batch = doc.native_batch();
        batch
            .upsert(
                &entry("m-a", MessageRole::Assistant, "first, then more"),
                99,
            )
            .unwrap();
        batch.commit().unwrap();
        let entries = doc.read_entries().unwrap();
        assert_eq!(entries.len(), 2);
        assert!(
            matches!(&entries[0].parts[0], MessagePart::Text { text, .. } if text == "first, then more")
        );
    }

    #[test]
    fn replacing_a_part_keeps_its_entry_and_position() {
        let doc = SessionDoc::init("chat").unwrap();
        let mut message = entry("m-a", MessageRole::Assistant, "before");
        message.parts.push(MessagePart::Error {
            id: "e1".into(),
            message: "old".into(),
        });
        doc.push_message(&message).unwrap();
        let mut batch = doc.native_batch();
        let owner = batch
            .replace_part(
                "e1",
                &MessagePart::Error {
                    id: "e1".into(),
                    message: "new".into(),
                },
            )
            .unwrap();
        assert_eq!(owner.as_deref(), Some("m-a"));
        assert!(
            batch
                .replace_part(
                    "missing",
                    &MessagePart::Text {
                        id: "x".into(),
                        text: String::new()
                    }
                )
                .unwrap()
                .is_none()
        );
        batch.commit().unwrap();
        let entries = doc.read_entries().unwrap();
        assert!(
            matches!(&entries[0].parts[1], MessagePart::Error { message, .. } if message == "new")
        );
    }

    #[test]
    fn native_state_round_trips_and_skips_unchanged_writes() {
        let doc = SessionDoc::init("chat").unwrap();
        assert!(doc.native_state().is_none());
        let state = roboco_proto::NativeChatState {
            active_request: Some("r1".into()),
            ..Default::default()
        };
        doc.set_native_state(&state).unwrap();
        let version = doc.doc().oplog_vv();
        doc.set_native_state(&state).unwrap();
        assert_eq!(doc.doc().oplog_vv(), version);
        assert_eq!(doc.native_state(), Some(state));
    }
}
