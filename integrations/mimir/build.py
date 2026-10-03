#!/usr/bin/env python3
"""Builds the sh.roboco.bridge plugin package from a packaged SDK archive.

    build.py --sdk-archive mimir-plugin-sdk-0.13.0.crate [--out out] [--mimir PATH]

The SDK is extracted from the archive and wired in with a cargo patch flag, so
nothing here refers to a Mimir checkout. The output is a package directory, a
deterministic .tgz of it, its sha256, and explicit install instructions.
"""

import argparse
import gzip
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tomllib
from pathlib import Path

HERE = Path(__file__).resolve().parent
PLUGIN_FILES = ["mimir-plugin.toml", "plugin.wasm", "PACKAGE.json", "INSTALL.txt"]
SOURCE_FILES = ["Cargo.toml", "Cargo.lock", "mimir-plugin.toml", "build.py"]
REQUIRED_IMPORTS = {
    "wasi:cli/stdin": "0.3.0",
    "wasi:cli/stdout": "0.3.0",
}
REQUIRED_EXPORTS = {
    "mimir:frontend/frontend": "1.0.0",
    "mimir:plugin-core/lifecycle": "3.0.0",
}
FORBIDDEN_IMPORT_PREFIXES = ["wasi:sockets/", "wasi:http/"]


def run(command, *, cwd=HERE, env=None, capture=False):
    result = subprocess.run(
        [str(part) for part in command],
        cwd=cwd,
        env=env,
        check=False,
        text=True,
        capture_output=capture,
    )
    if result.returncode != 0:
        detail = (result.stdout or "") + (result.stderr or "") if capture else ""
        raise SystemExit(f"command failed ({result.returncode}) {' '.join(map(str, command))}\n{detail}")
    return result


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def source_files() -> list[Path]:
    files = [HERE / name for name in SOURCE_FILES]
    files += sorted((HERE / "src").rglob("*.rs"))
    return files


def source_checksum() -> str:
    digest = hashlib.sha256()
    for path in source_files():
        relative = path.relative_to(HERE).as_posix()
        digest.update(f"{relative}\0{sha256_file(path)}\n".encode())
    return digest.hexdigest()


def extract_sdk(archive: Path, work: Path, required: str) -> tuple[Path, str]:
    if not archive.is_file():
        raise SystemExit(f"SDK archive not found: {archive}")
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    with tarfile.open(archive) as bundle:
        names = bundle.getnames()
        roots = {name.split("/", 1)[0] for name in names}
        if len(roots) != 1:
            raise SystemExit(f"SDK archive has several roots: {sorted(roots)}")
        root = roots.pop()
        for member in bundle.getmembers():
            target = (work / member.name).resolve()
            if not target.is_relative_to(work.resolve()):
                raise SystemExit(f"SDK archive entry escapes the extraction directory: {member.name}")
        bundle.extractall(work, filter="data")
    sdk = work / root
    manifest = tomllib.loads((sdk / "Cargo.toml").read_text())
    package = manifest["package"]
    if package["name"] != "mimir-plugin-sdk":
        raise SystemExit(f"archive holds {package['name']}, not mimir-plugin-sdk")
    if f"={package['version']}" != required:
        raise SystemExit(f"archive version {package['version']} does not satisfy this plugin's {required}")
    if not list((sdk / "wit").rglob("*.wit")):
        raise SystemExit("SDK archive carries no WIT files")
    return sdk, package["version"]


def parse_imports(wit: str) -> tuple[dict[str, list[str]], dict[str, list[str]]]:
    imports, exports = {}, {}
    for line in wit.splitlines():
        match = re.match(r"\s*(import|export)\s+([\w:\-]+(?:/[\w\-]+)?)@([\w.\-+]+);", line)
        if match:
            (imports if match[1] == "import" else exports).setdefault(match[2], []).append(match[3])
    return imports, exports


def host_contracts_from_source() -> dict[str, str]:
    text = (HERE / "src/methods.rs").read_text()
    block = re.search(r"HOST_CONTRACTS:[^=]*=\s*&\[(.*?)\];", text, re.S)
    if not block:
        raise SystemExit("HOST_CONTRACTS not found in src/methods.rs")
    return dict(re.findall(r'\("([^"]+)",\s*"([^"]+)"\)', block[1]))


