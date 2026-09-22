"""Bounded Laya retrieval benchmark on pre-commit Koog files.

Only localhost is contacted. The request matches LayaRelevance.kt.
"""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import platform
import re
import statistics
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]
WORD = re.compile(r"[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|\d+")
TYPE = re.compile(r"^(fix|feat|refactor|perf)(\([^)]*\))?!?:\s*", re.I)
SKIP = re.compile(r"release|version|bump|changelog|typo|readme|docs?\b|revert|merge", re.I)
NOISE = re.compile(r"\s*\(#\d+\)|\bKG-\d+\b:?\s*")
DECL = re.compile(r"^(?:[\w@]+(?:\([^)]*\))?\s+)*?(class|interface|object|fun|typealias|val|var|def|function|struct|enum|trait|impl)\b")
SIGNATURE_END = re.compile(r"\s[{=]\s|\s\{$|\{$|:$")
MAX_BYTES = 100_000
MAX_EXCERPT_CHARS = 1_000
MAX_TASK_CHARS = 500
FULL_CHARS = 1_000
MODEL = "english"
QUESTION_ID = "relevant"
QUESTION_PREFIX = "This source file is relevant to implementing the following coding task: "


@dataclass(frozen=True)
class Task:
    sha: str
    parent: str
    task: str
    truth: list[str]


class BenchmarkCallError(RuntimeError):
    """A model call failed; preserve the attempted request and stop this task."""

    def __init__(self, task: Task, phase: str, path: str, first: dict, second: dict):
        self.task = task
        self.phase = phase
        self.path = path
        self.first = first.copy()
        self.second = second.copy()
        calls = list(first.values()) + list(second.values())
        self.request_count = len(calls)
        self.error_count = sum(call["error"] is not None for call in calls)
        super().__init__(f"{task.sha[:8]} {phase} {path}: {calls[-1]['error']}")


def git(repo: Path, *args: str, input_data: bytes | None = None) -> bytes:
    p = subprocess.run(
        ["git", "-C", str(repo), *args],
        input=input_data, capture_output=True, check=False,
    )
    if p.returncode:
        raise RuntimeError(f"git {' '.join(args)} failed: {p.stderr.decode('utf-8', 'replace')[:500]}")
    return p.stdout


def clean_subject(subject: str) -> str:
    s = NOISE.sub(" ", TYPE.sub("", subject)).strip()
    return s[:1].upper() + s[1:]


def load_tasks(repo: Path, ref: str, limit: int) -> tuple[list[Task], list[dict]]:
    raw = git(repo, "log", "--no-renames", "--no-merges", "--first-parent",
              "--format=%x00%H %P%x01%s", "--name-status", ref).decode("utf-8", "replace")
    tasks: list[Task] = []
    skipped: list[dict] = []
    for chunk in raw.split("\x00")[1:]:
        head, _, body = chunk.partition("\n")
        shas, _, subject = head.partition("\x01")
        sha, *parents = shas.split()
        if len(parents) != 1 or not TYPE.match(subject) or SKIP.search(subject):
            continue
        modified = [line.split("\t", 1)[1] for line in body.splitlines()
                    if line.startswith("M\t") and line.endswith(".kt")]
        added = sum(line.startswith("A\t") and line.endswith(".kt") for line in body.splitlines())
        if not (1 <= len(modified) <= 8) or added > len(modified):
            continue
        if all("/test/" in p or p.endswith("Test.kt") for p in modified):
            continue
        task = clean_subject(subject)
        if len(task.split()) < 4:
            continue
        tree = git(repo, "ls-tree", "-r", parents[0], "--", *modified).decode("utf-8", "replace")
        truth_ids = {}
        for line in tree.splitlines():
            meta, path = line.split("\t", 1)
            _, kind, oid = meta.split()
            if kind == "blob":
                truth_ids[path] = oid
        missing = sorted(set(modified) - set(truth_ids))
        if missing:
            skipped.append({"sha": sha, "task": task, "reason": "truth missing at parent", "paths": missing})
            continue
        sizes_raw = git(repo, "cat-file", "--batch-check=%(objectname) %(objectsize)",
                        input_data=("".join(oid + "\n" for oid in truth_ids.values())).encode())
        sizes = {line.split()[0]: int(line.split()[1]) for line in sizes_raw.decode().splitlines()}
        oversized = {path: sizes[oid] for path, oid in truth_ids.items() if sizes[oid] > MAX_BYTES}
        if oversized:
            skipped.append({"sha": sha, "task": task, "reason": "truth exceeds plugin 100000-byte candidate cap",
                            "path_sizes": oversized})
            continue
        tasks.append(Task(sha, parents[0], task, modified))
        if len(tasks) >= limit:
            break
    if len(tasks) < limit:
        raise RuntimeError(f"Only {len(tasks)} eligible tasks in {ref}; need {limit}. Fetch more history.")
    return tasks, skipped


