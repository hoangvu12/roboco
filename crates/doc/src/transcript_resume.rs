//! Transcript resume: an opt-in, capability-gated way for a client that kept
//! a transcript copy (in memory or in its per-engine cache) to catch up with
//! an ordinary [`Delta`](crate::TranscriptFrame::Delta) frame instead of a
//! full `reset` on every reconnect.
//!
//! The client sends a compact summary of what it holds — a digest of the
//! common prefix plus the ids and digests of its last W entries. When the
//! summary matches, [`resume_frame`] produces a delta that transforms the
//! client's copy into the current transcript; otherwise the engine sends an
//! ordinary reset and the existing `apply_transcript_frame` path is all the
//! client ever needs. Digests are computed by the ENGINE
//! ([`entry_digest`]) and stored opaquely by clients: a different engine
//! build that serializes entries differently produces different digests, the
//! prefix no longer matches, and the client gets a correct (if full) reset.
//!
//! Definitions are normative — one implementation, shared by the engine and
//! the mobile client (the engine wiring lives in the `WATCH_DOC_MESSAGES`
//! handler; the wire types are [`TranscriptResume`] /
//! [`TranscriptResumeEntry`]).

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, HashMap, HashSet};
use ts_rs::TS;

use crate::schema::SessionMessageEntry;
use crate::transcript_delta::{TranscriptFrame, TranscriptUpsert, TranscriptUpdate};

/// Upper bound on the tail window a client may send (the wire contract; a
/// larger tail is refused with a full reset).
pub const MAX_RESUME_TAIL: usize = 512;

/// One entry of a resume tail: the entry id plus the digest the engine sent
/// for it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct TranscriptResumeEntry {
    pub id: String,
    pub digest: String,
}

/// The client's summary of a held transcript copy: how many entries it holds,
/// a digest over the common prefix, and the ids/digests of its last W entries.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct TranscriptResume {
    /// Entries the client holds.
    pub count: usize,
    /// `prefix_digest` of the first `count − tail.len()` entries' digests.
    pub prefix: String,
    /// The last W held entries, in order; W ≤ 512.
    pub tail: Vec<TranscriptResumeEntry>,
}

fn hex_of(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        let _ = write!(out, "{byte:02x}");
    }
    out
}

/// Digest of one transcript entry: lowercase hex of the first 8 bytes of
/// `SHA-256(serde_json::to_vec(entry))` → 16 chars.
///
/// `SessionMessageEntry` carries no map types, so serialization is
/// deterministic within one engine build (if a future field is a map it must
/// be a `BTreeMap`, or digests become nondeterministic across processes).
pub fn entry_digest(entry: &SessionMessageEntry) -> String {
    // Infallible for this type (no map keys, no lossless-float shenanigans);
    // a failure would only degrade to a consistent-but-constant digest.
    let bytes = serde_json::to_vec(entry).unwrap_or_default();
    let hash = Sha256::digest(&bytes);
    hex_of(&hash[..8])
}

/// Digest of a digest sequence: lowercase hex of the first 16 bytes of
/// `SHA-256(d₀ ‖ "\\n" ‖ d₁ ‖ "\\n" ‖ …)` — every entry digest followed by
/// one `\n`. 32 chars. Empty input hashes the empty byte string
/// (`e3b0c44298fc1c149afbf4c8996fb924`).
pub fn prefix_digest(digests: &[impl AsRef<str>]) -> String {
    let mut hasher = Sha256::new();
    for digest in digests {
        hasher.update(digest.as_ref().as_bytes());
        hasher.update(b"\n");
    }
    hex_of(&hasher.finalize()[..16])
}

/// Digests of a list of entries, aligned by position.
pub fn entry_digests(entries: &[SessionMessageEntry]) -> Vec<String> {
    entries.iter().map(entry_digest).collect()
}

