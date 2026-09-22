"""Recompute the frozen 30-task Laya-vs-BM25 paired recall interval from saved rows."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
from pathlib import Path
import random
import zlib

SEED = 20260923
RESAMPLES = 10_000
SPLIT = "rows[3:33]: original 5 plus frozen 25 extension, excluding development rows 0:3"
METHOD = (
    "Paired task bootstrap: resample 30 whole tasks with replacement; mean of exact per-task "
    "(fixed Laya+BM25 recall@10 minus full-source BM25 recall@10); 2.5th and 97.5th empirical "
    "percentiles with linear interpolation at (n-1)*q."
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def paths(value: object, label: str) -> list[str]:
    require(isinstance(value, list) and bool(value), f"{label} must be a nonempty list")
    require(all(isinstance(path, str) and path for path in value), f"{label} contains an invalid path")
    require(len(set(value)) == len(value), f"{label} contains duplicate paths")
    return value


def percentile(sorted_values: list[float], q: float) -> float:
    position = (len(sorted_values) - 1) * q
    lower = int(position)
    higher = min(lower + 1, len(sorted_values) - 1)
    return sorted_values[lower] + (sorted_values[higher] - sorted_values[lower]) * (position - lower)


def reject_constant(value: str) -> None:
    raise ValueError(f"invalid JSON number {value}")


def read_source(path: Path) -> tuple[bytes, dict]:
    require(path.name.endswith((".json", ".json.gz")), "input must be .json or .json.gz")
    content = path.read_bytes()
    raw = gzip.decompress(content) if path.name.endswith(".json.gz") else content
    source = json.loads(raw, parse_constant=reject_constant)
    require(isinstance(source, dict), "input must contain a JSON object")
    return raw, source


def heldout_rows(source: dict) -> list[dict]:
    protocol = source.get("protocol")
    require(isinstance(protocol, dict), "missing benchmark protocol")
    expected = {
        "dev_tasks": 3,
        "heldout_tasks": 30,
        "fusion_weight": 1.0,
        "bm25_rerank_corpus": "prefiltered candidates",
        "tie_break": "path",
    }
    for key, value in expected.items():
        require(protocol.get(key) == value, f"protocol.{key} must be {value!r}")
    rows, tasks = source.get("rows"), source.get("tasks")
    require(isinstance(rows, list) and len(rows) == 33, "expected exactly 3 development and 30 held-out rows")
    require(isinstance(tasks, list) and len(tasks) == 33, "expected exactly 33 frozen task definitions")
    seen = set()
    for index, (row, task) in enumerate(zip(rows, tasks)):
        label = f"row {index}"
        require(isinstance(row, dict) and isinstance(task, dict), f"{label} or task is not an object")
        sha = row.get("sha")
        require(isinstance(sha, str) and len(sha) == 40 and sha not in seen, f"{label} has an invalid or duplicate commit")
        seen.add(sha)
        require(all(row.get(key) == task.get(key) for key in ("sha", "parent", "task", "truth")),
                f"{label} does not match its frozen task definition")
        require(row.get("protocol_revision") == 2, f"{label} has a different ranking protocol")
    provenance = source.get("extension_provenance")
    if provenance is not None:
        require(isinstance(provenance, dict) and provenance.get("reused_completed_rows") == 8
                and provenance.get("new_task_count") == 25
                and provenance.get("settings_unchanged_except_task_count") is True,
                "extension provenance does not describe the frozen 5+25 held-out split")
    return rows[3:33]


def exact_recall(row: dict, index: int) -> tuple[dict, float]:
    label = f"held-out row {index}"
    truth = set(paths(row.get("truth"), f"{label}.truth"))
    bm25 = paths(row.get("bm25"), f"{label}.bm25")
    local = paths(row.get("local_bm25"), f"{label}.local_bm25")
    pool = paths(row.get("pool"), f"{label}.pool")
    require(len(bm25) == row.get("candidate_count") and truth <= set(bm25),
            f"{label}.bm25 is incomplete or omits a truth file")
    require(len(local) == row.get("selected_count"), f"{label}.local_bm25 is incomplete")
    require(set(local) <= set(bm25), f"{label}.local_bm25 contains a path outside the candidate corpus")
    require(len(pool) == row.get("pool_count"), f"{label}.pool is incomplete")
    scores = row.get("s2")
    require(isinstance(scores, dict) and scores.keys() == set(pool), f"{label}.s2 must score every pool path exactly")
    require(set(pool) <= set(local), f"{label}.pool contains a path absent from local_bm25")
    require(all(isinstance(score, (int, float)) and not isinstance(score, bool)
                and math.isfinite(score) and 0 <= score <= 1 for score in scores.values()),
            f"{label}.s2 contains an invalid probability")
    require(row.get("error_count") == 0, f"{label} has failed model calls")
    positions = {path: position for position, path in enumerate(local)}
    laya = sorted(pool, key=lambda path: (-(scores[path] + 1 / (1 + positions[path] / 10)), path))
    bm25_hits = len(truth.intersection(bm25[:10]))
    laya_hits = len(truth.intersection(laya[:10]))
    recall = row.get("recall")
    require(isinstance(recall, dict) and isinstance(recall.get("bm25"), dict)
            and isinstance(recall.get("blend_w1.0"), dict), f"{label} is missing saved recall")
    require(round(bm25_hits / len(truth), 4) == recall["bm25"].get("10")
            and round(laya_hits / len(truth), 4) == recall["blend_w1.0"].get("10"),
            f"{label} saved recall does not match its exact rankings")
    difference = laya_hits / len(truth) - bm25_hits / len(truth)
    return {
        "sha": row["sha"],
        "truth_files": len(truth),
        "bm25_hits_at_10": bm25_hits,
        "laya_hits_at_10": laya_hits,
        "paired_difference": difference,
    }, difference


def calculate(raw: bytes, source: dict, source_name: str, seed: int, resamples: int) -> dict:
    require(resamples > 0, "resamples must be positive")
    rows = heldout_rows(source)
    paired = [exact_recall(row, index) for index, row in enumerate(rows, start=3)]
    tasks = [task for task, _ in paired]
    differences = [difference for _, difference in paired]
    rng = random.Random(seed)
    means = sorted(
        sum(differences[rng.randrange(len(differences))] for _ in differences) / len(differences)
        for _ in range(resamples)
    )
    provenance = source.get("extension_provenance")
    split = SPLIT if provenance is not None else "rows[3:33]: 30 held-out tasks, excluding development rows 0:3"
    return {
        "source_file": source_name.removesuffix(".gz"),
        "source_sha256": hashlib.sha256(raw).hexdigest(),
        "split": split,
        "method": METHOD,
        "seed": seed,
        "resamples": resamples,
        "tasks": tasks,
        "mean_bm25_recall_at_10": sum(t["bm25_hits_at_10"] / t["truth_files"] for t in tasks) / len(tasks),
        "mean_laya_recall_at_10": sum(t["laya_hits_at_10"] / t["truth_files"] for t in tasks) / len(tasks),
        "mean_paired_difference": sum(differences) / len(differences),
        "percentile_95_interval": [percentile(means, .025), percentile(means, .975)],
        "task_counts": {
            "improved": sum(x > 0 for x in differences),
            "tied": sum(x == 0 for x in differences),
            "worse": sum(x < 0 for x in differences),
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="saved extended benchmark .json or .json.gz")
    parser.add_argument("--output", required=True, type=Path, help="new result .json or .json.gz; never overwritten")
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--resamples", type=int, default=RESAMPLES)
    args = parser.parse_args()
    try:
        require(args.output.name.endswith((".json", ".json.gz")), "output must be .json or .json.gz")
        raw, source = read_source(args.input)
        result = calculate(raw, source, args.input.name, args.seed, args.resamples)
        output = (json.dumps(result, indent=2) + "\n").encode("utf-8")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        if args.output.name.endswith(".json.gz"):
            with gzip.open(args.output, "xb") as target:
                target.write(output)
        else:
            with args.output.open("xb") as target:
                target.write(output)
    except (OSError, EOFError, UnicodeError, ValueError, KeyError, TypeError, zlib.error) as error:
        parser.exit(1, f"paired_recall: {error}\n")
    low, high = result["percentile_95_interval"]
    print(f"paired difference {result['mean_paired_difference']:+.6f}, 95% CI [{low:+.6f}, {high:+.6f}]")


if __name__ == "__main__":
    main()