def files_at(repo: Path, rev: str, blob_cache: dict[str, str]) -> dict[str, str]:
    """Read <=100 kB Kotlin blobs at a parent commit; cache unchanged blobs across tasks."""
    tree = git(repo, "ls-tree", "-r", rev).decode("utf-8", "replace")
    entries: list[tuple[str, str]] = []
    for line in tree.splitlines():
        meta, path = line.split("\t", 1)
        _, kind, oid = meta.split()
        if kind == "blob" and path.endswith(".kt"):
            entries.append((path, oid))
    ids = list(dict.fromkeys(oid for _, oid in entries))
    sizes_raw = git(repo, "cat-file", "--batch-check=%(objectname) %(objectsize)",
                    input_data=("".join(oid + "\n" for oid in ids)).encode())
    sizes = {line.split()[0]: int(line.split()[1]) for line in sizes_raw.decode().splitlines()}
    needed = [oid for oid in ids if sizes[oid] <= MAX_BYTES and oid not in blob_cache]
    if needed:
        out = git(repo, "cat-file", "--batch", input_data=("".join(oid + "\n" for oid in needed)).encode())
        pos = 0
        for expected in needed:
            end = out.index(b"\n", pos)
            oid, kind, size = out[pos:end].decode().split()
            if oid != expected or kind != "blob":
                raise RuntimeError(f"Unexpected git cat-file object for {expected}")
            start = end + 1
            blob_cache[oid] = out[start:start + int(size)].decode("utf-8", "replace")
            pos = start + int(size) + 1
    return {path: blob_cache[oid] for path, oid in entries if sizes[oid] <= MAX_BYTES}


def tokens(text: str) -> list[str]:
    return [m.group().lower() for m in WORD.finditer(text) if len(m.group()) > 1]


def bm25_rank(query: str, docs: dict[str, str]) -> list[str]:
    """Port of src/main/.../pack/Bm25.kt, including path terms and stable ties."""
    if not docs:
        return []
    counts = {p: Counter(tokens(p + " " + text)) for p, text in docs.items()}
    lengths = {p: sum(c.values()) for p, c in counts.items()}
    avg = statistics.mean(lengths.values())
    df = Counter(t for c in counts.values() for t in c)
    terms = set(tokens(query))
    n = len(docs)
    scores = {}
    for path, c in counts.items():
        length = lengths[path]
        scores[path] = sum(
            math.log(1 + (n - df[t] + .5) / (df[t] + .5))
            * c[t] * 2.2 / (c[t] + 1.2 * (1 - .75 + .75 * length / avg))
            for t in terms if t in c
        )
    return sorted(docs, key=lambda p: (-scores[p], p))