impl TranscriptResume {
    /// Build a resume summary from a held transcript copy and the digest map
    /// the engine sent alongside it. `window` = W, clamped to
    /// `[0, min(held.len(), MAX_RESUME_TAIL)]`.
    ///
    /// Returns `None` when any held entry lacks a digest — the copy was fed
    /// by an engine that predates digests, so it is never resumable and the
    /// caller should subscribe plainly.
    pub fn from_held(
        held: &[SessionMessageEntry],
        digests: &HashMap<String, String>,
        window: usize,
    ) -> Option<Self> {
        if held.iter().any(|entry| !digests.contains_key(&entry.id)) {
            return None;
        }
        let count = held.len();
        let w = window.min(count).min(MAX_RESUME_TAIL);
        let p = count - w;
        let prefix_parts: Vec<&str> = held[..p]
            .iter()
            .map(|entry| digests[&entry.id].as_str())
            .collect();
        let tail = held[p..]
            .iter()
            .map(|entry| TranscriptResumeEntry {
                id: entry.id.clone(),
                digest: digests[&entry.id].clone(),
            })
            .collect();
        Some(Self {
            count,
            prefix: prefix_digest(&prefix_parts),
            tail,
        })
    }
}

/// Build the catch-up frame for a client that sent `resume`. Returns `None`
/// when the summary does not match (the caller sends an ordinary reset):
///
/// Let `n = resume.count`, `w = resume.tail.len()`, `p = n − w`,
/// `m = current.len()`. `None` when `w > n`, `w > MAX_RESUME_TAIL`, `p > m`,
/// `current_digests` is not aligned with `current`, a tail id repeats, or
/// `prefix_digest(current_digests[..p]) != resume.prefix`.
///
/// When the prefix matches, the client's list is known exactly:
/// `current[..p]` (identical) followed by the tail ids. `remove` = the tail
/// ids not present in `current`. Walking `current[p..]`, an entry is skipped
/// only when it is the next unconsumed kept entry, its digest is equal, and
/// its predecessor in the client's post-removal list equals the entry's
/// predecessor in `current` — then the client's copy is exactly in place.
/// Everything else is upserted after its `current` predecessor (an anchor
/// `apply_transcript_frame` always finds settled). A resume frame never uses
/// `append`: the engine does not know the client's text.
///
/// The same size rule as `diff_transcript` applies: a delta carrying most of
/// the transcript serializes like a reset but applies slower.
pub fn resume_frame(
    resume: &TranscriptResume,
    current: &[SessionMessageEntry],
    current_digests: &[String],
) -> Option<TranscriptFrame> {
    let n = resume.count;
    let w = resume.tail.len();
    let p = n.saturating_sub(w);
    let m = current.len();
    if w > n || w > MAX_RESUME_TAIL || p > m || current_digests.len() != m {
        return None;
    }
    let mut tail_ids = HashSet::with_capacity(w);
    if resume
        .tail
        .iter()
        .any(|item| !tail_ids.insert(item.id.as_str()))
    {
        return None;
    }
    if prefix_digest(&current_digests[..p]) != resume.prefix {
        return None;
    }

    // The client's list is now known exactly: current[..p] followed by the
    // tail ids. `remove` = the tail ids not present in current, in tail order;
    // `kept` = the tail minus remove, in order (the client's post-removal
    // list is current[..p] ++ kept).
    let current_ids: HashSet<&str> = current.iter().map(|e| e.id.as_str()).collect();
    let remove: Vec<String> = resume
        .tail
        .iter()
        .filter(|item| !current_ids.contains(item.id.as_str()))
        .map(|item| item.id.clone())
        .collect();
    let kept: Vec<&TranscriptResumeEntry> = resume
        .tail
        .iter()
        .filter(|item| current_ids.contains(item.id.as_str()))
        .collect();
    let kept_index: HashMap<&str, usize> = kept
        .iter()
        .enumerate()
        .map(|(i, item)| (item.id.as_str(), i))
        .collect();
    // Predecessor of kept[j] in the client's post-removal list: the previous
    // kept item, or current[p−1] for the first, or None when p == 0.
    let kept_pred = |j: usize| -> Option<&str> {
        if j == 0 {
            (p > 0).then(|| current[p - 1].id.as_str())
        } else {
            Some(kept[j - 1].id.as_str())
        }
    };

    let mut consumed = vec![false; kept.len()];
    let mut head = 0usize; // next unconsumed kept entry
    let mut upsert = Vec::new();
    for i in p..m {
        let after = (i > 0).then(|| current[i - 1].id.as_str());
        let entry = &current[i];
        while head < kept.len() && consumed[head] {
            head += 1;
        }
        let skip = head < kept.len()
            && kept[head].id == entry.id
            && kept[head].digest == current_digests[i]
            && kept_pred(head) == after;
        if skip {
            consumed[head] = true;
            head += 1;
        } else {
            if let Some(&j) = kept_index.get(entry.id.as_str()) {
                consumed[j] = true;
            }
            upsert.push(TranscriptUpsert {
                after: after.map(str::to_string),
                entry: entry.clone(),
            });
        }
    }

    // A delta touching most rows serializes like a reset but applies slower.
    if upsert.len() * 2 >= m.max(1) && m > 4 {
        return None;
    }
    Some(TranscriptFrame::Delta {
        upsert,
        append: Vec::new(),
        remove,
        count: m,
    })
}

