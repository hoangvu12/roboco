use crate::limits::{CONTROL_HIGH_WATER_BYTES, EVENT_HIGH_WATER_BYTES, Limits};
use crate::wire::{Frame, notification};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, VecDeque};

pub const FRAGMENT_METHOD: &str = "bridge.fragment";

/// One piece of a frame that did not fit the frame budget. Concatenating the
/// decoded `data` of fragments `0..count` yields the original frame exactly.
#[derive(Debug, Serialize, Deserialize)]
pub struct Fragment {
    pub transfer: u64,
    pub index: u32,
    pub count: u32,
    pub total_bytes: usize,
    pub data: String,
}

#[derive(Debug)]
struct Transfer {
    id: u64,
    frame: Frame,
    offset: usize,
    index: u32,
    count: u32,
}

#[derive(Debug)]
enum Item {
    Whole(Frame),
    Transfer(Transfer),
}

/// Two outgoing lanes behind one writer.
///
/// Control frames (responses and errors) always go before events, and large
/// control transfers take turns with small frames, so a request is never
/// stuck behind someone else's big result. Events keep strict order.
#[derive(Debug)]
pub struct Outbox {
    limits: Limits,
    control: VecDeque<Item>,
    control_bytes: usize,
    events: VecDeque<Item>,
    event_bytes: usize,
    next_transfer: u64,
}

impl Outbox {
    pub fn new(limits: Limits) -> Self {
        Self {
            limits,
            control: VecDeque::new(),
            control_bytes: 0,
            events: VecDeque::new(),
            event_bytes: 0,
            next_transfer: 1,
        }
    }

    pub fn set_limits(&mut self, limits: Limits) {
        self.limits = limits;
    }

    pub fn push_control(&mut self, frame: Frame) {
        self.control_bytes += frame.len();
        let item = self.item(frame);
        self.control.push_back(item);
    }

    pub fn push_event(&mut self, frame: Frame) {
        self.event_bytes += frame.len();
        let item = self.item(frame);
        self.events.push_back(item);
    }

    fn item(&mut self, frame: Frame) -> Item {
        if frame.len() <= self.limits.max_frame_bytes {
            return Item::Whole(frame);
        }
        let id = self.next_transfer;
        self.next_transfer += 1;
        let count = frame.len().div_ceil(self.limits.chunk_bytes);
        Item::Transfer(Transfer {
            id,
            frame,
            offset: 0,
            index: 0,
            count: u32::try_from(count).expect("a frame has fewer than 2^32 fragments"),
        })
    }

    pub fn is_empty(&self) -> bool {
        self.control.is_empty() && self.events.is_empty()
    }

    pub fn events_saturated(&self) -> bool {
        self.event_bytes >= EVENT_HIGH_WATER_BYTES
    }

    pub fn control_saturated(&self) -> bool {
        self.control_bytes >= CONTROL_HIGH_WATER_BYTES
    }

    pub fn queued_bytes(&self) -> usize {
        self.control_bytes + self.event_bytes
    }

    /// The next line to write, without its newline.
    pub fn next_line(&mut self) -> Option<Frame> {
        if let Some(item) = self.control.pop_front() {
            return Some(match item {
                Item::Whole(frame) => {
                    self.control_bytes -= frame.len();
                    frame
                }
                Item::Transfer(mut transfer) => {
                    let (line, raw) = fragment(&mut transfer, self.limits.chunk_bytes);
                    self.control_bytes -= raw;
                    if transfer.offset < transfer.frame.len() {
                        self.control.push_back(Item::Transfer(transfer));
                    }
                    line
                }
            });
        }
        match self.events.front_mut()? {
            Item::Whole(_) => {
                let Some(Item::Whole(frame)) = self.events.pop_front() else {
                    unreachable!()
                };
                self.event_bytes -= frame.len();
                Some(frame)
            }
            Item::Transfer(transfer) => {
                let (line, raw) = fragment(transfer, self.limits.chunk_bytes);
                self.event_bytes -= raw;
                if transfer.offset >= transfer.frame.len() {
                    self.events.pop_front();
                }
                Some(line)
            }
        }
    }

