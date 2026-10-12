//! Minimal ELF64 little-endian reader for the Android core checks.
//!
//! Only what `cargo xtask android-core --check` needs: `PT_LOAD` alignment
//! (the Play 16 KB requirement), Berkeley-style text/data/bss section sizes
//! (what `llvm-size` prints, and what the grammar-size measurements in
//! ticket android-app#10 were taken with), and the byte total of `loro`
//! symbols — the "measure before refactoring" number for §5 of that ticket.
//!
//! Android ABIs (`aarch64-linux-android`, `x86_64-linux-android`) are always
//! ELF64 little-endian; anything else is rejected.

/// Section header flags: `SHF_WRITE`, `SHF_ALLOC`. (Exec-vs-readonly does
/// not change the text bucket — see `parse`.)
const SHF_WRITE: u64 = 0x1;
const SHF_ALLOC: u64 = 0x2;
/// Section types: `SHT_SYMTAB`, `SHT_NOBITS`. (`SHT_STRTAB` is used by the
/// test ELF builder only.)
const SHT_SYMTAB: u32 = 2;
const SHT_NOBITS: u32 = 8;
/// Program header type `PT_LOAD`.
const PT_LOAD: u32 = 1;

const ELFCLASS64: u8 = 2;
const ELFDATA2LSB: u8 = 1;

/// Play requirement for targetSdk 35+: every `PT_LOAD` segment must be
/// aligned to at least a 16 KB page.
pub const MIN_LOAD_ALIGNMENT: u64 = 0x4000;

/// What the checks read out of one `.so`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ElfInfo {
    /// `p_align` of every `PT_LOAD` program header, in file order.
    pub load_alignments: Vec<u64>,
    /// Sum of ALLOC non-NOBITS non-WRITE sections (code and read-only data,
    /// as reported by `size`/`llvm-size` in Berkeley mode).
    pub text: u64,
    /// Sum of ALLOC non-NOBITS WRITE sections.
    pub data: u64,
    /// Sum of ALLOC NOBITS sections.
    pub bss: u64,
    /// Sum of `st_size` over symbols whose name mentions `loro` — the CRDT
    /// crate's footprint in the final library. `None` when the symbol table
    /// was stripped (`--profile mobile-dist` keeps it: `strip = "debuginfo"`).
    pub loro_symbol_bytes: Option<u64>,
}

impl ElfInfo {
    /// The Play 16 KB check: every `PT_LOAD` is at least 16 KB aligned.
    pub fn load_alignments_ok(&self) -> bool {
        self.load_alignments
            .iter()
            .all(|&align| align >= MIN_LOAD_ALIGNMENT)
    }
}

/// Parse an ELF64 little-endian image.
pub fn parse(bytes: &[u8]) -> anyhow::Result<ElfInfo> {
    if bytes.len() < 64 {
        anyhow::bail!("not an ELF file: shorter than the ELF64 header");
    }
    if &bytes[0..4] != b"\x7fELF" {
        anyhow::bail!("not an ELF file: bad magic");
    }
    if bytes[4] != ELFCLASS64 {
        anyhow::bail!("only ELF64 is supported (class {})", bytes[4]);
    }
    if bytes[5] != ELFDATA2LSB {
        anyhow::bail!("only little-endian ELF is supported");
    }

    let phoff = u64_at(bytes, 0x20);
    let shoff = u64_at(bytes, 0x28);
    let phentsize = u16_at(bytes, 0x36) as usize;
    let phnum = u16_at(bytes, 0x38) as usize;
    let shentsize = u16_at(bytes, 0x3a) as usize;
    let mut shnum = u16_at(bytes, 0x3c) as usize;
    // e_shstrndx is deliberately unused: sections are classified by type
    // and flags, never by name, so no section-name string table is read.

    let sections = SectionTable::parse(bytes, shoff, shentsize, &mut shnum)?;

    let mut load_alignments = Vec::new();
    for index in 0..phnum {
        let offset = phoff as usize + index * phentsize;
        let entry = bytes
            .get(offset..offset + 56)
            .ok_or_else(|| anyhow::anyhow!("program header {index} is out of bounds"))?;
        if u32_at(entry, 0) == PT_LOAD {
            load_alignments.push(u64_at(entry, 0x30));
        }
    }

    let mut text = 0;
    let mut data = 0;
    let mut bss = 0;
    for section in sections.iter() {
        if section.flags & SHF_ALLOC == 0 {
            continue;
        }
        if section.kind == SHT_NOBITS {
            bss += section.size;
        } else if section.flags & SHF_WRITE != 0 {
            data += section.size;
        } else {
            // Code and read-only data, matching `size`'s text bucket.
            text += section.size;
        }
    }

    let loro_symbol_bytes = loro_total(bytes, &sections)?;
    Ok(ElfInfo {
        load_alignments,
        text,
        data,
        bss,
        loro_symbol_bytes,
    })
}