/// Keep the client's digest map in step with an applied update. Reset:
/// replace the map. Delta: drop `remove` ids, insert `update.digests`.
///
/// Returns `false` when the update changed entries but carried no digests
/// (an older engine): the map is cleared and the caller must forget resume
/// state (a copy without digests is never resumable).
pub fn apply_update_digests(
    held: &mut HashMap<String, String>,
    update: &TranscriptUpdate,
) -> bool {
    match &update.frame {
        TranscriptFrame::Reset { .. } => match &update.digests {
            Some(digests) => {
                *held = digests.iter().map(|(k, v)| (k.clone(), v.clone())).collect();
                true
            }
            None => {
                held.clear();
                false
            }
        },
        TranscriptFrame::Delta {
            upsert, append, remove, ..
        } => {
            for id in remove {
                held.remove(id);
            }
            let changed = !upsert.is_empty() || !append.is_empty();
            match &update.digests {
                Some(digests) => {
                    // A partial digest map would leave stale values for
                    // changed entries and silently corrupt a later resume;
                    // forget the state instead.
                    if upsert.iter().any(|u| !digests.contains_key(&u.entry.id))
                        || append.iter().any(|a| !digests.contains_key(&a.entry))
                    {
                        held.clear();
                        return false;
                    }
                    held.extend(digests.clone());
                    true
                }
                None if changed => {
                    held.clear();
                    false
                }
                None => true,
            }
        }
    }
}