    /// Newline-terminated lines totalling at least `target` bytes unless the
    /// queues run out first; empty when nothing is queued.
    pub fn next_write(&mut self, target: usize) -> Vec<u8> {
        let mut out = Vec::new();
        while out.len() < target {
            let Some(line) = self.next_line() else { break };
            out.extend_from_slice(&line);
            out.push(b'\n');
        }
        out
    }
}

fn fragment(transfer: &mut Transfer, chunk_bytes: usize) -> (Frame, usize) {
    let end = (transfer.offset + chunk_bytes).min(transfer.frame.len());
    let raw = end - transfer.offset;
    let piece = Fragment {
        transfer: transfer.id,
        index: transfer.index,
        count: transfer.count,
        total_bytes: transfer.frame.len(),
        data: STANDARD.encode(&transfer.frame[transfer.offset..end]),
    };
    transfer.offset = end;
    transfer.index += 1;
    let line = notification(FRAGMENT_METHOD, &piece).expect("a fragment always serializes");
    (line, raw)
}

#[derive(Debug, PartialEq, Eq)]
pub enum Fed {
    Frame(Frame),
    Pending,
}

/// Rebuilds frames from `bridge.fragment` notifications. The engine client
/// does the same; this is the reference the native tests and the transport
/// harness check against.
#[derive(Debug, Default)]
pub struct Reassembler {
    partial: HashMap<u64, Partial>,
}

#[derive(Debug)]
struct Partial {
    count: u32,
    total_bytes: usize,
    next: u32,
    bytes: Vec<u8>,
}

impl Reassembler {
    pub fn feed(&mut self, line: &[u8]) -> Result<Fed, String> {
        if !contains(line, FRAGMENT_METHOD.as_bytes()) {
            return Ok(Fed::Frame(line.to_vec()));
        }
        #[derive(Deserialize)]
        struct Envelope {
            method: Option<String>,
            params: Option<Fragment>,
        }
        let Ok(envelope) = serde_json::from_slice::<Envelope>(line) else {
            return Ok(Fed::Frame(line.to_vec()));
        };
        if envelope.method.as_deref() != Some(FRAGMENT_METHOD) {
            return Ok(Fed::Frame(line.to_vec()));
        }
        let piece = envelope.params.ok_or("fragment without params")?;
        let bytes = STANDARD.decode(&piece.data).map_err(|error| error.to_string())?;
        let partial = self.partial.entry(piece.transfer).or_insert_with(|| Partial {
            count: piece.count,
            total_bytes: piece.total_bytes,
            next: 0,
            bytes: Vec::with_capacity(piece.total_bytes),
        });
        if piece.index != partial.next
            || piece.count != partial.count
            || piece.total_bytes != partial.total_bytes
        {
            return Err(format!("fragment {} of transfer {} is out of order", piece.index, piece.transfer));
        }
        partial.bytes.extend_from_slice(&bytes);
        partial.next += 1;
        if partial.next < partial.count {
            return Ok(Fed::Pending);
        }
        let done = self.partial.remove(&piece.transfer).expect("present above");
        if done.bytes.len() != done.total_bytes {
            return Err(format!(
                "transfer {} rebuilt {} bytes, expected {}",
                piece.transfer,
                done.bytes.len(),
                done.total_bytes
            ));
        }
        Ok(Fed::Frame(done.bytes))
    }

    pub fn in_flight(&self) -> usize {
        self.partial.len()
    }
}