fn loro_total(bytes: &[u8], sections: &SectionTable<'_>) -> anyhow::Result<Option<u64>> {
    let Some(symtab) = sections.find(SHT_SYMTAB) else {
        return Ok(None);
    };
    let strtab_index = symtab
        .link
        .ok_or_else(|| anyhow::anyhow!(".symtab has no linked string table (sh_link is null)"))?
        as usize;
    let strtab = sections
        .get(strtab_index)
        .ok_or_else(|| anyhow::anyhow!("string table index {strtab_index} is out of bounds"))?;
    let strings = slice_at(bytes, strtab.offset, strtab.size)?;
    let symbols = slice_at(bytes, symtab.offset, symtab.size)?;

    let mut total = 0;
    // Sym64 is 24 bytes; entry zero is the null symbol and carries no name.
    for entry in symbols.chunks_exact(24).skip(1) {
        let name_offset = u32_at(entry, 0) as usize;
        let size = u64_at(entry, 0x10);
        if size == 0 {
            continue;
        }
        let Some(name) = cstr_at(strings, name_offset) else {
            continue;
        };
        // Mangled or not, Rust symbol names contain the crate path
        // (`loro::…` / `_ZN4loro…`), so a substring match mirrors the
        // `llvm-nm -C | grep -i loro` measurement from the ticket.
        if name.to_ascii_lowercase().contains("loro") {
            total += size;
        }
    }
    Ok(Some(total))
}

struct SectionTable<'a> {
    bytes: &'a [u8],
    offset: usize,
    entry_size: usize,
    count: usize,
}

struct SectionEntry {
    kind: u32,
    flags: u64,
    offset: u64,
    size: u64,
    link: Option<u32>,
}

impl<'a> SectionTable<'a> {
    fn parse(
        bytes: &'a [u8],
        offset: u64,
        entry_size: usize,
        count: &mut usize,
    ) -> anyhow::Result<Self> {
        if offset == 0 && *count == 0 {
            return Ok(Self {
                bytes,
                offset: 0,
                entry_size: 0,
                count: 0,
            });
        }
        if entry_size < 64 {
            anyhow::bail!("section header entry size {entry_size} is smaller than Elf64_Shdr");
        }
        // Extended section numbering: the real count lives in section zero's
        // size field. Section zero is always SHT_NULL with otherwise-zero
        // fields, so reading it is safe before the bounds check below.
        if *count == 0 {
            let first = bytes
                .get(offset as usize..offset as usize + entry_size)
                .ok_or_else(|| anyhow::anyhow!("section table is out of bounds"))?;
            *count = u64_at(first, 0x20) as usize;
        }
        let table = Self {
            bytes,
            offset: offset as usize,
            entry_size,
            count: *count,
        };
        // One eager bounds check instead of one per section.
        table.get(table.count.wrapping_sub(1)).ok_or_else(|| {
            anyhow::anyhow!("section table ({} entries) is out of bounds", table.count)
        })?;
        Ok(table)
    }

    fn get(&self, index: usize) -> Option<SectionEntry> {
        if index >= self.count {
            return None;
        }
        let start = self.offset + index * self.entry_size;
        let entry = self.bytes.get(start..start + 64)?;
        Some(SectionEntry {
            kind: u32_at(entry, 4),
            flags: u64_at(entry, 8),
            offset: u64_at(entry, 0x18),
            size: u64_at(entry, 0x20),
            link: {
                let raw = u32_at(entry, 0x28);
                (raw != 0).then_some(raw)
            },
        })
    }

    fn iter(&self) -> impl Iterator<Item = SectionEntry> + '_ {
        (0..self.count).map(|index| self.get(index).expect("bounds checked in parse"))
    }

    fn find(&self, kind: u32) -> Option<SectionEntry> {
        self.iter().find(|section| section.kind == kind)
    }
}

fn u16_at(bytes: &[u8], offset: usize) -> u16 {
    u16::from_le_bytes([bytes[offset], bytes[offset + 1]])
}

fn u32_at(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes([
        bytes[offset],
        bytes[offset + 1],
        bytes[offset + 2],
        bytes[offset + 3],
    ])
}

fn u64_at(bytes: &[u8], offset: usize) -> u64 {
    let mut raw = [0u8; 8];
    raw.copy_from_slice(&bytes[offset..offset + 8]);
    u64::from_le_bytes(raw)
}