/// Digest map for a frame, as attached to `TranscriptUpdate.digests`:
/// a reset carries the digest of every entry; a delta the digests of the
/// entries it upserts or appends to. `precomputed` (aligned with `entries`,
/// available on the first frame) avoids rehashing the whole transcript.
pub fn frame_digests(
    frame: &TranscriptFrame,
    entries: &[SessionMessageEntry],
    precomputed: Option<&[String]>,
) -> BTreeMap<String, String> {
    let mut map = BTreeMap::new();
    match frame {
        TranscriptFrame::Reset { reset } => match precomputed {
            Some(all) if all.len() == reset.len() => {
                for (entry, digest) in reset.iter().zip(all) {
                    map.insert(entry.id.clone(), digest.clone());
                }
            }
            _ => {
                for entry in reset {
                    map.insert(entry.id.clone(), entry_digest(entry));
                }
            }
        },
        TranscriptFrame::Delta { upsert, append, .. } => {
            for TranscriptUpsert { entry, .. } in upsert {
                map.insert(entry.id.clone(), entry_digest(entry));
            }
            for append_id in append.iter().map(|a| &a.entry) {
                if !map.contains_key(append_id)
                    && let Some(found) = entries.iter().find(|e| &e.id == append_id)
                {
                    map.insert(append_id.clone(), entry_digest(found));
                }
            }
        }
    }
    map
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parts::MessagePart;
    use crate::schema::MessageRole;
    use crate::transcript_delta::{TextAppend, apply_transcript_frame, diff_transcript};

    fn entry(id: &str, text: &str) -> SessionMessageEntry {
        SessionMessageEntry {
            origin_chat_id: None,
            id: id.into(),
            role: MessageRole::Assistant,
            parts: vec![MessagePart::Text {
                id: "t0".into(),
                text: text.into(),
            }],
            created_at: 0,
            device_id: "dev".into(),
            status: None,
            continuation_of: None,
            duration_ms: None,
        }
    }

    fn digests_of(entries: &[SessionMessageEntry]) -> HashMap<String, String> {
        entries
            .iter()
            .map(|e| (e.id.clone(), entry_digest(e)))
            .collect()
    }

    fn delta_of(frame: &TranscriptFrame) -> (&Vec<TranscriptUpsert>, &Vec<TextAppend>, &Vec<String>, usize) {
        match frame {
            TranscriptFrame::Delta { upsert, append, remove, count } => (upsert, append, remove, *count),
            other => panic!("expected delta, got {other:?}"),
        }
    }

    #[test]
    fn entry_digest_is_stable_and_sensitive() {
        let a = entry("a", "hello");
        assert_eq!(entry_digest(&a), entry_digest(&a));
        // Round trip through JSON (an independently decoded copy) is stable:
        // digests stay valid after any serialization hop.
        let json = serde_json::to_vec(&a).unwrap();
        let decoded: SessionMessageEntry = serde_json::from_slice(&json).unwrap();
        assert_eq!(entry_digest(&a), entry_digest(&decoded));
        assert_eq!(entry_digest(&a).len(), 16);

        let mut b = entry("a", "hellx");
        assert_ne!(entry_digest(&a), entry_digest(&b));
        b = entry("b", "hello");
        assert_ne!(entry_digest(&a), entry_digest(&b));
        let mut status = entry("a", "hello");
        status.status = Some(crate::MessageStatus::Streaming);
        assert_ne!(entry_digest(&a), entry_digest(&status));
        let mut tool = entry("a", "hello");
        tool.parts.push(MessagePart::Tool {
            id: "p1".into(),
            call: roboco_proto::ToolCall::Exec { command: "ls".into() },
            is_error: false,
            resolved: false,
            output: None,
            diff: None,
            output_ref: None,
            output_bytes: None,
            diff_ref: None,
            diff_stats: None,
            subagent_ref: None,
            subagent_status: None,
            subagent_tail: None,
        });
        let digest = entry_digest(&tool);
        if let MessagePart::Tool { resolved, .. } = tool.parts.last_mut().unwrap() {
            *resolved = true;
        }
        assert_ne!(digest, entry_digest(&tool));
    }

    #[test]
    fn prefix_digest_empty_is_the_documented_constant() {
        let empty: Vec<&str> = Vec::new();
        assert_eq!(prefix_digest(&empty), "e3b0c44298fc1c149afbf4c8996fb924");
        // The empty digest is SHA-256 of the empty byte string, first 16 bytes.
        assert_eq!(
            hex_of(&Sha256::digest(b"")[..16]),
            "e3b0c44298fc1c149afbf4c8996fb924"
        );
        // Order matters: the digest sequence is positional.
        let one = [entry_digest(&entry("a", "x"))];
        let two = [entry_digest(&entry("b", "y"))];
        assert_ne!(prefix_digest(&one), prefix_digest(&two));
        assert_eq!(prefix_digest(&one), prefix_digest(&one));
        assert_eq!(prefix_digest(&[one[0].clone()]).len(), 32);
    }

    #[test]
    fn from_held_requires_digests_for_every_entry() {
        let held: Vec<_> = (0..10).map(|i| entry(&format!("e{i}"), "x")).collect();
        let digests = digests_of(&held);
        let resume = TranscriptResume::from_held(&held, &digests, 4).unwrap();
        assert_eq!(resume.count, 10);
        assert_eq!(resume.tail.len(), 4);
        assert_eq!(resume.tail[0].id, "e6");
        assert_eq!(
            resume.prefix,
            prefix_digest(&held[..6].iter().map(|e| entry_digest(e)).collect::<Vec<_>>())
        );
        // Any missing digest → not resumable at all.
        let mut partial = digests.clone();
        partial.remove("e0");
        assert!(TranscriptResume::from_held(&held, &partial, 4).is_none());
        // Window clamps to the held length and to the wire bound.
        let wide = TranscriptResume::from_held(&held, &digests, 512).unwrap();
        assert_eq!(wide.tail.len(), 10);
        let over = TranscriptResume::from_held(&held, &digests, 513).unwrap();
        assert_eq!(over.tail.len(), 512.min(10));
        // An empty copy is a valid (trivial) resume.
        let empty = TranscriptResume::from_held(&[], &HashMap::new(), 64).unwrap();
        assert_eq!(empty.count, 0);
        assert_eq!(empty.prefix, prefix_digest(&Vec::<&str>::new()));
        assert!(empty.tail.is_empty());
    }

    fn roundtrip(frame: TranscriptFrame) -> TranscriptFrame {
        serde_json::from_value(serde_json::to_value(&frame).unwrap()).unwrap()
    }

    fn assert_resume_roundtrip(
        held: &[SessionMessageEntry],
        next: &[SessionMessageEntry],
        window: usize,
    ) -> Option<TranscriptFrame> {
        let digests = digests_of(held);
        let resume = TranscriptResume::from_held(held, &digests, window)?;
        let next_digests: Vec<String> = next.iter().map(entry_digest).collect();
        let frame = resume_frame(&resume, next, &next_digests);
        if let Some(frame) = &frame {
            // The wire shape must survive serde.
            let frame = roundtrip(frame.clone());
            let mut current = held.to_vec();
            apply_transcript_frame(&mut current, frame).unwrap();
            assert_eq!(&current, next);
        }
        frame
    }

    #[test]
    fn resume_frame_nothing_changed() {
        let held: Vec<_> = (0..8).map(|i| entry(&format!("e{i}"), "x")).collect();
        let frame = assert_resume_roundtrip(&held, &held, 3).unwrap();
        let (upsert, append, remove, count) = delta_of(&frame);
        assert!(upsert.is_empty());
        assert!(append.is_empty());
        assert!(remove.is_empty());
        assert_eq!(count, 8);
    }

    #[test]
    fn resume_frame_appended_entries_are_upserts_in_order() {
        let held: Vec<_> = (0..8).map(|i| entry(&format!("e{i}"), "x")).collect();
        let mut next = held.clone();
        next.push(entry("a1", "new"));
        next.push(entry("a2", "new2"));
        next.push(entry("a3", "new3"));
        let frame = assert_resume_roundtrip(&held, &next, 3).unwrap();
        let (upsert, _, _, count) = delta_of(&frame);
        assert_eq!(upsert.len(), 3);
        assert_eq!(upsert[0].after.as_deref(), Some("e7"));
        assert_eq!(upsert[1].after.as_deref(), Some("a1"));
        assert_eq!(upsert[2].after.as_deref(), Some("a2"));
        assert_eq!(count, 11);
    }

    #[test]
    fn resume_frame_text_growth_is_one_upsert() {
        let held: Vec<_> = (0..8).map(|i| entry(&format!("e{i}"), "x")).collect();
        let mut next = held.clone();
        next[7] = entry("e7", "x grown while away");
        let frame = assert_resume_roundtrip(&held, &next, 3).unwrap();
        let (upsert, _, _, _) = delta_of(&frame);
        assert_eq!(upsert.len(), 1);
        assert_eq!(upsert[0].entry.id, "e7");
    }

    #[test]
    fn resume_frame_removed_tail_entry() {
        let held: Vec<_> = (0..8).map(|i| entry(&format!("e{i}"), "x")).collect();
        let mut next = held.clone();
        next.remove(6);
        let frame = assert_resume_roundtrip(&held, &next, 3).unwrap();
        let (upsert, _, remove, count) = delta_of(&frame);
        assert!(upsert.is_empty());
        assert_eq!(remove, &vec!["e6".to_string()]);
        assert_eq!(count, 7);
    }

    #[test]
    fn resume_frame_mid_tail_insert_matches_diff_transcript() {
        let held: Vec<_> = (0..8).map(|i| entry(&format!("e{i}"), "x")).collect();
        let mut next = held.clone();
        next.insert(6, entry("mid", "inserted"));
        let frame = assert_resume_roundtrip(&held, &next, 3).unwrap();
        let (upsert, _, _, _) = delta_of(&frame);
        // The insert plus its displaced successor, like diff_transcript.
        let diff = diff_transcript(&held, &next);
        let (diff_upsert, _, _, _) = delta_of(&diff);
        assert_eq!(
            upsert.iter().map(|u| u.entry.id.clone()).collect::<Vec<_>>(),
            diff_upsert.iter().map(|u| u.entry.id.clone()).collect::<Vec<_>>()
        );
    }

    #[test]
    fn resume_frame_refuses_bad_summaries() {
        let held: Vec<_> = (0..8).map(|i| entry(&format!("e{i}"), "x")).collect();
        let digests = digests_of(&held);
        let next: Vec<_> = (0..8).map(|i| entry(&format!("e{i}"), "x")).collect();
        let next_digests: Vec<String> = next.iter().map(entry_digest).collect();

        // Prefix mismatch.
        let mut resume = TranscriptResume::from_held(&held, &digests, 3).unwrap();
        let flipped = if resume.prefix.starts_with('0') {
            resume.prefix.replacen('0', "1", 1)
        } else {
            resume.prefix.replacen(|c: char| c != '0', "0", 1)
        };
        resume.prefix = flipped;
        assert!(resume_frame(&resume, &next, &next_digests).is_none());

        // p > m: the client holds more entries than exist now.
        let long: Vec<_> = (0..20).map(|i| entry(&format!("h{i}"), "x")).collect();
        let long_digests = digests_of(&long);
        let resume = TranscriptResume::from_held(&long, &long_digests, 0).unwrap();
        assert!(resume_frame(&resume, &next, &next_digests).is_none());

        // w > 512 and w > n.
        let mut wide = TranscriptResume::from_held(&held, &digests, 3).unwrap();
        wide.tail = (0..513).map(|i| TranscriptResumeEntry {
            id: format!("x{i}"),
            digest: "0".repeat(16),
        }).collect();
        assert!(resume_frame(&wide, &next, &next_digests).is_none());
        let mut over_count = resume.clone();
        over_count.count = 2; // w = 3 > n = 2
        assert!(resume_frame(&over_count, &next, &next_digests).is_none());

        // Duplicate tail ids.
        let mut dup = TranscriptResume::from_held(&held, &digests, 3).unwrap();
        let second_id = dup.tail[1].id.clone();
        dup.tail[0].id = second_id;
        assert!(resume_frame(&dup, &next, &next_digests).is_none());

        // Unaligned digests.
        assert!(resume_frame(&resume, &next, &next_digests[..3]).is_none());

        // Large divergence: more than half the rows are new.
        let fresh: Vec<_> = (0..10).map(|i| entry(&format!("f{i}"), "y")).collect();
        let fresh_digests: Vec<String> = fresh.iter().map(entry_digest).collect();
        let empty_resume = TranscriptResume::from_held(&[], &HashMap::new(), 0).unwrap();
        assert!(resume_frame(&empty_resume, &fresh, &fresh_digests).is_none());
    }

    #[test]
    fn resume_frame_whole_list_is_tail() {
        let held: Vec<_> = (0..5).map(|i| entry(&format!("e{i}"), "x")).collect();
        let mut next = held.clone();
        next.push(entry("a", "new"));
        next[3] = entry("e3", "edited");
        let frame = assert_resume_roundtrip(&held, &next, 5).unwrap();
        let (upsert, _, _, count) = delta_of(&frame);
        assert_eq!(upsert.len(), 2);
        assert_eq!(count, 6);
    }

    #[test]
    fn resume_frame_matches_diff_transcript_on_small_edits() {
        let held: Vec<_> = (0..6).map(|i| entry(&format!("e{i}"), "x")).collect();
        let mut next = held.clone();
        next[5] = entry("e5", "streamed more");
        let frame = assert_resume_roundtrip(&held, &next, 2).unwrap();
        let (upsert, _, _, _) = delta_of(&frame);
        let diff = diff_transcript(&held, &next);
        let (diff_upsert, _, _, _) = delta_of(&diff);
        assert_eq!(upsert.len(), diff_upsert.len());
    }

    /// Deterministic LCG (no new test dependency).
    struct Lcg(u64);
    impl Lcg {
        fn next(&mut self) -> u64 {
            self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            self.0 >> 16
        }
        fn below(&mut self, bound: usize) -> usize {
            (self.next() % bound.max(1) as u64) as usize
        }
        fn bool(&mut self) -> bool {
            self.next() & 1 == 0
        }
    }

    /// 500 deterministic random rounds: derive B from A by random appends,
    /// removes, edits, inserts, status changes and moves; resume A onto B.
    /// When `resume_frame` returns a frame, applying it to A must yield B.
    /// When it declines, the prefix must really differ or the size rule must
    /// be possible (m > 4).
    #[test]
    fn resume_frame_property_applies_exactly() {
        let mut rng = Lcg(0x5eed_cafe_f00d_1234);
        let mut declines = 0;
        for _round in 0..500 {
            let n = rng.below(41);
            let mut a: Vec<SessionMessageEntry> = (0..n)
                .map(|i| {
                    let mut e = entry(&format!("e{i}"), &format!("text {i}"));
                    e.created_at = i as i64;
                    e
                })
                .collect();
            let mut next_id = n;

            // Mutate a copy of A into B.
            let mut b = a.clone();
            let mutations = rng.below(6) + 1;
            for _ in 0..mutations {
                match rng.below(6) {
                    0 => {
                        // append
                        next_id += 1;
                        b.push(entry(&format!("e{next_id}"), "appended"));
                    }
                    1 if !b.is_empty() => {
                        // remove
                        b.remove(rng.below(b.len()));
                    }
                    2 if !b.is_empty() => {
                        // edit (prefix growth or rewrite)
                        let i = rng.below(b.len());
                        let old = match &b[i].parts[0] {
                            MessagePart::Text { text, .. } => text.clone(),
                            _ => String::new(),
                        };
                        let text = if rng.bool() {
                            format!("{old} more")
                        } else {
                            "rewritten".into()
                        };
                        b[i] = entry(&b[i].id, &text);
                    }
                    3 if !b.is_empty() => {
                        // mid insert
                        next_id += 1;
                        let at = rng.below(b.len() + 1);
                        b.insert(at, entry(&format!("e{next_id}"), "inserted"));
                    }
                    4 if !b.is_empty() => {
                        // status change
                        let i = rng.below(b.len());
                        let mut e = b[i].clone();
                        e.status = Some(crate::MessageStatus::Streaming);
                        b[i] = e;
                    }
                    5 if b.len() > 1 => {
                        // move (a Loro list merge can reorder entries)
                        let from = rng.below(b.len());
                        let e = b.remove(from);
                        let to = rng.below(b.len() + 1);
                        b.insert(to, e);
                    }
                    _ => {}
                }
            }
            if b.is_empty() {
                continue;
            }

            let digests = digests_of(&a);
            let w = rng.below(a.len() + 1);
            let Some(resume) = TranscriptResume::from_held(&a, &digests, w) else {
                panic!("from_held must succeed for a complete digest map");
            };
            let b_digests: Vec<String> = b.iter().map(entry_digest).collect();
            let frame = resume_frame(&resume, &b, &b_digests);
            match frame {
                Some(frame) => {
                    let frame = roundtrip(frame);
                    let mut current = a.clone();
                    apply_transcript_frame(&mut current, frame).unwrap();
                    assert_eq!(&current, &b);
                }
                None => {
                    declines += 1;
                    let p = resume.count.saturating_sub(resume.tail.len());
                    let prefix_matches = p <= b.len()
                        && prefix_digest(&b_digests[..p]) == resume.prefix;
                    assert!(
                        p > b.len() || !prefix_matches || b.len() > 4,
                        "decline requires a real prefix mismatch, p > m, or the size rule (m > 4): p={p} m={} prefix_match={prefix_matches}",
                        b.len(),
                    );
                }
            }
        }
        assert!(declines > 0, "the rounds must exercise decline paths");
    }

    #[test]
    fn apply_update_digests_tracks_reset_and_delta() {
        let entries: Vec<_> = (0..4).map(|i| entry(&format!("e{i}"), "x")).collect();
        let digests = digests_of(&entries);
        let map: HashMap<String, String> = {
            let mut m = HashMap::new();
            m.insert("old".into(), "0".repeat(16));
            m
        };

        // Reset replaces the map.
        let mut held = map.clone();
        let reset = TranscriptUpdate {
            frame: TranscriptFrame::reset(&entries),
            context_usage: None,
            replay_baseline: None,
            digests: Some(
                entries
                    .iter()
                    .map(|e| (e.id.clone(), digests[&e.id].clone()))
                    .collect(),
            ),
        };
        assert!(apply_update_digests(&mut held, &reset));
        assert_eq!(held, digests);

        // Delta: removes drop, upsert digests insert.
        let mut next = entries.clone();
        next.push(entry("e4", "new"));
        let frame = diff_transcript(&entries, &next);
        let (upsert, _, _, _) = delta_of(&frame);
        let mut delta_digests = BTreeMap::new();
        for u in upsert {
            delta_digests.insert(u.entry.id.clone(), entry_digest(&u.entry));
        }
        let delta = TranscriptUpdate {
            frame: frame.clone(),
            context_usage: None,
            replay_baseline: None,
            digests: Some(delta_digests),
        };
        assert!(apply_update_digests(&mut held, &delta));
        let mut expected = digests_of(&next);
        expected.remove("old");
        assert_eq!(held, expected);

        // A delta that changed entries but carried no digests: forget.
        let mut stale = expected.clone();
        let bare = TranscriptUpdate {
            frame,
            context_usage: None,
            replay_baseline: None,
            digests: None,
        };
        assert!(!apply_update_digests(&mut stale, &bare));
        assert!(stale.is_empty());

        // A reset without digests (an older engine): forget.
        let mut old_engine = expected.clone();
        let bare_reset = TranscriptUpdate {
            frame: TranscriptFrame::reset(&[]),
            context_usage: None,
            replay_baseline: None,
            digests: None,
        };
        assert!(!apply_update_digests(&mut old_engine, &bare_reset));
        assert!(old_engine.is_empty());

        // A remove-only delta without digests keeps the map (nothing changed
        // that needs a digest) and drops the removed ids.
        let mut removed = expected.clone();
        removed.remove("e4");
        let mut two = next.clone();
        two.pop();
        let bare_remove = TranscriptUpdate {
            frame: diff_transcript(&next, &two),
            context_usage: None,
            replay_baseline: None,
            digests: None,
        };
        assert!(apply_update_digests(&mut removed, &bare_remove));
        assert!(removed.contains_key("e0"));
        assert!(!removed.contains_key("e4"));
    }

    #[test]
    fn frame_digests_covers_reset_and_delta_ids() {
        let entries: Vec<_> = (0..4).map(|i| entry(&format!("e{i}"), "x")).collect();
        let all = entry_digests(&entries);
        // Reset: every entry, reusing precomputed digests.
        let reset = TranscriptFrame::reset(&entries);
        let map = frame_digests(&reset, &entries, Some(&all));
        assert_eq!(map.len(), 4);
        assert_eq!(map["e2"], all[2]);
        // Reset without precomputed: recomputed.
        assert_eq!(frame_digests(&reset, &entries, None), map);

        // Delta: exactly the upserted and appended ids.
        let mut next = entries.clone();
        next[3] = entry("e3", "grown");
        let mut streamed = next[3].clone();
        streamed.parts = vec![MessagePart::Text {
            id: "t0".into(),
            text: "grown longer".into(),
        }];
        next[3] = streamed;
        let delta = diff_transcript(&entries, &next);
        let (upsert, _, _, _) = delta_of(&delta);
        assert_eq!(upsert.len(), 1);
        let map = frame_digests(&delta, &next, None);
        assert_eq!(map.len(), 1);
        assert_eq!(map["e3"], entry_digest(&next[3]));

        // A text append rides the hot path: the delta carries the entry's id.
        let mut grown = next.clone();
        if let MessagePart::Text { text, .. } = &mut grown[3].parts[0] {
            text.push_str(" even longer");
        }
        let append_frame = diff_transcript(&next, &grown);
        let (_, appends, _, _) = delta_of(&append_frame);
        assert_eq!(appends.len(), 1);
        let map = frame_digests(&append_frame, &grown, None);
        assert_eq!(map.len(), 1);
        assert_eq!(map["e3"], entry_digest(&grown[3]));
    }
}
