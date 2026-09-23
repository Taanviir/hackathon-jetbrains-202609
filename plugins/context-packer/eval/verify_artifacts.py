"""Verify the stored and decompressed hashes of the published local Laya evidence."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path
import zlib


def verify(directory: Path) -> int:
    directory = directory.resolve()
    manifest = json.loads((directory / "ARTIFACTS.json").read_text(encoding="utf-8"))
    entries = manifest["artifacts"]
    if not isinstance(entries, list) or not entries:
        raise ValueError("ARTIFACTS.json must list at least one artifact")
    seen = set()
    for entry in entries:
        name = entry["file"]
        if (not isinstance(name, str) or not name or name in (".", "..")
                or "/" in name or "\\" in name or ":" in name or name in seen):
            raise ValueError("Artifact names must be unique plain filenames")
        seen.add(name)
        path = directory / name
        if path.resolve().parent != directory:
            raise ValueError(f"{name}: artifact resolves outside the evidence directory")
        stored = path.read_bytes()
        if len(stored) != entry["stored_bytes"]:
            raise ValueError(f"{name}: stored byte count differs")
        if hashlib.sha256(stored).hexdigest() != entry["stored_sha256"]:
            raise ValueError(f"{name}: stored SHA-256 differs")
        if name.endswith(".gz"):
            raw = gzip.decompress(stored)
            if len(raw) != entry["uncompressed_bytes"]:
                raise ValueError(f"{name}: decompressed byte count differs")
            if hashlib.sha256(raw).hexdigest() != entry["uncompressed_sha256"]:
                raise ValueError(f"{name}: decompressed SHA-256 differs")
            if name.endswith(".json.gz"):
                json.loads(raw)
    return len(entries)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path,
                        default=Path(__file__).resolve().parent / "results" / "laya")
    args = parser.parse_args()
    try:
        count = verify(args.directory)
    except (OSError, ValueError, KeyError, TypeError, EOFError, zlib.error) as error:
        parser.exit(1, f"verify_artifacts: {error}\n")
    print(f"Verified {count} artifacts: stored and decompressed hashes match.")


if __name__ == "__main__":
    main()
