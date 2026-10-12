//! `<out>/sizes.json` — the measured Android-core sizes the ticket records
//! in the PR and CI uploads as an artifact.
//!
//! The JSON is emitted by hand rather than through serde: the xtask stays
//! std-only plus `anyhow`, and every string here comes from a fixed
//! alphabet (profile, language set, ABI names), so the emitter only has to
//! stay honest, which the tests pin down.

use crate::elf::ElfInfo;

/// One ABI's measured library.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AbiSizes {
    pub abi: String,
    pub file_size: u64,
    pub text: u64,
    pub data: u64,
    pub bss: u64,
    /// The smallest `PT_LOAD` alignment (bytes).
    pub min_load_alignment: u64,
    /// The `loro` crate's symbol bytes (android-app#10 §5: only refactor the
    /// `roboco-doc` features if this exceeds 1 MB). `None` when the symbol
    /// table was stripped.
    pub loro_symbol_bytes: Option<u64>,
}

impl AbiSizes {
    pub fn from_file(abi: &str, file_size: u64, info: &ElfInfo) -> Self {
        Self {
            abi: abi.to_owned(),
            file_size,
            text: info.text,
            data: info.data,
            bss: info.bss,
            min_load_alignment: info.load_alignments.iter().copied().min().unwrap_or(0),
            loro_symbol_bytes: info.loro_symbol_bytes,
        }
    }
}

/// The whole run's report.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SizesReport {
    pub profile: String,
    pub languages: String,
    pub abis: Vec<AbiSizes>,
    /// The arm64-v8a `loro` total — the §5 measurement target; `None` when
    /// no arm64 library was measured or its symbol table was stripped.
    pub loro_symbol_bytes: Option<u64>,
}

impl SizesReport {
    pub fn new(profile: &str, languages: &str, mut abis: Vec<AbiSizes>) -> Self {
        let loro_symbol_bytes = abis
            .iter()
            .find(|abi| abi.abi == "arm64-v8a")
            .and_then(|abi| abi.loro_symbol_bytes);
        abis.sort_by(|left, right| left.abi.cmp(&right.abi));
        Self {
            profile: profile.to_owned(),
            languages: languages.to_owned(),
            abis,
            loro_symbol_bytes,
        }
    }

    /// Stable JSON with no timestamps or absolute paths, so two runs of the
    /// same inputs produce identical files.
    pub fn to_json(&self) -> String {
        let mut json = String::from("{\n");
        json.push_str(&format!("  \"profile\": {},\n", json_string(&self.profile)));
        json.push_str(&format!(
            "  \"languages\": {},\n",
            json_string(&self.languages)
        ));
        json.push_str(&format!(
            "  \"loro_symbol_bytes\": {},\n",
            json_number(self.loro_symbol_bytes)
        ));
        json.push_str("  \"abis\": [\n");
        for (index, abi) in self.abis.iter().enumerate() {
            json.push_str("    {\n");
            json.push_str(&format!("      \"abi\": {},\n", json_string(&abi.abi)));
            json.push_str(&format!("      \"file_size\": {},\n", abi.file_size));
            json.push_str(&format!(
                "      \"text\": {}, \"data\": {}, \"bss\": {},\n",
                abi.text, abi.data, abi.bss
            ));
            json.push_str(&format!(
                "      \"min_load_alignment\": {},\n",
                abi.min_load_alignment
            ));
            json.push_str(&format!(
                "      \"loro_symbol_bytes\": {}\n",
                json_number(abi.loro_symbol_bytes)
            ));
            json.push_str(if index + 1 == self.abis.len() {
                "    }\n"
            } else {
                "    },\n"
            });
        }
        json.push_str("  ]\n}\n");
        json
    }

    /// The one-line-per-ABI summary printed to stdout.
    pub fn summary(&self) -> String {
        let mut table = format!(
            "android-core sizes: profile={} languages={}\n\
             {:<12} {:>10} {:>10} {:>10} {:>10} {:>8} {:>12}\n",
            self.profile,
            self.languages,
            "abi",
            "file (MB)",
            "text (MB)",
            "data (MB)",
            "bss (MB)",
            "align",
            "loro (KB)"
        );
        for abi in &self.abis {
            table.push_str(&format!(
                "{:<12} {:>10.1} {:>10.1} {:>10.1} {:>10.1} {:>8} {:>12}\n",
                abi.abi,
                abi.file_size as f64 / 1e6,
                abi.text as f64 / 1e6,
                abi.data as f64 / 1e6,
                abi.bss as f64 / 1e6,
                format!("{:#x}", abi.min_load_alignment),
                match abi.loro_symbol_bytes {
                    Some(bytes) => format!("{:.0}", bytes as f64 / 1e3),
                    None => "-".to_owned(),
                },
            ));
        }
        table
    }
}

fn json_string(value: &str) -> String {
    let mut quoted = String::with_capacity(value.len() + 2);
    quoted.push('"');
    for character in value.chars() {
        match character {
            '"' => quoted.push_str("\\\""),
            '\\' => quoted.push_str("\\\\"),
            control if control.is_control() => {
                quoted.push_str(&format!("\\u{:04x}", control as u32))
            }
            other => quoted.push(other),
        }
    }
    quoted.push('"');
    quoted
}

fn json_number(value: Option<u64>) -> String {
    value
        .map(|value| value.to_string())
        .unwrap_or_else(|| "null".to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::elf::ElfInfo;

    fn sample() -> SizesReport {
        let info = ElfInfo {
            load_alignments: vec![0x4000, 0x4000],
            text: 15_000_000,
            data: 3_000_000,
            bss: 500_000,
            loro_symbol_bytes: Some(900_000),
        };
        SizesReport::new(
            "mobile-dist",
            "mobile",
            vec![
                AbiSizes::from_file(
                    "x86_64",
                    20_000_000,
                    &ElfInfo {
                        loro_symbol_bytes: None,
                        ..info.clone()
                    },
                ),
                AbiSizes::from_file("arm64-v8a", 21_000_000, &info),
            ],
        )
    }

    #[test]
    fn abis_are_sorted_and_the_arm64_loro_total_is_promoted() {
        let report = sample();
        assert_eq!(report.abis[0].abi, "arm64-v8a");
        assert_eq!(report.abis[1].abi, "x86_64");
        assert_eq!(report.loro_symbol_bytes, Some(900_000));
    }

    #[test]
    fn json_is_deterministic_and_parses_as_expected_fields() {
        let first = sample().to_json();
        let second = sample().to_json();
        assert_eq!(first, second);
        assert!(first.contains("\"profile\": \"mobile-dist\""));
        assert!(first.contains("\"languages\": \"mobile\""));
        assert!(first.contains("\"loro_symbol_bytes\": 900000"));
        assert!(first.contains("\"abi\": \"arm64-v8a\""));
        assert!(first.contains("\"min_load_alignment\": 16384"));
        assert!(first.contains("\"loro_symbol_bytes\": null"));
        assert!(!first.contains("target"), "no absolute paths in sizes.json");
    }

    #[test]
    fn the_summary_table_lists_every_abi() {
        let summary = sample().summary();
        assert!(summary.contains("arm64-v8a"));
        assert!(summary.contains("x86_64"));
        assert!(summary.contains("profile=mobile-dist"));
        assert!(summary.contains("0x4000"));
    }

    #[test]
    fn strings_are_escaped_not_trusted() {
        let report = SizesReport::new("mo\"bile\\", "all", Vec::new());
        let json = report.to_json();
        assert!(json.contains("\"profile\": \"mo\\\"bile\\\\\""));
    }
}