def regex_sketch(path: str, text: str) -> str:
    """Port of Kotlin RegexSketcher; 1,200 character cap."""
    out = [f"path: {path}"]
    doc = None
    for raw in text.splitlines():
        line = raw.rstrip()
        s = line.strip()
        if s.startswith("package "):
            out.append(s)
        if s.startswith("/**"):
            doc = s.removeprefix("/**").removesuffix("*/").strip() or None
        indent = len(line) - len(line.lstrip())
        if indent > 8 or s.startswith(("private ", "//", "*", "import ")):
            if s.startswith("* ") and doc is None:
                doc = s.removeprefix("* ").strip()
            continue
        if DECL.search(s):
            sig = SIGNATURE_END.split(s, maxsplit=1)[0]
            out.append("  " * (indent // 4) + "- " + sig + (f"  // {doc}" if doc else ""))
            doc = None
    return "\n".join(out)[:1200]


def request_body(task: str, path: str, text: str) -> dict:
    """Content-equivalent to LayaRelevance.predict's JSON request."""
    return {
        "model": MODEL,
        "state": f"File: {path[:240]}\n{text[:MAX_EXCERPT_CHARS]}",
        "questions": {
            QUESTION_ID: {
                "type": "noul",
                "instructions": QUESTION_PREFIX + task[:MAX_TASK_CHARS],
            },
        },
    }


def task_window(task: str, text: str, limit: int = 1_000) -> tuple[str, int]:
    """A dev-only alternative: focus the excerpt on a code line matching task terms."""
    stop = {"the", "and", "for", "with", "from", "into", "when", "that", "this",
            "fix", "add", "use", "support", "make", "allow", "should", "not"}
    terms = set(tokens(task)) - stop
    best_score = 0.0
    best_start = 0
    pos = 0
    for line in text.splitlines(keepends=True):
        matched = terms.intersection(tokens(line))
        score = sum(len(t) for t in matched)
        if line.lstrip().startswith(("import ", "package ", "//", "*")):
            score *= .4
        if score > best_score:
            best_score = score
            best_start = pos
        pos += len(line)
    start = max(0, best_start - 200) if best_score else 0
    return text[start:start + limit], start


def call_json(url: str, body: dict | None, timeout: float) -> tuple[dict, float]:
    started = time.perf_counter()
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = Request(url, data=data, headers={"Content-Type": "application/json", "Accept": "application/json"},
                  method="GET" if body is None else "POST")
    with urlopen(req, timeout=timeout) as response:
        result = json.load(response)
    return result, (time.perf_counter() - started) * 1000


def score_one(endpoint: str, timeout: float, task: str, path: str, text: str) -> dict:
    started = time.perf_counter()
    try:
        res, roundtrip = call_json(endpoint, request_body(task, path, text), timeout)
        score = float(res["answers"][QUESTION_ID]["noul"])
        if not math.isfinite(score) or not 0 <= score <= 1:
            raise ValueError("Invalid probability")
        return {
            "score": score,
            "input_tokens": int(res["usage"]["input_tokens"]),
            "roundtrip_ms": round(roundtrip, 1),
            "server_ms": float(res["latency_ms"]),
            "model": res["model"],
            "error": None,
        }
    except Exception as exc:
        detail = f"{type(exc).__name__}: {str(exc)[:200]}"
        if isinstance(exc, HTTPError):
            detail += " response=" + exc.read(400).decode("utf-8", "replace")[:400]
        return {
            "score": 0.0,
            "input_tokens": 0,
            "roundtrip_ms": round((time.perf_counter() - started) * 1000, 1),
            "server_ms": None,
            "model": None,
            "error": detail,
        }


def recall(ranked: list[str], truth: list[str], k: int) -> float:
    return len(set(ranked[:k]) & set(truth)) / len(truth)


def rankings(row: dict) -> dict[str, list[str]]:
    bm25, local_bm25, pool, s1, s2 = (
        row["bm25"], row["local_bm25"], row["pool"], row["s1"], row["s2"]
    )
    pos = {path: i for i, path in enumerate(local_bm25)}
    out = {
        "bm25": bm25,
        "local_bm25": local_bm25,
        "pool_bm25": sorted(pool, key=lambda p: pos[p]),
        "laya_only": sorted(pool, key=lambda p: (-s2[p], p)),
        "laya_sketch": sorted(s1, key=lambda p: (-s1[p], p)),
    }
    for weight in (.25, .5, 1.0):
        out[f"blend_w{weight}"] = sorted(
            pool, key=lambda p: (-(s2[p] + weight / (1 + pos[p] / 10)), p),
        )
    return out


def measure_task(task: Task, repo: Path, endpoint: str, timeout: float, prefilter: int,
                 pool_size: int, blob_cache: dict[str, str]) -> dict:
    start = time.perf_counter()
    files = files_at(repo, task.parent, blob_cache)
    missing = set(task.truth) - set(files)
    if missing:
        raise RuntimeError(f"{task.sha[:8]} truth files missing from parent snapshot: {sorted(missing)}")
    loaded_ms = (time.perf_counter() - start) * 1000
    bm_start = time.perf_counter()
    bm25 = bm25_rank(task.task, files)
    prefilter_bm25_ms = (time.perf_counter() - bm_start) * 1000
    selected = bm25[:prefilter]
    local_start = time.perf_counter()
    local_bm25 = bm25_rank(task.task, {path: files[path] for path in selected})
    local_bm25_ms = (time.perf_counter() - local_start) * 1000
    s1_calls = {}
    s2_calls = {}
    p1_start = time.perf_counter()
    for idx, path in enumerate(selected, 1):
        s1_calls[path] = score_one(endpoint, timeout, task.task, path,
                                   regex_sketch(path, files[path]))
        if s1_calls[path]["error"]:
            raise BenchmarkCallError(task, "pass1", path, s1_calls, s2_calls)
        if idx % 20 == 0:
            print(f"  pass1 {idx}/{len(selected)}", flush=True)
    pass1_ms = (time.perf_counter() - p1_start) * 1000
    s1 = {path: call["score"] for path, call in s1_calls.items()}
    by_sketch = sorted(selected, key=lambda p: (-s1[p], p))
    pool = list(dict.fromkeys(local_bm25[:pool_size] + by_sketch[:pool_size]))
    p2_start = time.perf_counter()
    for idx, path in enumerate(pool, 1):
        s2_calls[path] = score_one(endpoint, timeout, task.task, path,
                                   f"path: {path}\n{files[path][:FULL_CHARS]}")
        if s2_calls[path]["error"]:
            raise BenchmarkCallError(task, "pass2", path, s1_calls, s2_calls)
        if idx % 20 == 0:
            print(f"  pass2 {idx}/{len(pool)}", flush=True)
    pass2_ms = (time.perf_counter() - p2_start) * 1000
    s2 = {path: call["score"] for path, call in s2_calls.items()}
    calls = list(s1_calls.values()) + list(s2_calls.values())
    row = {
        **asdict(task),
        "protocol_revision": 2,
        "candidate_count": len(files),
        "selected_count": len(selected),
        "pool_count": len(pool),
        "bm25": bm25,
        "local_bm25": local_bm25,
        "pool": pool,
        "s1": s1,
        "s2": s2,
        "pass1_calls": s1_calls,
        "pass2_calls": s2_calls,
        "request_count": len(calls),
        "input_tokens": sum(c["input_tokens"] for c in calls),
        "error_count": sum(c["error"] is not None for c in calls),
        "load_ms": round(loaded_ms, 1),
        "bm25_ms": round(prefilter_bm25_ms + local_bm25_ms, 1),
        "prefilter_bm25_ms": round(prefilter_bm25_ms, 1),
        "local_bm25_ms": round(local_bm25_ms, 1),
        "pass1_ms": round(pass1_ms, 1),
        "pass2_ms": round(pass2_ms, 1),
        "total_ms": round((time.perf_counter() - start) * 1000, 1),
    }
    row["recall"] = {name: {str(k): round(recall(order, task.truth, k), 4) for k in (5, 10)}
                     for name, order in rankings(row).items()}
    row["prefilter_recall"] = round(recall(selected, task.truth, len(selected)), 4)
    row["pool_ceiling"] = round(recall(pool, task.truth, len(pool)), 4)
    add_latency_stats(row)
    return row


def reconcile_dev_row(row: dict, task: Task, repo: Path, endpoint: str, timeout: float,
                      prefilter: int, pool_size: int, blob_cache: dict[str, str]) -> dict:
    """Reuse identical old HTTP responses while matching the new lexical tie/local BM25 rules."""
    files = files_at(repo, task.parent, blob_cache)
    bm_start = time.perf_counter()
    bm25 = bm25_rank(task.task, files)
    selected = bm25[:prefilter]
    local_bm25 = bm25_rank(task.task, {path: files[path] for path in selected})
    bm25_ms = (time.perf_counter() - bm_start) * 1000
    old1, old2 = row["pass1_calls"], row["pass2_calls"]
    s1_calls = {}
    supplemental = 0
    for path in selected:
        if path not in old1:
            supplemental += 1
        s1_calls[path] = old1.get(path) or score_one(endpoint, timeout, task.task, path,
                                                      regex_sketch(path, files[path]))
    s1 = {path: call["score"] for path, call in s1_calls.items()}
    by_sketch = sorted(selected, key=lambda p: (-s1[p], p))
    pool = list(dict.fromkeys(local_bm25[:pool_size] + by_sketch[:pool_size]))
    s2_calls = {}
    for path in pool:
        if path not in old2:
            supplemental += 1
        s2_calls[path] = old2.get(path) or score_one(endpoint, timeout, task.task, path,
                                                      f"path: {path}\n{files[path][:FULL_CHARS]}")
    calls = list(s1_calls.values()) + list(s2_calls.values())
    discarded = len(old1) + len(old2) - sum(path in old1 for path in selected) - sum(path in old2 for path in pool)
    row.update({
        "protocol_revision": 2,
        "bm25": bm25, "local_bm25": local_bm25, "pool": pool,
        "s1": s1, "s2": {path: call["score"] for path, call in s2_calls.items()},
        "pass1_calls": s1_calls, "pass2_calls": s2_calls,
        "candidate_count": len(files), "selected_count": len(selected), "pool_count": len(pool),
        "request_count": len(calls), "input_tokens": sum(c["input_tokens"] for c in calls),
        "error_count": sum(c["error"] is not None for c in calls),
        "bm25_ms": round(bm25_ms, 1),
        "pass1_ms": round(sum(c["roundtrip_ms"] for c in s1_calls.values()), 1),
        "pass2_ms": round(sum(c["roundtrip_ms"] for c in s2_calls.values()), 1),
        "reconciliation": {
            "reused_calls": len(calls) - supplemental,
            "supplemental_calls": supplemental,
            "discarded_exploratory_calls": discarded,
        },
    })
    row["total_ms"] = round(row["load_ms"] + row["bm25_ms"] + row["pass1_ms"] + row["pass2_ms"], 1)
    row["recall"] = {name: {str(k): round(recall(order, task.truth, k), 4) for k in (5, 10)}
                     for name, order in rankings(row).items()}
    row["prefilter_recall"] = round(recall(selected, task.truth, len(selected)), 4)
    row["pool_ceiling"] = round(recall(pool, task.truth, len(pool)), 4)
    add_latency_stats(row)
    return row


def percentile(values: list[float], pct: float) -> float | None:
    if not values:
        return None
    values = sorted(values)
    x = (len(values) - 1) * pct
    lo = math.floor(x)
    return values[lo] + (values[min(lo + 1, len(values) - 1)] - values[lo]) * (x - lo)


def add_latency_stats(row: dict) -> None:
    calls = [call for group in (row["pass1_calls"], row["pass2_calls"])
             for call in group.values() if call["error"] is None]
    for label, key in (("roundtrip", "roundtrip_ms"), ("server", "server_ms")):
        values = [call[key] for call in calls]
        row[label + "_p50_ms"] = round(percentile(values, .5), 1) if values else None
        row[label + "_p95_ms"] = round(percentile(values, .95), 1) if values else None


def mean_recall(rows: list[dict], variant: str, k: int) -> float:
    return statistics.mean(row["recall"][variant][str(k)] for row in rows)


def report(data: dict) -> str:
    rows = data["rows"]
    n_dev = data["protocol"]["dev_tasks"]
    dev, held = rows[:n_dev], rows[n_dev:]
    if any(row["error_count"] for row in held):
        raise RuntimeError("Held-out rows contain failed model calls; archive the incident and rerun the frozen task before aggregating.")
    variants = ["bm25", "local_bm25", "pool_bm25", "laya_only",
                "blend_w1.0", "blend_w0.5", "blend_w0.25"]
    calls_needed = {"bm25": 0, "local_bm25": 0, "pool_bm25": 60,
                    "laya_only": 100, "blend_w1.0": 100,
                    "blend_w0.5": 100, "blend_w0.25": 100}
    chosen = sorted(variants, key=lambda v: (-mean_recall(dev, v, 10), -mean_recall(dev, v, 5),
                                              calls_needed[v], variants.index(v)))[0]
    data["dev_selected_variant"] = chosen
    calls = [call for row in rows for group in (row["pass1_calls"], row["pass2_calls"])
             for call in group.values()]
    roundtrips = [c["roundtrip_ms"] for c in calls if c["error"] is None]
    server = [c["server_ms"] for c in calls if c["error"] is None]
    output = [
        "# Laya benchmark: Koog pre-commit retrieval",
        "",
        f"Run: {data['run_utc']}. Koog {data['koog_head'][:12]} on develop; "
        f"Laya {data['health']['version']} / PyTorch {data['health']['torch']} on "
        f"{data['health']['device']} ({data['host']['processor']}, "
        f"{data['host']['cpu_count']} logical CPUs).",
        f"The first {len(dev)} eligible tasks are development; the next {len(held)} are held out. "
        "No settings were chosen using held-out outcomes. "
        f"{len(data['skipped_tasks'])} otherwise eligible commits were excluded because a truth file "
        "could not be retrieved under the plugin's predeclared 100,000-byte cap.",
        f"Development timing conditions: {(data['conditions'].get('dev') or 'unspecified').rstrip('.')}. "
        f"Held-out timing conditions: {(data['conditions'].get('heldout') or 'unspecified').rstrip('.')}.",
        "",
        "## Held-out quality",
        "",
        "| Ranking | Recall@5 | Recall@10 |",
        "| --- | ---: | ---: |",
    ]
    for name in dict.fromkeys(["bm25", "local_bm25", "pool_bm25", "blend_w1.0", chosen, "laya_only"]):
        label = {"bm25": "BM25 full source", "blend_w1.0": "Plugin fixed fusion (weight 1.0)",
                 "local_bm25": "BM25 reranked within top 60 (0 Laya calls)",
                 "pool_bm25": "BM25 on Laya/BM25 pool (pass 1 calls)",
                 "laya_only": "Laya pass 2 only"}.get(name, f"Dev-selected {name}")
        output.append(f"| {label} | {mean_recall(held, name, 5):.3f} | {mean_recall(held, name, 10):.3f} |")
    output.extend([
        "",
        f"Dev selection: {chosen} based on development recall@10, then recall@5, "
        "then fewer local model calls. "
        "The plugin's fixed weight is 1.0 and is reported regardless of dev selection.",
        f"Mean BM25-top-{data['protocol']['prefilter']} ceiling on held-out truth: "
        f"{statistics.mean(r['prefilter_recall'] for r in held):.3f}; "
        f"mean two-pass pool ceiling: {statistics.mean(r['pool_ceiling'] for r in held):.3f}.",
        "",
        "## Held-out tasks",
        "",
        "| Commit | Task | Truth files | Candidates | BM25 @10 | Fixed Laya+BM25 @10 | Calls | Tokens | Wall time | Errors |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ])
    for row in held:
        task = row["task"].replace("|", "\\|").replace("`", "")[:75]
        output.append(
            f"| {row['sha'][:8]} | {task} | {len(row['truth'])} | {row['candidate_count']} | "
            f"{row['recall']['bm25']['10']:.2f} | {row['recall']['blend_w1.0']['10']:.2f} | "
            f"{row['request_count']} | {row['input_tokens']:,} | {row['total_ms']/1000:.1f} s | "
            f"{row['error_count']} |"
        )
    output.extend([
        "",
        "## Cost and latency",
        "",
        f"- Evaluation made **{len(calls):,} local model requests**, consumed "
        f"**{sum(r['input_tokens'] for r in rows):,} reported input tokens** and generated zero output tokens. "
        "The model's API fee was **$0**. Electricity, hardware, and developer time are excluded.",
        f"- Protocol reconciliation reused saved development responses and discarded "
        f"**{sum(r.get('reconciliation', {}).get('discarded_exploratory_calls', 0) for r in dev)}** "
        "earlier exploratory calls from protocol totals; those calls were actually made and also had $0 API fee.",
        f"- Successful request round-trip p50/p95: **{percentile(roundtrips,.5):.0f}/"
        f"{percentile(roundtrips,.95):.0f} ms**; model inference p50/p95: "
        f"**{percentile(server,.5):.0f}/{percentile(server,.95):.0f} ms**.",
        f"- Held-out mean BM25 computation: **{statistics.mean(r['bm25_ms'] for r in held):.0f} ms/task**. "
        f"Two Laya passes: **{statistics.mean(r['pass1_ms']+r['pass2_ms'] for r in held)/1000:.1f} s/task**. "
        "These are sequential CPU requests; file snapshot extraction is separate.",
        f"- Errors: **{sum(r['error_count'] for r in rows)}**. "
        f"Warmup: {data['warmup']['roundtrip_ms']:.0f} ms round-trip, "
        f"{data['warmup']['server_ms']:.0f} ms inference, excluded from task metrics. "
        "Cold model load was not measured in this run; the separately observed cached startup was about 47 s.",
        "",
        "## Interpretation and limits",
        "",
        "This is a small, recent, commit-subject benchmark. Ground truth is modified Kotlin files in each commit; "
        "a file can be useful without being modified, and a modified file can be hard to infer from a short subject. "
        "The corpus is each commit's parent snapshot, so changed content cannot leak into retrieval.",
        "The English checkpoint has a 512-token total window. Each request contains one file; the adapter caps "
        "its text at 1,000 characters, and the model may truncate further after the question header. "
        "BM25 reads full source. The pass-1 prefilter caps Laya's ceiling and the pass-2 pool caps final recall.",
        "The script mirrors the Kotlin request body and retrieval math, but is a Python harness over Git snapshots, "
        "not an IntelliJ UI or end-to-end plugin timing measurement. "
        "Score ties are broken by lexicographic path, matching the plugin's explicit tie rules. "
        "A larger held-out sample and direct IDE run are needed before claiming a quality gain.",
        "Excluded commits (before splitting): "
        + "; ".join(f"{item['sha'][:8]}: {item['reason']}" for item in data["skipped_tasks"]) + ".",
        "",
        "Reproduction and exact protocol: eval/LAYA.md. Raw scores, rankings, tokens, request timings, "
        "and errors: laya-benchmark.json.",
        "",
    ])
    if data.get("development_experiments"):
        experiment_lines = []
        for experiment in data["development_experiments"]:
            experiment_lines.append(
                f"- Separate development-only experiment: **{experiment['request_count']} calls**, "
                f"recorded in {experiment['artifact']} and excluded from the fixed-protocol "
                "totals and held-out selection."
            )
        # Keep supplemental accounting with the cost section, before any incident narrative.
        cost_end = output.index("## Interpretation and limits")
        output[cost_end:cost_end] = experiment_lines + [""]
    if data.get("incidents") or data.get("aborted_attempts"):
        incident_lines = ["## Infrastructure incident", ""]
        for incident in data.get("incidents", []):
            incident_lines.append(
                f"A prior attempt at {incident['sha'][:8]} made **{incident['request_count']} calls**, "
                f"with **{incident['error_count']} failures** before the local Laya server exited. "
                f"It is preserved in {incident['artifact']} and excluded from the successful "
                "quality, token, call, and latency aggregates above. The task was retried under "
                "the identical frozen protocol after server recovery."
            )
        for attempt in data.get("aborted_attempts", []):
            incident_lines.append(
                f"An interrupted attempt at {attempt['sha'][:8]} stopped immediately after "
                f"**{attempt['request_count']} calls** and **{attempt['error_count']} failures**. "
                f"Full call details are in {attempt['artifact']}; this attempt is excluded from "
                "successful aggregates and can be retried with --resume without changing settings."
            )
        if data.get("uncheckpointed_interruption"):
            interruption = data["uncheckpointed_interruption"]
            incident_lines.append(
                f"The following task, {interruption['sha'][:8]}, was interrupted before its "
                f"first task checkpoint. Its partial request count is {interruption['request_count_range']} "
                "because only 20-call progress markers were emitted; it contributed no ranking "
                "or timing result to the aggregate."
            )
        incident_lines.append("")
        marker = output.index("## Interpretation and limits")
        output[marker:marker] = incident_lines
    return "\n".join(output)


def save_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
    tmp.replace(path)


def run_dev_variant(args: argparse.Namespace, repo: Path) -> None:
    """Compare a 20/40-call task-window single pass using development tasks only."""
    baseline = json.loads(args.dev_variant_from.read_text(encoding="utf-8"))
    n = baseline["protocol"]["dev_tasks"]
    if len(baseline["rows"]) < n:
        raise RuntimeError(f"Baseline needs {n} completed development rows first.")
    head = git(repo, "rev-parse", args.ref).decode().strip()
    if head != baseline["koog_head"]:
        raise RuntimeError("Koog ref differs from the baseline run.")
    health, _ = call_json(args.endpoint.removesuffix("/api/predict") + "/api/health", None, args.timeout)
    if health["models"].get(MODEL) != "ready":
        raise RuntimeError(f"Laya {MODEL} is not ready: {health['models']}")
    if args.resume and args.output.exists():
        data = json.loads(args.output.read_text(encoding="utf-8"))
        if data["source_koog_head"] != head or data["candidate_limit"] != args.variant_candidates:
            raise RuntimeError("Variant resume file differs in corpus or candidate limit.")
    else:
        data = {
            "run_utc": datetime.now(timezone.utc).isoformat(),
            "source_baseline": str(args.dev_variant_from),
            "source_koog_head": head,
            "model": MODEL,
            "health": health,
            "candidate_limit": args.variant_candidates,
            "excerpt": "task_window: best code line by lexical overlap, 200 chars of preceding context, then 1000 chars",
            "rows": [],
        }
        save_json(args.output, data)
    done = {row["sha"] for row in data["rows"]}
    blob_cache: dict[str, str] = {}
    for original in baseline["rows"][:n]:
        if original["sha"] in done:
            continue
        task = Task(original["sha"], original["parent"], original["task"], original["truth"])
        files = files_at(repo, task.parent, blob_cache)
        bm25 = original["bm25"]
        candidates = bm25[:args.variant_candidates]
        calls = {}
        offsets = {}
        started = time.perf_counter()
        print(f"Dev variant {task.sha[:8]}: {len(candidates)} calls", flush=True)
        for idx, path in enumerate(candidates, 1):
            excerpt, offset = task_window(task.task, files[path])
            offsets[path] = offset
            calls[path] = score_one(args.endpoint, args.timeout, task.task, path,
                                    f"path: {path}\n{excerpt}")
            if idx % 20 == 0:
                print(f"  {idx}/{len(candidates)}", flush=True)
        bmpos = {path: i for i, path in enumerate(bm25)}
        score = {path: call["score"] for path, call in calls.items()}
        variants = {"bm25": bm25}
        for k in (20, 40):
            if k > len(candidates):
                continue
            subset = candidates[:k]
            variants[f"single_{k}_laya"] = sorted(subset, key=lambda p: (-score[p], p))
            variants[f"single_{k}_blend"] = sorted(
                subset, key=lambda p: (-(score[p] + 1 / (1 + bmpos[p] / 10)), p),
            )
        data["rows"].append({
            **asdict(task),
            "calls": calls,
            "window_offsets": offsets,
            "input_tokens": sum(c["input_tokens"] for c in calls.values()),
            "error_count": sum(c["error"] is not None for c in calls.values()),
            "wall_ms": round((time.perf_counter() - started) * 1000, 1),
            "recall": {name: {str(k): round(recall(paths, task.truth, k), 4)
                              for k in (5, 10)} for name, paths in variants.items()},
            "ceiling": {str(k): round(recall(candidates[:k], task.truth, k), 4) for k in (20, 40)},
        })
        save_json(args.output, data)
    rows = data["rows"]
    baseline_by_sha = {row["sha"]: row for row in baseline["rows"][:n]}
    for row in rows:
        original = baseline_by_sha[row["sha"]]
        row["recall"]["local_bm25"] = original["recall"]["local_bm25"]
        row["recall"]["fixed_two_pass"] = original["recall"]["blend_w1.0"]
    save_json(args.output, data)
    lines = [
        "# Development-only Laya efficiency experiment",
        "",
        f"Koog {head[:12]}, Laya {health['version']} on {health['device']}. "
        f"Only the first {n} eligible development tasks were scored. "
        "No held-out task or label was used.",
        "The variant sends a task-term-focused 1,000-character source window for "
        "each of BM25's top 40 files, one local HTTP request per file. "
        "Top 20 results use the first 20 of those saved calls. "
        "It has one pass instead of the plugin baseline's two.",
        "",
        "| Development ranking | Recall@5 | Recall@10 | Calls/task |",
        "| --- | ---: | ---: | ---: |",
    ]
    for name, count in (("bm25", 0), ("local_bm25", 0), ("fixed_two_pass", 94),
                        ("single_20_laya", 20), ("single_20_blend", 20),
                        ("single_40_laya", 40), ("single_40_blend", 40)):
        if name not in rows[0]["recall"]:
            continue
        lines.append(f"| {name} | {mean_recall(rows, name, 5):.3f} | "
                     f"{mean_recall(rows, name, 10):.3f} | {count} |")
    lines.extend([
        "",
        f"Mean candidate ceiling: top 20 {statistics.mean(r['ceiling']['20'] for r in rows):.3f}; "
        + (f"top 40 {statistics.mean(r['ceiling']['40'] for r in rows):.3f}. "
           if args.variant_candidates >= 40 else ""),
        "The fixed two-pass row uses 93–94 calls per task on these tasks; its 94 is a rounded comparison.",
        f"Actual calls: {sum(len(row['calls']) for row in rows)}; reported input tokens: "
        f"{sum(row['input_tokens'] for row in rows):,}; errors: "
        f"{sum(row['error_count'] for row in rows)}; local API fee: $0 excluding hardware and electricity.",
        "This is exploratory development evidence. Any selected variant must be frozen before "
        "a first held-out evaluation; comparing these development results to the held-out "
        "baseline would not be a valid independent test.",
        "",
    ])
    args.markdown.parent.mkdir(parents=True, exist_ok=True)
    args.markdown.write_text("\n".join(lines), encoding="utf-8")
    print(f"Saved {args.output} and {args.markdown}", flush=True)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--repo", type=Path, default=ROOT / ".cache" / "koog")
    ap.add_argument("--ref", default="develop")
    ap.add_argument("--endpoint", default="http://127.0.0.1:8770/api/predict")
    ap.add_argument("--dev", type=int, default=3)
    ap.add_argument("--heldout", type=int, default=5)
    ap.add_argument("--prefilter", type=int, default=60)
    ap.add_argument("--pool", type=int, default=20)
    ap.add_argument("--timeout", type=float, default=90)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--markdown", type=Path, required=True)
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--stop-after", type=int, help="Stop after this many completed tasks, leaving JSON resumable.")
    ap.add_argument("--dev-note", help="Observed development timing conditions, such as concurrent build load.")
    ap.add_argument("--heldout-note", help="Observed held-out timing conditions.")
    ap.add_argument("--dev-variant-from", type=Path, help="Run the task-window single-pass experiment on this baseline's dev rows only.")
    ap.add_argument("--variant-candidates", type=int, default=40)
    args = ap.parse_args()
    if not (1 <= args.dev <= 10 and 1 <= args.heldout <= 30 and
            1 <= args.prefilter <= 60 and 1 <= args.pool <= 20 and args.timeout > 0):
        ap.error("Use 1..10 dev tasks, 1..30 held-out tasks, 1..60 candidates, 1..20 pool, positive timeout.")
    parsed = urlparse(args.endpoint)
    if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost", "::1") or parsed.path != "/api/predict":
        ap.error("Endpoint must be a loopback HTTP /api/predict URL.")
    repo = args.repo.resolve()
    if args.dev_variant_from:
        if args.variant_candidates not in (20, 40):
            ap.error("--variant-candidates must be 20 or 40.")
        run_dev_variant(args, repo)
        return
    head = git(repo, "rev-parse", args.ref).decode().strip()
    tasks, skipped = load_tasks(repo, args.ref, args.dev + args.heldout)
    if args.stop_after is not None and not 1 <= args.stop_after <= len(tasks):
        ap.error("--stop-after must be between 1 and the total task count.")
    protocol = {
        "dev_tasks": args.dev, "heldout_tasks": args.heldout,
        "prefilter": args.prefilter, "pool": args.pool, "max_blob_bytes": MAX_BYTES,
        "sketch_chars": 1200, "full_chars": FULL_CHARS,
        "adapter_excerpt_chars": MAX_EXCERPT_CHARS, "adapter_task_chars": MAX_TASK_CHARS,
        "model": MODEL, "question": QUESTION_PREFIX, "fusion_weight": 1.0,
        "timeout_seconds": args.timeout, "sequential_requests": True,
        "bm25_rerank_corpus": "prefiltered candidates", "tie_break": "path",
    }
    health, _ = call_json(args.endpoint.removesuffix("/api/predict") + "/api/health", None, args.timeout)
    if health["models"].get(MODEL) != "ready":
        raise RuntimeError(f"Laya {MODEL} is not ready: {health['models']}")
    if args.resume and args.output.exists():
        data = json.loads(args.output.read_text(encoding="utf-8"))
        legacy = data["protocol"]
        comparable = ("dev_tasks", "heldout_tasks", "prefilter", "pool", "max_blob_bytes",
                      "sketch_chars", "adapter_excerpt_chars", "adapter_task_chars",
                      "model", "question", "fusion_weight", "timeout_seconds", "sequential_requests")
        if data["koog_head"] != head or any(legacy.get(k) != protocol[k] for k in comparable):
            raise RuntimeError("Resume file has a different Koog HEAD or protocol.")
        if legacy != protocol and any(i >= args.dev for i, _ in enumerate(data["rows"])):
            raise RuntimeError("A prior protocol already scored held-out tasks; start a separate run.")
        data["protocol"] = protocol
        data["tasks"] = [asdict(t) for t in tasks]
        data["skipped_tasks"] = skipped
        data.setdefault("resume_sessions", []).append({
            "utc": datetime.now(timezone.utc).isoformat(),
            "health": health,
            "completed_rows_at_start": len(data["rows"]),
        })
    else:
        warmup = score_one(args.endpoint, args.timeout, "Classify a source file", "__warmup__.kt",
                           "class Warmup")
        if warmup["error"]:
            raise RuntimeError(f"Warmup failed: {warmup['error']}")
        data = {
            "run_utc": datetime.now(timezone.utc).isoformat(),
            "koog_head": head,
            "koog_remote": git(repo, "remote", "get-url", "origin").decode().strip(),
            "protocol": protocol,
            "health": health,
            "host": {"platform": platform.platform(), "cpu_count": os.cpu_count(),
                     "python": platform.python_version(),
                     "processor": platform.processor() or os.environ.get("PROCESSOR_IDENTIFIER", "unknown")},
            "conditions": {},
            "cold_start_seconds": None,
            "warmup": warmup,
            "tasks": [asdict(t) for t in tasks],
            "skipped_tasks": skipped,
            "rows": [],
        }
        save_json(args.output, data)
    data["host"].setdefault("processor", platform.processor() or os.environ.get("PROCESSOR_IDENTIFIER", "unknown"))
    data.setdefault("conditions", {})
    if args.dev_note:
        data["conditions"]["dev"] = args.dev_note
    if args.heldout_note:
        data["conditions"]["heldout"] = args.heldout_note
    done = {r["sha"] for r in data["rows"]}
    blob_cache: dict[str, str] = {}
    for index, row in enumerate(data["rows"]):
        if row.get("protocol_revision", 1) < 2:
            task = tasks[index]
            if row["sha"] != task.sha:
                raise RuntimeError("Stored rows do not match the newly selected eligible tasks.")
            print(f"Reconciling dev {task.sha[:8]} with local BM25 and path tie-breaks", flush=True)
            data["rows"][index] = reconcile_dev_row(
                row, task, repo, args.endpoint, args.timeout, args.prefilter, args.pool, blob_cache,
            )
            save_json(args.output, data)
        data["rows"][index]["recall"] = {
            name: {str(k): round(recall(order, tasks[index].truth, k), 4) for k in (5, 10)}
            for name, order in rankings(data["rows"][index]).items()
        }
        add_latency_stats(data["rows"][index])
    for index, task in enumerate(tasks, 1):
        if args.stop_after is not None and len(data["rows"]) >= args.stop_after:
            break
        if task.sha in done:
            print(f"[{index}/{len(tasks)}] resume {task.sha[:8]}", flush=True)
            continue
        print(f"[{index}/{len(tasks)}] {task.sha[:8]} {task.task}", flush=True)
        try:
            row = measure_task(task, repo, args.endpoint, args.timeout, args.prefilter,
                               args.pool, blob_cache)
        except BenchmarkCallError as failure:
            attempt_number = 1 + sum(item["sha"] == task.sha for item in data.get("aborted_attempts", []))
            incident_path = args.output.with_name(
                f"{args.output.stem}-incident-{task.sha[:8]}-{attempt_number}.json"
            )
            incident = {
                "task": asdict(task), "phase": failure.phase, "failed_path": failure.path,
                "request_count": failure.request_count, "error_count": failure.error_count,
                "first_pass_calls": failure.first, "second_pass_calls": failure.second,
                "health_at_start": health,
            }
            save_json(incident_path, incident)
            data.setdefault("aborted_attempts", []).append({
                "sha": task.sha, "request_count": failure.request_count,
                "error_count": failure.error_count, "artifact": incident_path.name,
            })
            save_json(args.output, data)
            print(f"Aborted on first failed call; details saved to {incident_path}: {failure}",
                  file=sys.stderr, flush=True)
            raise SystemExit(2)
        data["rows"].append(row)
        save_json(args.output, data)
        print(f"  BM25@10={row['recall']['bm25']['10']:.2f}, "
              f"Laya+BM25@10={row['recall']['blend_w1.0']['10']:.2f}, "
              f"{row['request_count']} calls, {row['total_ms']/1000:.1f}s, "
              f"{row['error_count']} errors", flush=True)
    if args.stop_after is not None and len(data["rows"]) >= args.stop_after:
        save_json(args.output, data)
        print(f"Stopped after {len(data['rows'])} completed tasks; resume with --resume.", flush=True)
        return
    if len(data["rows"]) != len(tasks):
        raise RuntimeError("Incomplete result; use --resume.")
    markdown = report(data)
    save_json(args.output, data)
    args.markdown.parent.mkdir(parents=True, exist_ok=True)
    args.markdown.write_text(markdown, encoding="utf-8")
    print(f"Saved {args.output} and {args.markdown}", flush=True)


if __name__ == "__main__":
    main()
