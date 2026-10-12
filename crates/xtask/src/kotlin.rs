//! Checks on the UniFFI-generated Kotlin bindings.
//!
//! `cargo xtask android-core --check` runs these over
//! `<out>/kotlin/uniffi/roboco_core/roboco_core.kt`. They pin the contract
//! ticket 13 consumes (package, the `TranscriptView.close → shutdown` rename,
//! the version handshake) and enforce the rename rule from
//! `crates/mobile/uniffi.toml`: Kotlin exceptions already own `message`, so
//! an error variant field left as `message` would emit a `val message`
//! property that cannot compile.

/// The package UniFFI's default (no `package_name` override) assigns to the
/// `roboco_core` namespace.
pub const EXPECTED_PACKAGE: &str = "uniffi.roboco_core";

/// Returns every failure found in `source`; empty means the bindings are
/// good. Failures are already phrased for humans.
pub fn check_generated(source: &str) -> Vec<String> {
    let mut failures = Vec::new();

    let package_ok = source
        .lines()
        .any(|line| line.trim() == format!("package {EXPECTED_PACKAGE}"));
    if !package_ok {
        failures.push(format!("no `package {EXPECTED_PACKAGE}` declaration"));
    }
    for function in ["shutdown", "coreVersion"] {
        if !has_function(source, function) {
            failures.push(format!("generated Kotlin is missing `fun {function}(...)`"));
        }
    }
    failures.extend(message_clashes(source));
    failures
}

/// `--no-format` generation emits backticked identifiers for renamed and
/// camel-cased names (``fun `coreVersion`()``), so both spellings count.
fn has_function(source: &str, name: &str) -> bool {
    source.contains(&format!("fun {name}(")) || source.contains(&format!("fun `{name}`("))
}

/// `val message` properties declared inside classes of the exception
/// hierarchy (the error enums UniFFI turns into `sealed class … :
/// kotlin.Exception`). Renamed fields (`val reason`) and the generated
/// `override val message` getters are fine; record data classes are not
/// exceptions and are left alone.
fn message_clashes(source: &str) -> Vec<String> {
    // Pass 1: the sealed error bases (`sealed class PairError:
    // kotlin.Exception()` in UniFFI's template).
    let mut error_bases: Vec<String> = Vec::new();
    for line in source.lines() {
        let trimmed = line.trim();
        let Some(rest) = trimmed.strip_prefix("sealed class ") else {
            continue;
        };
        if !rest.contains("Exception") {
            continue;
        }
        let name = class_name(rest);
        if !name.is_empty() {
            error_bases.push(name.to_owned());
        }
    }

    // Pass 2: every class whose supertype is an error base (or `Exception`
    // directly) must not declare a `val message` constructor property.
    let mut failures = Vec::new();
    let lines: Vec<&str> = source.lines().collect();
    let mut index = 0;
    while let Some(&line) = lines.get(index) {
        let trimmed = line.trim();
        // `data class` (records) and plain declarations are skipped by the
        // `class ` prefix; nested variant classes match it.
        let Some(rest) = trimmed.strip_prefix("class ") else {
            index += 1;
            continue;
        };
        let name = class_name(rest).to_owned();
        let (extends, params_end) = match class_signature(rest, &lines[index..]) {
            Some(signature) => signature,
            None => {
                index += 1;
                continue;
            }
        };
        let is_exception =
            extends.contains("Exception") || error_bases.iter().any(|base| extends.trim() == *base);
        if is_exception {
            // Constructor params: on the declaration line (flat variants,
            // never `val`) or on the lines up to the `) : supertype` closer.
            for param_line in class_param_lines(rest, &lines[index..index + params_end]) {
                if param_line.trim_start().starts_with("val message") {
                    failures.push(format!(
                        "exception class {name} declares `val message` — add a \
                         rename line to crates/mobile/uniffi.toml \
                         (\"<Error>.{name}.message\" = \"reason\")"
                    ));
                }
            }
        }
        index += params_end;
    }
    failures
}

/// The identifier before the first `(`, `:` or whitespace.
fn class_name(rest: &str) -> &str {
    rest.find(['(', ':', ' '])
        .map(|end| &rest[..end])
        .unwrap_or(rest)
}

/// `(supertype expression, index just past the class signature)`. The
/// generated signatures come in exactly two shapes: everything on one line
/// (`class Variant(field: T) : Base(field)`), or a parenthesized parameter
/// list closed by a line starting with `) : Base…`.
fn class_signature(rest: &str, lines: &[&str]) -> Option<(String, usize)> {
    let close_on_declaration_line = match rest.find(')') {
        Some(close) => rest[close..].contains(':'),
        None => false,
    };
    if close_on_declaration_line {
        let close = rest.find(')').expect("checked above");
        let supertype = supertype_name(&rest[close + 1..]);
        return Some((supertype, 1));
    }
    // Multi-line: find the line that closes the parameter list.
    for (offset, line) in lines.iter().enumerate().skip(1) {
        if line.trim_start().starts_with(") :") || line.trim() == ")" {
            let supertype = supertype_name(line.trim_start());
            return Some((supertype, offset + 1));
        }
    }
    None
}

