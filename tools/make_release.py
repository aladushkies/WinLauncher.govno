#!/usr/bin/env python3
"""Build the source archive of WinLauncherRoot and print its SHA-256.

The archive used to be committed to git, which meant a stale binary copy of the sources
could sit in the repository next to the real ones. It is now generated on demand from the
working tree and is never tracked.

Usage:
    python3 tools/make_release.py                 # writes WinLauncherRoot-<version>.zip
    python3 tools/make_release.py --out dist/     # custom directory
"""
from pathlib import Path
import argparse
import hashlib
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED_DIRS = {".git", ".gradle", "build", "out", "dist", "captures", "__pycache__", ".idea", ".vscode"}
EXCLUDED_SUFFIXES = {".apk", ".aab", ".zip", ".keystore", ".jks", ".pyc", ".log"}
EXCLUDED_NAMES = {"local.properties"}


def version() -> str:
    gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    match = re.search(r"versionName\s+'([^']+)'", gradle)
    return match.group(1) if match else "0.0"


def collect() -> list[Path]:
    files = []
    for path in sorted(ROOT.rglob("*")):
        if not path.is_file():
            continue
        if any(part in EXCLUDED_DIRS for part in path.relative_to(ROOT).parts):
            continue
        if path.suffix.lower() in EXCLUDED_SUFFIXES or path.name in EXCLUDED_NAMES:
            continue
        files.append(path)
    return files


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(ROOT))
    args = parser.parse_args()
    out_dir = Path(args.out).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / f"WinLauncherRoot-{version()}.zip"
    files = collect()
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            archive.write(path, Path("WinLauncherRoot") / path.relative_to(ROOT))
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    print(f"{target}")
    print(f"files: {len(files)}")
    print(f"bytes: {target.stat().st_size}")
    print(f"sha256: {digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