def check_component(wasm: Path) -> dict:
    run(["wasm-tools", "validate", wasm, "--features", "all"])
    wit = run(["wasm-tools", "component", "wit", wasm], capture=True).stdout
    imports, exports = parse_imports(wit)
    problems = []
    for name, version in {**REQUIRED_IMPORTS}.items():
        if version not in imports.get(name, []):
            problems.append(f"import {name}@{version} missing (found {imports.get(name)})")
    for name, version in REQUIRED_EXPORTS.items():
        if version not in exports.get(name, []):
            problems.append(f"export {name}@{version} missing (found {exports.get(name)})")
    declared = host_contracts_from_source()
    for name, version in declared.items():
        if version not in {**imports, **exports}.get(name, []):
            problems.append(f"HOST_CONTRACTS declares {name}@{version} but the component has {imports.get(name) or exports.get(name)}")
    for name in imports:
        if any(name.startswith(prefix) for prefix in FORBIDDEN_IMPORT_PREFIXES):
            problems.append(f"unexpected import {name}")
    if problems:
        raise SystemExit("component check failed\n  " + "\n  ".join(problems))
    return {"imports": imports, "exports": exports}


def deterministic_tgz(directory: Path, destination: Path, prefix: str) -> None:
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.PAX_FORMAT) as bundle:
        for path in sorted(directory.rglob("*")):
            if not path.is_file():
                continue
            info = tarfile.TarInfo(f"{prefix}/{path.relative_to(directory).as_posix()}")
            data = path.read_bytes()
            info.size = len(data)
            info.mode = 0o644
            info.mtime = 0
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            bundle.addfile(info, io.BytesIO(data))
    with destination.open("wb") as handle:
        with gzip.GzipFile(fileobj=handle, mode="wb", mtime=0, filename="") as zipped:
            zipped.write(raw.getvalue())


