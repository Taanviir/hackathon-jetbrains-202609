"""Verify or replay the frozen 2,240-call local Laya response-cache stability diagnostic."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import json
import math
from pathlib import Path
import sys
import time
import zlib

import laya_benchmark as bench

CHECKPOINT = "1c5edc17a7acd8701df6fc341c0d179f1c62c982"
COUNTS = (60, 60, 30)
CYCLES = 14
HOT_LAST = 10
CAPACITY = 128
PRIVATE_LIMIT = 5 * 1024**3
C_FREE_FLOOR = 3 * 1024**3
HEALTH = "http://127.0.0.1:8770/api/health"
PREDICT = "http://127.0.0.1:8770/api/predict"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def read_json(path: Path) -> tuple[bytes, dict]:
    require(path.name.endswith((".json", ".json.gz")), f"{path} must be JSON or JSON.gz")
    data = path.read_bytes()
    raw = gzip.decompress(data) if path.name.endswith(".json.gz") else data
    parsed = json.loads(raw)
    require(isinstance(parsed, dict), f"{path} must contain a JSON object")
    return raw, parsed


def digest_body(body: dict) -> str:
    return hashlib.sha256(json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()


def order() -> list[int]:
    return [index for _ in range(CYCLES) for index in list(range(150)) + list(range(150 - HOT_LAST, 150))]


def sequence_digest(entries: list[dict]) -> str:
    return hashlib.sha256("\n".join(entries[index]["request_sha256"] for index in order()).encode()).hexdigest()


def build_inputs(baseline: dict, koog: Path) -> tuple[list[dict], list[dict]]:
    protocol = baseline.get("protocol")
    require(isinstance(protocol, dict) and protocol.get("dev_tasks") == 3
            and protocol.get("model") == "english" and protocol.get("sketch_chars") == 1200
            and protocol.get("adapter_excerpt_chars") == 1000
            and protocol.get("adapter_task_chars") == 500,
            "Baseline does not use the frozen three-task English sketch protocol")
    tasks, rows = baseline.get("tasks"), baseline.get("rows")
    require(isinstance(tasks, list) and isinstance(rows, list) and len(tasks) >= 3 and len(rows) >= 3,
            "Baseline is missing the three development tasks or rows")
    entries: list[dict] = []
    bodies: list[dict] = []
    for i, count in enumerate(COUNTS):
        task, row = tasks[i], rows[i]
        require(isinstance(task, dict) and isinstance(row, dict) and task.get("sha") == row.get("sha"),
                f"Development task {i} does not match its saved row")
        calls = row.get("pass1_calls")
        require(isinstance(calls, dict) and len(calls) >= count, f"Development row {i} lacks saved pass-1 calls")
        files = bench.files_at(koog, task["parent"], {})
        for path in list(calls)[:count]:
            require(path in files, f"Development row {i} source is absent from its parent commit: {path}")
            score = calls[path].get("score")
            require(type(score) in (int, float) and math.isfinite(score) and 0 <= score <= 1,
                    f"Development row {i} has an invalid saved score: {path}")
            body = bench.request_body(task["task"], path, bench.regex_sketch(path, files[path]))
            entries.append({
                "task_sha": task["sha"], "path": path, "request_sha256": digest_body(body),
                "expected_score": score,
            })
            bodies.append(body)
    require(len(entries) == 150 and len({e["request_sha256"] for e in entries}) == 150,
            "Expected 150 distinct development request bodies")
    return entries, bodies


def verify(baseline: dict, manifest: dict, koog: Path) -> tuple[list[dict], list[dict]]:
    require(manifest.get("koog_head") == baseline.get("koog_head"), "Koog head differs from frozen manifest")
    expected = {
        "checkpoint": CHECKPOINT, "cache_capacity": CAPACITY,
        "request_counts_by_dev_task": list(COUNTS), "distinct_requests": 150,
        "cycles": CYCLES, "repeat_last_per_cycle": HOT_LAST, "total_requests": 2240,
        "stop_private_bytes": PRIVATE_LIMIT, "stop_c_free_bytes": C_FREE_FLOOR,
    }
    for key, value in expected.items():
        require(manifest.get(key) == value, f"Frozen manifest {key} differs from {value!r}")
    entries, bodies = build_inputs(baseline, koog)
    require(manifest.get("requests") == entries, "Frozen request paths, scores, or body hashes differ")
    digest = sequence_digest(entries)
    require(manifest.get("sequence_sha256") == digest and len(order()) == 2240,
            "Frozen 2,240-call order or sequence SHA-256 differs")
    return entries, bodies


def checkpoint(path: Path, result: dict) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    temporary.replace(path)


def check_limits(resources: dict, manifest: dict) -> None:
    require(resources["private_bytes"] < manifest["stop_private_bytes"], "5 GiB server-private-memory stop")
    require(resources["c_free_bytes"] >= manifest["stop_c_free_bytes"], "3 GiB C: free-space stop")


def check_health(health: dict) -> None:
    bench.require_checkpoint(health, CHECKPOINT)
    models = health.get("models")
    require(isinstance(models, dict) and models.get("english") == "ready", "English Laya is not ready")
    cache = health.get("response_cache")
    require(isinstance(cache, dict) and cache.get("capacity") == CAPACITY
            and cache.get("entries") == 0 and cache.get("hits") == 0,
            "Start a fresh --response-cache 128 server before replay")


def validate_response(response: dict, entry: dict, should_hit: bool) -> float:
    score = response["answers"][bench.QUESTION_ID]["noul"]
    require(type(score) in (int, float) and math.isfinite(score) and 0 <= score <= 1,
            "Laya returned an invalid probability")
    delta = abs(score - entry["expected_score"])
    require(delta <= 0.000001, "Saved score parity failed")
    require(response.get("cache_hit") is should_hit, f"cache_hit mismatch: expected {should_hit}")
    usage = response["usage"]
    tokens, cached = usage["input_tokens"], usage.get("cached_input_tokens", 0)
    require(type(tokens) is int and tokens >= 0 and type(cached) is int and cached >= 0,
            "Laya returned invalid token usage")
    require((not should_hit and cached == 0) or (should_hit and tokens == 0),
            "Laya returned usage inconsistent with its cache_hit flag")
    latency = response["latency_ms"]
    require(type(latency) in (int, float) and math.isfinite(latency) and latency >= 0,
            "Laya returned invalid inference latency")
    return delta


def run(pid: int, output: Path, baseline_raw: bytes, manifest_raw: bytes, manifest: dict,
        entries: list[dict], bodies: list[dict]) -> None:
    require(sys.platform == "win32" and pid > 0, "Live replay requires Windows and a positive Laya server PID")
    require(not output.exists(), f"Refusing to overwrite existing result: {output}")
    from windows_resources import process_resources

    health, _ = bench.call_json(HEALTH, None, 10)
    check_health(health)
    resources = process_resources(pid)
    check_limits(resources, manifest)
    result = {
        "started_utc": datetime.now(timezone.utc).isoformat(),
        "manifest_sha256": hashlib.sha256(manifest_raw).hexdigest(),
        "provenance": {
            "baseline_sha256": hashlib.sha256(baseline_raw).hexdigest(),
            "replay_script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        },
        "sequence_sha256": manifest["sequence_sha256"],
        "health_before": health, "resource_before": resources,
        "samples": [], "status": "running", "stop_reason": None,
    }
    with output.open("x", encoding="utf-8") as target:
        target.write(json.dumps(result, indent=2, ensure_ascii=False))
    number, index = 0, -1
    try:
        warmup, warmup_ms = bench.call_json(PREDICT, bench.request_body(
            "Classify a source file", "__warmup__.kt", "class Warmup"), 20)
        require(warmup.get("cache_hit") is False, "The warmup unexpectedly used the response cache")
        result["warmup"] = {"cache_hit": False, "roundtrip_ms": warmup_ms}
        checkpoint(output, result)
        started = time.perf_counter()
        for number, index in enumerate(order(), 1):
            prior = process_resources(pid)
            check_limits(prior, manifest)
            response, roundtrip = bench.call_json(PREDICT, bodies[index], 20)
            offset = (number - 1) % 160
            delta = validate_response(response, entries[index], offset >= 150)
            after = process_resources(pid)
            result["samples"].append({
                "number": number, "cycle": (number - 1) // 160 + 1, "request_index": index,
                "request_sha256": entries[index]["request_sha256"],
                "cache_hit": response["cache_hit"], "score_difference": delta,
                "roundtrip_ms": round(roundtrip, 1), "server_ms": response["latency_ms"],
                "input_tokens": response["usage"]["input_tokens"],
                "cached_input_tokens": response["usage"].get("cached_input_tokens"),
                "resources": after,
            })
            checkpoint(output, result)
            check_limits(after, manifest)
            if number % 160 == 0:
                print(f"cycle {number // 160}/{CYCLES}, {number}/2240 calls", flush=True)
        result["status"] = "complete"
        result["finished_utc"] = datetime.now(timezone.utc).isoformat()
        result["wall_seconds"] = round(time.perf_counter() - started, 1)
        result["health_after"] = bench.call_json(HEALTH, None, 10)[0]
        result["resource_after"] = process_resources(pid)
        checkpoint(output, result)
    except (Exception, KeyboardInterrupt) as error:
        result["status"] = "interrupted" if isinstance(error, KeyboardInterrupt) else "stopped"
        result["stop_reason"] = {
            "number": number, "cycle": (number - 1) // 160 + 1 if number else 0,
            "request_index": index, "error": (str(error) or type(error).__name__)[:500],
        }
        checkpoint(output, result)
        raise


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", required=True, type=Path, help="saved baseline .json or .json.gz")
    parser.add_argument("--manifest", required=True, type=Path, help="frozen soak manifest .json or .json.gz")
    parser.add_argument("--koog", required=True, type=Path, help="bare Koog repository with saved parent commits")
    parser.add_argument("--verify-only", action="store_true", help="rebuild and hash every request without network or model")
    parser.add_argument("--output", type=Path, help="new live replay result JSON; refused if it already exists")
    parser.add_argument("--pid", type=int, help="PID of the sole local Laya server for live replay")
    args = parser.parse_args()
    try:
        baseline_raw, baseline = read_json(args.baseline)
        manifest_raw, manifest = read_json(args.manifest)
        entries, bodies = verify(baseline, manifest, args.koog)
        if args.verify_only:
            print(json.dumps({
                "distinct_requests": len(entries), "total_requests": len(order()),
                "sequence_sha256": sequence_digest(entries),
                "baseline_sha256": hashlib.sha256(baseline_raw).hexdigest(),
                "manifest_sha256": hashlib.sha256(manifest_raw).hexdigest(),
                "checkpoint": CHECKPOINT, "cache_capacity": CAPACITY,
            }, indent=2))
        else:
            require(args.output is not None and args.pid is not None,
                    "Live replay requires --output and --pid; use --verify-only for offline validation")
            require(args.output.name.endswith(".json"), "Live replay output must be a .json file")
            run(args.pid, args.output, baseline_raw, manifest_raw, manifest, entries, bodies)
    except (OSError, EOFError, ValueError, KeyError, TypeError, RuntimeError, UnicodeError, zlib.error) as error:
        parser.exit(1, f"soak_replay: {error}\n")


if __name__ == "__main__":
    main()