fn contains(haystack: &[u8], needle: &[u8]) -> bool {
    haystack.windows(needle.len()).any(|window| window == needle)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{Value, json};

    fn limits(max_frame_bytes: usize, chunk_bytes: usize) -> Limits {
        Limits { max_frame_bytes, chunk_bytes }
    }

    fn big_frame(payload_bytes: usize) -> Frame {
        let payload = "x".repeat(payload_bytes);
        serde_json::to_vec(&json!({"jsonrpc":"2.0","id":1,"result":{"p":payload}})).unwrap()
    }

    fn drain(outbox: &mut Outbox) -> Vec<Frame> {
        std::iter::from_fn(|| outbox.next_line()).collect()
    }

    #[test]
    fn small_frames_pass_through_unchanged() {
        let mut outbox = Outbox::new(Limits::default());
        outbox.push_control(b"{\"a\":1}".to_vec());
        outbox.push_control(b"{\"b\":2}".to_vec());
        assert_eq!(drain(&mut outbox), vec![b"{\"a\":1}".to_vec(), b"{\"b\":2}".to_vec()]);
        assert!(outbox.is_empty());
        assert_eq!(outbox.queued_bytes(), 0);
    }

    #[test]
    fn a_frame_at_the_budget_is_whole_and_one_byte_over_is_fragmented() {
        let mut outbox = Outbox::new(limits(65_536, 4096));
        outbox.push_control(vec![b'a'; 65_536]);
        outbox.push_control(vec![b'a'; 65_537]);
        let lines = drain(&mut outbox);
        assert_eq!(lines[0].len(), 65_536);
        assert!(lines.len() > 2);
    }

    #[test]
    fn ten_mebibytes_reassemble_exactly_with_every_line_inside_the_budget() {
        for (budget, chunk) in [(65_536, 32_768), (1 << 20, 256 << 10), (16 << 20, 256 << 10), (1 << 20, 1024)] {
            let original = big_frame(10 << 20);
            let mut outbox = Outbox::new(limits(budget, chunk));
            outbox.push_control(original.clone());
            let mut reassembler = Reassembler::default();
            let mut rebuilt = None;
            let mut lines = 0;
            while let Some(line) = outbox.next_line() {
                assert!(line.len() <= budget, "line of {} exceeds {budget}", line.len());
                assert!(!line.contains(&b'\n'));
                lines += 1;
                if let Fed::Frame(frame) = reassembler.feed(&line).unwrap() {
                    assert!(rebuilt.replace(frame).is_none());
                }
            }
            assert_eq!(rebuilt.as_deref(), Some(original.as_slice()), "budget {budget} chunk {chunk}");
            assert_eq!(reassembler.in_flight(), 0);
            let expected = if original.len() > budget { original.len().div_ceil(chunk) } else { 1 };
            assert_eq!(lines, expected);
            assert_eq!(outbox.queued_bytes(), 0);
        }
    }

    #[test]
    fn json_escaping_expansion_is_fragmented_not_truncated() {
        let raw = "\u{1}".repeat(256 << 10);
        let frame = serde_json::to_vec(&json!({"jsonrpc":"2.0","id":2,"result":{"text":raw}})).unwrap();
        assert!(frame.len() > 5 * (256 << 10), "control bytes expand six-fold");
        let mut outbox = Outbox::new(limits(65_536, 16_384));
        outbox.push_control(frame.clone());
        let mut reassembler = Reassembler::default();
        let mut rebuilt = None;
        while let Some(line) = outbox.next_line() {
            assert!(line.len() <= 65_536);
            if let Fed::Frame(done) = reassembler.feed(&line).unwrap() {
                rebuilt = Some(done);
            }
        }
        let value: Value = serde_json::from_slice(&rebuilt.unwrap()).unwrap();
        assert_eq!(value["result"]["text"].as_str().unwrap(), raw);
    }

    #[test]
    fn control_goes_before_events_and_a_small_frame_overtakes_a_big_transfer() {
        let mut outbox = Outbox::new(limits(65_536, 16_384));
        outbox.push_event(b"{\"event\":1}".to_vec());
        outbox.push_control(big_frame(200_000));
        outbox.push_control(b"{\"small\":true}".to_vec());
        let first = outbox.next_line().unwrap();
        assert!(String::from_utf8_lossy(&first).contains(FRAGMENT_METHOD));
        assert_eq!(outbox.next_line().unwrap(), b"{\"small\":true}");
        let rest = drain(&mut outbox);
        assert!(rest.iter().take(rest.len() - 1).all(|l| String::from_utf8_lossy(l).contains(FRAGMENT_METHOD)));
        assert_eq!(rest.last().unwrap(), b"{\"event\":1}");
    }

    #[test]
    fn two_big_control_transfers_alternate_fragments() {
        let mut outbox = Outbox::new(limits(65_536, 16_384));
        outbox.push_control(big_frame(100_000));
        outbox.push_control(big_frame(100_000));
        let ids: Vec<u64> = drain(&mut outbox)
            .iter()
            .map(|line| serde_json::from_slice::<Value>(line).unwrap()["params"]["transfer"].as_u64().unwrap())
            .collect();
        assert_eq!(&ids[..4], &[1, 2, 1, 2]);
    }

    #[test]
    fn events_stay_in_order_even_when_one_is_fragmented() {
        let mut outbox = Outbox::new(limits(65_536, 16_384));
        let make = |n: u32, bytes: usize| {
            serde_json::to_vec(&json!({"jsonrpc":"2.0","method":"view.event","params":{"n":n,"p":"y".repeat(bytes)}})).unwrap()
        };
        outbox.push_event(make(1, 10));
        outbox.push_event(make(2, 150_000));
        outbox.push_event(make(3, 10));
        let mut reassembler = Reassembler::default();
        let mut seen = Vec::new();
        while let Some(line) = outbox.next_line() {
            if let Fed::Frame(frame) = reassembler.feed(&line).unwrap() {
                seen.push(serde_json::from_slice::<Value>(&frame).unwrap()["params"]["n"].as_u64().unwrap());
            }
        }
        assert_eq!(seen, vec![1, 2, 3]);
    }

    #[test]
    fn control_fragments_may_interleave_with_a_fragmented_event_without_mixing_them() {
        let mut outbox = Outbox::new(limits(65_536, 16_384));
        outbox.push_event(big_frame(100_000));
        let first = outbox.next_line().unwrap();
        outbox.push_control(b"{\"r\":1}".to_vec());
        assert_eq!(outbox.next_line().unwrap(), b"{\"r\":1}");
        let mut reassembler = Reassembler::default();
        assert_eq!(reassembler.feed(&first).unwrap(), Fed::Pending);
        let mut done = 0;
        while let Some(line) = outbox.next_line() {
            if matches!(reassembler.feed(&line).unwrap(), Fed::Frame(_)) {
                done += 1;
            }
        }
        assert_eq!(done, 1);
    }

    #[test]
    fn saturation_tracks_queued_bytes_and_clears_when_drained() {
        let mut outbox = Outbox::new(Limits::default());
        assert!(!outbox.events_saturated() && !outbox.control_saturated());
        let chunk = vec![b'e'; 900_000];
        for _ in 0..5 {
            outbox.push_event(chunk.clone());
        }
        assert!(outbox.events_saturated());
        assert!(!outbox.control_saturated());
        while outbox.next_line().is_some() {}
        assert!(!outbox.events_saturated());
        for _ in 0..9 {
            outbox.push_control(vec![b'c'; 1_000_000]);
        }
        assert!(outbox.control_saturated());
        drain(&mut outbox);
        assert!(!outbox.control_saturated());
    }

    #[test]
    fn next_write_batches_lines_with_newlines() {
        let mut outbox = Outbox::new(Limits::default());
        for n in 0..10 {
            outbox.push_control(format!("{{\"n\":{n}}}").into_bytes());
        }
        let batch = outbox.next_write(20);
        assert!(batch.ends_with(b"\n"));
        let lines = batch.split(|b| *b == b'\n').filter(|l| !l.is_empty()).count();
        assert!((2..10).contains(&lines));
        let rest = outbox.next_write(usize::MAX);
        assert_eq!(rest.iter().filter(|b| **b == b'\n').count() + lines, 10);
        assert!(outbox.next_write(1).is_empty());
    }

    #[test]
    fn a_frame_that_mentions_fragments_in_text_is_not_mistaken_for_one() {
        let frame = serde_json::to_vec(&json!({"jsonrpc":"2.0","id":1,"result":{"t":"bridge.fragment"}})).unwrap();
        let mut reassembler = Reassembler::default();
        assert_eq!(reassembler.feed(&frame).unwrap(), Fed::Frame(frame));
    }

    #[test]
    fn out_of_order_fragments_are_an_error() {
        let mut outbox = Outbox::new(limits(65_536, 16_384));
        outbox.push_control(big_frame(100_000));
        let lines = drain(&mut outbox);
        let mut reassembler = Reassembler::default();
        assert!(reassembler.feed(&lines[1]).is_err());
    }
}
