"""Koog history as an eval set: tasks from commit subjects, files as they were at the parent commit."""

import json
import re
import subprocess
from dataclasses import dataclass, asdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT / ".cache" / "koog"
MAX_BYTES = 100_000

TYPE_RE = re.compile(r"^(fix|feat|refactor|perf)(\([^)]*\))?!?:\s*", re.I)
SKIP_RE = re.compile(r"release|version|bump|changelog|typo|readme|docs?\b|revert|merge", re.I)
NOISE_RE = re.compile(r"\s*\(#\d+\)|\bKG-\d+\b:?\s*")


@dataclass
class Task:
    sha: str
    parent: str
    task: str
    truth: list[str]


def git(*args: str) -> str:
    return subprocess.run(["git", "-C", str(REPO), *args], check=True, capture_output=True, text=True).stdout


def clean_subject(subject: str) -> str:
    s = TYPE_RE.sub("", subject)
    s = NOISE_RE.sub(" ", s).strip()
    return s[:1].upper() + s[1:]


def load_tasks(limit: int = 60, min_files: int = 1, max_files: int = 8) -> list[Task]:
    log = git("log", "--no-merges", "--first-parent", "--format=%x00%H %P%x01%s", "--name-status", "develop")
    tasks = []
    for chunk in log.split("\x00")[1:]:
        head, _, body = chunk.partition("\n")
        shas, _, subject = head.partition("\x01")
        sha, *parents = shas.split()
        if len(parents) != 1 or not TYPE_RE.match(subject) or SKIP_RE.search(subject):
            continue
        modified = [ln.split("\t", 1)[1] for ln in body.splitlines()
                    if ln.startswith("M\t") and ln.endswith(".kt")]
        if not (min_files <= len(modified) <= max_files):
            continue
        if all("/test/" in p or p.endswith("Test.kt") for p in modified):
            continue
        task = clean_subject(subject)
        if len(task.split()) < 4:
            continue
        tasks.append(Task(sha, parents[0], task, modified))
        if len(tasks) >= limit:
            break
    return tasks


def files_at(rev: str) -> dict[str, str]:
    """All .kt files under MAX_BYTES as they were at `rev`, read in one git cat-file batch."""
    entries = []
    for ln in git("ls-tree", "-r", "-l", rev).splitlines():
        meta, path = ln.split("\t", 1)
        _, kind, obj, size = meta.split()
        if kind == "blob" and path.endswith(".kt") and size != "-" and int(size) <= MAX_BYTES:
            entries.append((path, obj))
    proc = subprocess.run(["git", "-C", str(REPO), "cat-file", "--batch"],
                          input="".join(f"{o}\n" for _, o in entries).encode(), capture_output=True, check=True)
    out, pos, files = proc.stdout, 0, {}
    for path, _ in entries:
        header_end = out.index(b"\n", pos)
        size = int(out[pos:header_end].split()[2])
        start = header_end + 1
        files[path] = out[start:start + size].decode("utf-8", "replace")
        pos = start + size + 1
    return files


if __name__ == "__main__":
    ts = load_tasks(limit=200)
    print(f"{len(ts)} tasks")
    for t in ts[:12]:
        print(f"  {t.sha[:8]}  {len(t.truth)} files  {t.task[:80]}")
    Path(ROOT / ".cache" / "tasks.json").write_text(json.dumps([asdict(t) for t in ts], indent=1))
