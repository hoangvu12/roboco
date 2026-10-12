#!/usr/bin/env bash
# Compare allocation churn using identical real captured markdown.
# First build roboco-ui to populate deps; no extra crates are needed.
# Usage: profile-markdown.sh TARGET/debug/deps TEXT_FILE [GIT_REF]
# Omit GIT_REF for working-tree code. Output is JSON; bytes are allocator
# requests/live heap, not RSS. The parser is compiled optimized in both runs.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
deps=$(realpath "$1")
source_text=$(realpath "$2")
bench_dir=$(mktemp -d)
trap 'rm -rf "$bench_dir"' EXIT
# Paths embedded in the generated main.rs must resolve for a native rustc:
# MSYS /tmp and /c/... spellings do not. cygpath -m yields the C:/... mixed
# form rustc accepts; elsewhere (macOS/Linux) paths embed as-is.
if command -v cygpath >/dev/null 2>&1; then
  rust_path() { cygpath -m "$1"; }
else
  rust_path() { realpath "$1"; }
fi
# The parser and mend modules live in the roboco-markdown crate since the
# extraction (upstream fe46a971); GIT_REF may still name a pre-move commit,
# where the same files sat under crates/ui/src/markdown/.
if [[ -n "${3:-}" ]] && ! git -C "$root" cat-file -e "$3:crates/markdown/src/parser.rs" 2>/dev/null; then
  module_dir=crates/ui/src/markdown
else
  module_dir=crates/markdown/src
fi
for module in parser mend; do
  if [[ -n "${3:-}" ]]; then
    git -C "$root" show "$3:$module_dir/$module.rs" > "$bench_dir/$module.rs"
  else
    cp "$root/$module_dir/$module.rs" "$bench_dir/$module.rs"
  fi
done
bench_dir_rust=$(rust_path "$bench_dir")
root_rust=$(rust_path "$root")
cat > "$bench_dir/main.rs" <<EOF
#[path = "$bench_dir_rust/mend.rs"] pub mod mend;
#[path = "$bench_dir_rust/parser.rs"] pub mod parser;
mod markdown {
    pub use super::{mend, parser};
}
include!("$root_rust/scripts/markdown-profile.rs");
EOF
cmark=$(ls -t "$deps"/libpulldown_cmark-*.rlib | head -1)
rustc --edition=2024 -O -Awarnings -L "dependency=$deps" \
  --extern "pulldown_cmark=$cmark" "$bench_dir/main.rs" -o "$bench_dir/profile"
"$bench_dir/profile" "$source_text"