fn slice_at(bytes: &[u8], offset: u64, size: u64) -> anyhow::Result<&[u8]> {
    let start = offset as usize;
    let end = start
        .checked_add(size as usize)
        .ok_or_else(|| anyhow::anyhow!("section at {start}+{size} overflows the file"))?;
    bytes
        .get(start..end)
        .ok_or_else(|| anyhow::anyhow!("section at {start}+{size} is out of bounds"))
}

fn cstr_at(strings: &[u8], offset: usize) -> Option<&str> {
    let bytes = strings.get(offset..)?;
    let end = bytes
        .iter()
        .position(|&byte| byte == 0)
        .unwrap_or(bytes.len());
    std::str::from_utf8(&bytes[..end]).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `SHT_STRTAB`, for the synthetic section tables below.
    const SHT_STRTAB: u32 = 3;

    /// `SHF_EXECINSTR`, for the text-bucket section below.
    const SHF_EXECINSTR: u64 = 0x4;

    /// Builds a synthetic ELF64 little-endian image the way the NDK links
    /// real ones: header, program headers, sections with a section-name
    /// string table, and an optional symbol table with its string table.
    struct ElfBuilder {
        load_segments: Vec<(u32, u64)>,
        sections: Vec<SectionSpec>,
        symbols: Vec<(&'static str, u64)>,
    }

    struct SectionSpec {
        kind: u32,
        flags: u64,
        size: u64,
    }

    impl ElfBuilder {
        fn new() -> Self {
            Self {
                load_segments: Vec::new(),
                sections: Vec::new(),
                symbols: Vec::new(),
            }
        }

        fn load(mut self, p_align: u64) -> Self {
            self.load_segments.push((PT_LOAD, p_align));
            self
        }

        fn section(mut self, kind: u32, flags: u64, size: u64) -> Self {
            self.sections.push(SectionSpec { kind, flags, size });
            self
        }

        fn symbol(mut self, name: &'static str, size: u64) -> Self {
            self.symbols.push((name, size));
            self
        }

        fn build(self) -> Vec<u8> {
            const HEADER: usize = 64;
            const PHDR: usize = 56;
            const SHDR: usize = 64;
            const SYM: usize = 24;

            // Layout: header, program headers, section headers, then the
            // section contents (shstrtab, symtab, strtab, padding bodies).
            let phnum = self.load_segments.len();
            // Layout: null, shstrtab, the real sections, then symtab + its
            // string table only when symbols exist (a stripped library has
            // no .symtab at all).
            let has_symbols = !self.symbols.is_empty();
            let section_count = 2 + self.sections.len() + usize::from(has_symbols) * 2;
            let shoff = (HEADER + phnum * PHDR) as u64;
            let content_base = shoff as usize + section_count * SHDR;

            // The section-name string table: SHT_NULL (""), the real
            // sections, then shstrtab/symtab/strtab themselves. Names are only
            // placeholders — the reader classifies by type and flags.
            let shstrtab: Vec<u8> = [
                "",
                ".text",
                ".data",
                ".bss",
                ".shstrtab",
                ".symtab",
                ".strtab",
            ]
            .iter()
            .flat_map(|name| name.bytes().chain([0]))
            .collect();

            // The symbol string table: null byte first, then each name.
            let mut strtab = vec![0u8];
            let mut name_offsets = Vec::new();
            for (name, _) in &self.symbols {
                name_offsets.push(strtab.len() as u32);
                strtab.extend(name.bytes());
                strtab.push(0);
            }
            let mut symbols: Vec<u8> = vec![0; SYM]; // null symbol
            for ((_, size), name_offset) in self.symbols.iter().zip(&name_offsets) {
                let mut entry = [0u8; SYM];
                entry[0..4].copy_from_slice(&name_offset.to_le_bytes());
                entry[0x10..0x18].copy_from_slice(&size.to_le_bytes());
                symbols.extend_from_slice(&entry);
            }

            let symtab_offset = (content_base + shstrtab.len()) as u64;
            let strtab_offset = symtab_offset + symbols.len() as u64;
            // Section contents other than the string/symbol tables are just
            // sized padding: sizes are the only thing the reader sums.
            let mut body_cursor = strtab_offset as usize + strtab.len();
            let mut offsets = Vec::new();
            for section in &self.sections {
                offsets.push(body_cursor as u64);
                body_cursor += section.size as usize;
            }

            let mut bytes = Vec::new();
            bytes.extend_from_slice(&[0x7f, b'E', b'L', b'F', ELFCLASS64, ELFDATA2LSB, 1, 0]);
            bytes.extend(std::iter::repeat(0).take(8)); // rest of e_ident
            // Macros rather than closures: several closures cannot each hold
            // `&mut bytes` at once.
            macro_rules! push {
                ($word:expr) => {
                    bytes.extend_from_slice(&$word.to_le_bytes())
                };
            }
            push!(3u16); // ET_DYN
            push!(0xb7u16); // EM_AARCH64
            push!(1u32); // e_version
            push!(0u64); // e_entry
            push!(HEADER as u64); // e_phoff
            push!(shoff); // e_shoff
            push!(0u32); // e_flags
            push!(HEADER as u16);
            push!(PHDR as u16);
            push!(phnum as u16);
            push!(SHDR as u16);
            push!(section_count as u16);
            push!(1u16); // e_shstrndx: the name table is section index 1

            for (kind, align) in &self.load_segments {
                let mut phdr = [0u8; PHDR];
                phdr[0..4].copy_from_slice(&kind.to_le_bytes());
                phdr[0x30..0x38].copy_from_slice(&align.to_le_bytes());
                bytes.extend_from_slice(&phdr);
            }

            let mut push_section = |kind: u32, flags: u64, offset: u64, size: u64, link: u32| {
                let mut shdr = [0u8; SHDR];
                shdr[4..8].copy_from_slice(&kind.to_le_bytes());
                shdr[8..0x10].copy_from_slice(&flags.to_le_bytes());
                shdr[0x18..0x20].copy_from_slice(&offset.to_le_bytes());
                shdr[0x20..0x28].copy_from_slice(&size.to_le_bytes());
                shdr[0x28..0x2c].copy_from_slice(&link.to_le_bytes());
                bytes.extend_from_slice(&shdr);
            };
            push_section(0, 0, 0, 0, 0); // SHT_NULL
            push_section(SHT_STRTAB, 0, content_base as u64, shstrtab.len() as u64, 0);
            for (index, section) in self.sections.iter().enumerate() {
                push_section(section.kind, section.flags, offsets[index], section.size, 0);
            }
            if has_symbols {
                // symtab sits at index 2 + sections.len(); its string table
                // (sh_link) is the section right after it.
                push_section(
                    SHT_SYMTAB,
                    0,
                    symtab_offset,
                    symbols.len() as u64,
                    (3 + self.sections.len()) as u32,
                );
                push_section(SHT_STRTAB, 0, strtab_offset, strtab.len() as u64, 0);
            }

            bytes.extend_from_slice(&shstrtab);
            if has_symbols {
                bytes.extend_from_slice(&symbols);
                bytes.extend_from_slice(&strtab);
            }
            bytes.resize(body_cursor, 0);
            bytes
        }
    }

    #[test]
    fn sixteen_kb_alignment_passes_and_four_kb_fails() {
        let ok = ElfBuilder::new().load(0x4000).build();
        assert!(parse(&ok).unwrap().load_alignments_ok());
        let bad = ElfBuilder::new().load(0x1000).build();
        let info = parse(&bad).unwrap();
        assert_eq!(info.load_alignments, vec![0x1000]);
        assert!(!info.load_alignments_ok());
    }

    #[test]
    fn section_sizes_follow_the_berkeley_buckets() {
        // text (ALLOC|EXEC), rodata (ALLOC), data (ALLOC|WRITE), bss
        // (ALLOC|WRITE|NOBITS), and one non-ALLOC section that counts for
        // nothing.
        let bytes = ElfBuilder::new()
            .section(1, SHF_ALLOC | SHF_EXECINSTR, 100)
            .section(1, SHF_ALLOC, 30)
            .section(1, SHF_ALLOC | SHF_WRITE, 20)
            .section(SHT_NOBITS, SHF_ALLOC | SHF_WRITE, 10)
            .section(1, 0, 999)
            .build();
        let info = parse(&bytes).unwrap();
        assert_eq!((info.text, info.data, info.bss), (130, 20, 10));
    }

    #[test]
    fn loro_symbols_sum_their_sizes_and_ignore_other_crates() {
        let bytes = ElfBuilder::new()
            .symbol("_ZN4loro7container3foo17h1E", 4_000)
            .symbol("loro::doc::Doc", 1_500)
            .symbol("roboco_mobile::layout", 9_000)
            .build();
        assert_eq!(parse(&bytes).unwrap().loro_symbol_bytes, Some(5_500));
    }

    #[test]
    fn stripped_libraries_report_no_symbol_total() {
        let bytes = ElfBuilder::new().build();
        assert_eq!(parse(&bytes).unwrap().loro_symbol_bytes, None);
    }

    #[test]
    fn rejects_non_elf_and_wrong_class() {
        assert!(parse(b"").is_err());
        assert!(parse(b"MZ\x00\x00not-an-elf-at-all-padding-padding-padding!").is_err());
        let mut elf_class_32 = ElfBuilder::new().build();
        elf_class_32[4] = 1; // ELFCLASS32
        assert!(parse(&elf_class_32).is_err());
    }
}
