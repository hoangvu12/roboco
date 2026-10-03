#[derive(Debug, PartialEq, Eq)]
pub enum Line {
    Frame(Vec<u8>),
    /// A line passed `max` bytes. It is dropped through its newline; later
    /// lines are unaffected.
    Oversized,
}

/// Splits a byte stream into newline-delimited frames of at most `max` bytes
/// each, however the reads fragment the input.
#[derive(Debug)]
pub struct LineSplitter {
    max: usize,
    buffer: Vec<u8>,
    discarding: bool,
}

impl LineSplitter {
    pub fn new(max: usize) -> Self {
        Self {
            max,
            buffer: Vec::new(),
            discarding: false,
        }
    }

    pub fn set_max(&mut self, max: usize) {
        self.max = max;
    }

    pub fn push(&mut self, mut bytes: &[u8], out: &mut Vec<Line>) {
        while !bytes.is_empty() {
            let end = bytes.iter().position(|byte| *byte == b'\n');
            let part = &bytes[..end.unwrap_or(bytes.len())];
            bytes = &bytes[end.map_or(bytes.len(), |end| end + 1)..];
            if self.discarding {
                if end.is_some() {
                    self.discarding = false;
                }
                continue;
            }
            self.buffer.extend_from_slice(part);
            if self.content_len() > self.max {
                self.buffer = Vec::new();
                out.push(Line::Oversized);
                self.discarding = end.is_none();
                continue;
            }
            if end.is_some() {
                self.finish_line(out);
            }
        }
    }

    /// The unterminated tail at EOF is a frame, not data to lose.
    pub fn finish(&mut self, out: &mut Vec<Line>) {
        if !self.discarding {
            self.finish_line(out);
        }
        self.discarding = false;
    }

    fn content_len(&self) -> usize {
        self.buffer.len() - usize::from(self.buffer.last() == Some(&b'\r'))
    }

    fn finish_line(&mut self, out: &mut Vec<Line>) {
        let mut line = std::mem::take(&mut self.buffer);
        if line.last() == Some(&b'\r') {
            line.pop();
        }
        if !line.iter().all(u8::is_ascii_whitespace) {
            out.push(Line::Frame(line));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(max: usize, pieces: &[&[u8]]) -> Vec<Line> {
        let mut splitter = LineSplitter::new(max);
        let mut out = Vec::new();
        for piece in pieces {
            splitter.push(piece, &mut out);
        }
        splitter.finish(&mut out);
        out
    }

    fn frames(lines: Vec<Line>) -> Vec<Vec<u8>> {
        lines
            .into_iter()
            .map(|line| match line {
                Line::Frame(frame) => frame,
                Line::Oversized => panic!("unexpected oversize"),
            })
            .collect()
    }

    #[test]
    fn byte_by_byte_equals_whole_input() {
        let input = b"{\"a\":1}\n{\"b\":\"two\"}\r\n\n  \n{\"c\":3}\n";
        let whole = frames(run(1024, &[input]));
        assert_eq!(whole.len(), 3);
        let singles: Vec<&[u8]> = input.chunks(1).collect();
        assert_eq!(frames(run(1024, &singles)), whole);
        for size in 2..9 {
            let pieces: Vec<&[u8]> = input.chunks(size).collect();
            assert_eq!(frames(run(1024, &pieces)), whole, "chunk size {size}");
        }
    }

    #[test]
    fn crlf_is_stripped_and_blank_lines_are_skipped() {
        assert_eq!(
            frames(run(64, &[b"a\r\n\r\n\n \t\nb\n"])),
            vec![b"a".to_vec(), b"b".to_vec()]
        );
    }

    #[test]
    fn carriage_return_split_from_its_newline_still_strips() {
        assert_eq!(frames(run(64, &[b"abc\r", b"\ndef\n"])), vec![b"abc".to_vec(), b"def".to_vec()]);
    }

    #[test]
    fn a_split_utf8_sequence_reassembles() {
        let text = "{\"t\":\"é🦀\"}\n".as_bytes();
        for cut in 1..text.len() {
            let got = frames(run(64, &[&text[..cut], &text[cut..]]));
            assert_eq!(got, vec![text[..text.len() - 1].to_vec()], "cut {cut}");
        }
    }

    #[test]
    fn the_limit_is_exact() {
        let max = 16;
        let fits = vec![b'x'; max];
        let over = vec![b'x'; max + 1];
        let mut input = fits.clone();
        input.push(b'\n');
        assert_eq!(frames(run(max, &[&input])), vec![fits.clone()]);
        let mut input = over.clone();
        input.push(b'\n');
        assert_eq!(run(max, &[&input]), vec![Line::Oversized]);
        let mut crlf = fits.clone();
        crlf.extend_from_slice(b"\r\n");
        assert_eq!(frames(run(max, &[&crlf])), vec![fits]);
    }

    #[test]
    fn an_oversized_line_is_reported_once_and_the_stream_recovers() {
        let max = 8;
        let mut input = vec![b'z'; 100];
        input.extend_from_slice(b"\nok\n");
        let pieces: Vec<&[u8]> = input.chunks(3).collect();
        assert_eq!(
            run(max, &pieces),
            vec![Line::Oversized, Line::Frame(b"ok".to_vec())]
        );
        assert_eq!(
            run(max, &[&input]),
            vec![Line::Oversized, Line::Frame(b"ok".to_vec())]
        );
    }

    #[test]
    fn an_oversized_line_is_reported_before_its_newline_arrives() {
        let mut splitter = LineSplitter::new(4);
        let mut out = Vec::new();
        splitter.push(b"123456", &mut out);
        assert_eq!(out, vec![Line::Oversized]);
        splitter.push(b"789", &mut out);
        assert_eq!(out.len(), 1);
        splitter.push(b"\nok\n", &mut out);
        assert_eq!(out, vec![Line::Oversized, Line::Frame(b"ok".to_vec())]);
    }

    #[test]
    fn memory_stays_bounded_while_discarding() {
        let mut splitter = LineSplitter::new(1024);
        let mut out = Vec::new();
        let block = vec![b'q'; 1 << 16];
        for _ in 0..256 {
            splitter.push(&block, &mut out);
            assert!(splitter.buffer.capacity() <= (1 << 17));
        }
        assert_eq!(out, vec![Line::Oversized]);
    }

    #[test]
    fn an_unterminated_tail_at_eof_is_a_frame() {
        assert_eq!(frames(run(64, &[b"a\nlast"])), vec![b"a".to_vec(), b"last".to_vec()]);
        assert_eq!(run(4, &[b"toolong"]), vec![Line::Oversized]);
        assert!(run(64, &[b"a\n   "]).len() == 1);
    }

    #[test]
    fn multi_megabyte_frame_under_a_large_limit_arrives_whole() {
        let big = vec![b'a'; 3 << 20];
        let mut input = big.clone();
        input.push(b'\n');
        let pieces: Vec<&[u8]> = input.chunks(65_536).collect();
        assert_eq!(frames(run(16 << 20, &pieces)), vec![big]);
    }
}