def install_text(name: str, version: str, tgz, digest: str, package) -> str:
    return f"""Install {name} {version}

This package is never installed automatically. Run one of these yourself.

From the package directory
  mimir plugin check {package}
  mimir plugin install {package}

From a hosted copy of the archive (Mimir installs archives from https URLs only)
  mimir plugin install https://<your-host>/{getattr(tgz, 'name', tgz)} --sha256 {digest}

Confirm
  mimir plugin list

Run (the Roboco engine does this, it speaks the framed protocol on stdio)
  mimir plugin run {name}

Remove
  mimir plugin remove {name}

Mimir keeps plugin state under MIMIR_CODING_AGENT_DIR. Set it to an empty
directory to try the plugin without touching your real configuration.
"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sdk-archive", default=os.environ.get("MIMIR_SDK_ARCHIVE"), type=Path)
    parser.add_argument("--out", default=HERE / "out", type=Path)
    parser.add_argument("--work", default=HERE / ".build", type=Path)
    parser.add_argument("--mimir", type=Path, help="run `plugin check` with this binary in isolated storage")
    args = parser.parse_args()
    if args.sdk_archive is None:
        parser.error("--sdk-archive (or MIMIR_SDK_ARCHIVE) is required")
    archive = args.sdk_archive.resolve()
    out = args.out.resolve()
    work = args.work.resolve()

    cargo = tomllib.loads((HERE / "Cargo.toml").read_text())
    crate = cargo["package"]
    required = cargo["dependencies"]["mimir-plugin-sdk"]
    manifest = tomllib.loads((HERE / "mimir-plugin.toml").read_text())
    if manifest["version"] != crate["version"]:
        raise SystemExit(f"manifest version {manifest['version']} differs from Cargo version {crate['version']}")

    sdk, sdk_version = extract_sdk(archive, work / "sdk", required)
    sdk_sha = sha256_file(archive)
    src_sha = source_checksum()

    cargo_home = Path(os.environ.get("CARGO_HOME", Path.home() / ".cargo"))
    remaps = [(HERE, "/plugin"), (work / "target", "/target"), (sdk, "/sdk"), (cargo_home / "registry", "/cargo-registry")]
    sysroot = Path(run(["rustc", "--print", "sysroot"], capture=True).stdout.strip())
    remaps.append((sysroot, "/rustc"))
    if sdk.is_relative_to(HERE):
        remaps.append((sdk.relative_to(HERE), "/sdk"))
    flags = " ".join(f"--remap-path-prefix={source}={target}" for source, target in remaps)
    env = dict(
        os.environ,
        CARGO_TARGET_DIR=str(work / "target"),
        RUSTFLAGS=(os.environ.get("RUSTFLAGS", "") + " " + flags).strip(),
    )
    patch = f"patch.crates-io.mimir-plugin-sdk.path='{sdk.as_posix()}'"
    run(["cargo", "--config", patch, "test", "--locked", "--lib"], env=env)
    run(["cargo", "--config", patch, "build", "--locked", "--release", "--target", "wasm32-wasip2"], env=env)
    metadata = json.loads(run(
        ["cargo", "--config", patch, "metadata", "--locked", "--format-version", "1", "--filter-platform", "wasm32-wasip2"],
        env=env, capture=True,
    ).stdout)
    resolved = next(p for p in metadata["packages"] if p["name"] == "mimir-plugin-sdk")
    if not Path(resolved["manifest_path"]).is_relative_to(sdk):
        raise SystemExit(f"SDK resolved outside the extracted archive: {resolved['manifest_path']}")

    built = work / "target/wasm32-wasip2/release" / (crate["name"].replace("-", "_") + ".wasm")
    report = check_component(built)
    wasm_sha = sha256_file(built)

    name, version = manifest["id"], manifest["version"]
    package = out / f"{name}-{version}"
    shutil.rmtree(package, ignore_errors=True)
    package.mkdir(parents=True)
    shutil.copy2(HERE / "mimir-plugin.toml", package / "mimir-plugin.toml")
    shutil.copy2(built, package / manifest["artifact"])
    tgz = out / f"{name}-{version}.tgz"
    info = {
        "id": name,
        "version": version,
        "protocol_version": 1,
        "source_sha256": src_sha,
        "plugin_wasm_sha256": wasm_sha,
        "sdk": {"name": "mimir-plugin-sdk", "version": sdk_version, "archive_sha256": sdk_sha},
        "imports": report["imports"],
        "exports": report["exports"],
    }
    (package / "PACKAGE.json").write_text(json.dumps(info, indent=2, sort_keys=True) + "\n")
    (package / "INSTALL.txt").write_text(install_text(name, version, tgz.name, "$(cut -d' ' -f1 " + tgz.name + ".sha256)", "."))
    deterministic_tgz(package, tgz, f"{name}-{version}")
    tgz_sha = sha256_file(tgz)
    (out / f"{tgz.name}.sha256").write_text(f"{tgz_sha}  {tgz.name}\n")
    (out / "INSTALL.txt").write_text(install_text(name, version, tgz, tgz_sha, package))

    if args.mimir:
        isolated = work / "check"
        shutil.rmtree(isolated, ignore_errors=True)
        (isolated / "home").mkdir(parents=True)
        (isolated / "agent").mkdir()
        check_env = dict(
            os.environ,
            HOME=str(isolated / "home"),
            MIMIR_CODING_AGENT_DIR=str(isolated / "agent"),
            MIMIR_MODELS_PATH=str(isolated / "absent-models.json"),
            MIMIR_DISABLE_MODELS_FETCH="1",
            NO_COLOR="1",
        )
        checked = run([args.mimir, "plugin", "check", package], env=check_env, capture=True)
        if f"Compatible: {name} {version}" not in checked.stdout:
            raise SystemExit(checked.stdout + checked.stderr)

    print(json.dumps({
        "package_dir": str(package),
        "archive": str(tgz),
        "archive_sha256": tgz_sha,
        "plugin_wasm_sha256": wasm_sha,
        "source_sha256": src_sha,
        "sdk_version": sdk_version,
        "sdk_archive_sha256": sdk_sha,
        "wasi_imports": {k: v for k, v in report["imports"].items() if k.startswith("wasi:cli/std")},
        "mimir_imports": {k: v for k, v in report["imports"].items() if k.startswith("mimir:")},
        "mimir_exports": report["exports"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