/// The supertype expression: the text after the parameter-list closer
/// (`) :` — separators are single spaces in the generated code, but strip
/// any run of them, plus the colon) up to `(`, `{` or end of line.
fn supertype_name(closer: &str) -> String {
    let after = closer
        .trim_start_matches(|c: char| c == ')' || c == ':' || c.is_whitespace())
        .trim();
    after
        .find(['(', '{'])
        .map(|end| after[..end].trim())
        .unwrap_or(after)
        .to_owned()
}

/// Constructor parameter text: the inside of the declaration-line parens,
/// plus every parameter line of the multi-line form.
fn class_param_lines<'a>(rest: &'a str, lines: &[&'a str]) -> Vec<&'a str> {
    let mut params = Vec::new();
    if let Some(open) = rest.find('(') {
        if let Some(close) = rest[open..].find(')') {
            params.push(&rest[open + 1..open + close]);
            return params;
        }
    }
    for line in lines.iter().skip(1) {
        if line.trim_start().starts_with(") :") || line.trim() == ")" {
            break;
        }
        params.push(line);
    }
    params
}

#[cfg(test)]
mod tests {
    use super::*;

    const GOOD: &str = r#"
package uniffi.roboco_core

fun coreVersion(): String = "0.9.2"

public interface TranscriptView {
    fun shutdown()
    fun close()
}
"#;

    #[test]
    fn a_well_formed_file_passes() {
        assert!(check_generated(GOOD).is_empty());
    }

    #[test]
    fn backticked_identifiers_satisfy_the_function_checks() {
        // What `--no-format` actually emits for camel-cased/renamed names.
        let backticked = r#"
package uniffi.roboco_core

public interface TranscriptView {
    fun `shutdown`()
}
 */ fun `coreVersion`(): kotlin.String {
"#;
        assert!(check_generated(backticked).is_empty());
    }

    #[test]
    fn missing_package_shutdown_or_version_are_reported() {
        assert!(check_generated(GOOD).is_empty());
        let failures = check_generated("fun shutdown() {}\nfun coreVersion(): String\n");
        assert_eq!(
            failures,
            vec!["no `package uniffi.roboco_core` declaration"]
        );
        assert!(
            check_generated(&GOOD.replacen("fun shutdown()", "fun halt()", 1))
                .iter()
                .any(|failure| failure.contains("fun shutdown("))
        );
        assert!(
            check_generated(&GOOD.replacen(
                "fun coreVersion(): String",
                "fun version(): String",
                1
            ))
            .iter()
            .any(|failure| failure.contains("fun coreVersion("))
        );
    }

    /// The exact shape UniFFI 0.32 emits for an error variant whose field was
    /// NOT renamed — this is what the check exists to catch.
    const UNRENAMED_ERROR: &str = r#"
sealed class PairError: kotlin.Exception() {
    class Storage(
        val message: String,
    ) : PairError() {
        override val message
            get() = "message=${message}"
    }
}
"#;

    #[test]
    fn an_unrenamed_error_message_field_is_a_failure() {
        let failures = check_generated(&format!("{GOOD}\n{UNRENAMED_ERROR}"));
        assert_eq!(failures.len(), 1);
        assert!(failures[0].contains("Storage"), "{failures:?}");
        assert!(failures[0].contains("val message"));
    }

    #[test]
    fn renamed_fields_and_override_getters_are_fine() {
        let renamed = UNRENAMED_ERROR.replace("val message: String,", "val reason: String,");
        assert!(check_generated(&format!("{GOOD}\n{renamed}")).is_empty());
    }

    #[test]
    fn flat_errors_and_record_message_fields_do_not_clash() {
        // Flat error: a plain `message: String` parameter (no `val`) — the
        // generated template for variants without fields.
        let flat = "sealed class CoreError(message: String): kotlin.Exception(message) {\n    class Closed : CoreError(\"closed\")\n}";
        // Records are data classes, not exceptions: a `message` field there
        // compiles fine and is none of this check's business.
        let record = "data class ErrorChip (\n    val message: String,\n)";
        assert!(check_generated(&format!("{GOOD}\n{flat}\n{record}")).is_empty());
    }

    #[test]
    fn nested_variant_of_a_variantless_base_is_scanned() {
        let multiple = r#"
sealed class ClientError: kotlin.Exception() {
    class InvalidUrl(
        val message: String,
    ) : ClientError() {
        override val message
            get() = "message=${message}"
    }
    class Storage(
        val reason: String,
    ) : ClientError()
}
"#;
        let failures = check_generated(&format!("{GOOD}\n{multiple}"));
        assert_eq!(failures.len(), 1, "{failures:?}");
        assert!(failures[0].contains("InvalidUrl"));
    }
}
