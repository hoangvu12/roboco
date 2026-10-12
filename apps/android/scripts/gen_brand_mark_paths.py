import pathlib, re, sys
root = pathlib.Path(sys.argv[1])
out = pathlib.Path(sys.argv[2])
icons = root / "crates/ui/assets/icons"
def ds(name):
    return re.findall(r'\sd="([^"]*)"', (icons / f"{name}-mark.svg").read_text(encoding="utf-8"))
head = ("// GENERATED from crates/ui/assets/icons/<name>-mark.svg by\n"
        "// apps/android/scripts/gen_brand_mark_paths.py. Do not hand-edit:\n"
        "// BrandMarkSvgParityTest fails if this drifts from the desktop assets.\n"
        "package sh.roboco.android.design\n\n")
body = head
for name in ["claude", "openai", "cursor", "devin", "grok", "pi", "opencode", "antigravity"]:
    d = ds(name)[0]  # opencode: frame path only (first <path>)
    body += f'internal const val {name.upper()}_PATH_DATA = "{d}"\n\n'
(out / "BrandMarkPaths.kt").write_text(body.rstrip() + "\n", encoding="utf-8", newline="\n")
hermes = " ".join(ds("hermes"))  # three <path> d's, one space between, as iOS HermesMark.swift
(out / "HermesMarkData.kt").write_text(head + f'internal const val HERMES_PATH_DATA = "{hermes}"\n', encoding="utf-8", newline="\n")
