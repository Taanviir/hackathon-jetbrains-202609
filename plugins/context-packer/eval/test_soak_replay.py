"""Offline contract checks for the frozen Laya stability replay."""

from __future__ import annotations

from copy import deepcopy
import hashlib
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import soak_replay as replay


class SoakReplayTest(unittest.TestCase):
    def setUp(self) -> None:
        self.entries = [
            {"task_sha": "task", "path": f"source/{i}.kt", "request_sha256": f"hash-{i}",
             "expected_score": 0.5}
            for i in range(150)
        ]
        self.manifest = {
            "koog_head": "head", "checkpoint": replay.CHECKPOINT,
            "cache_capacity": replay.CAPACITY,
            "request_counts_by_dev_task": list(replay.COUNTS),
            "distinct_requests": 150, "cycles": replay.CYCLES,
            "repeat_last_per_cycle": replay.HOT_LAST,
            "total_requests": 2240,
            "stop_private_bytes": replay.PRIVATE_LIMIT,
            "stop_c_free_bytes": replay.C_FREE_FLOOR,
            "requests": self.entries,
            "sequence_sha256": replay.sequence_digest(self.entries),
        }

    def test_changed_manifest_or_request_hash_is_rejected(self) -> None:
        baseline = {"koog_head": "head"}
        with patch.object(replay, "build_inputs", return_value=(self.entries, [{}] * 150)):
            altered_capacity = deepcopy(self.manifest)
            altered_capacity["cache_capacity"] = 0
            with self.assertRaisesRegex(ValueError, "cache_capacity"):
                replay.verify(baseline, altered_capacity, Path("unused"))

            altered_request = deepcopy(self.manifest)
            altered_request["requests"][0]["request_sha256"] = "tampered"
            with self.assertRaisesRegex(ValueError, "request paths, scores, or body hashes"):
                replay.verify(baseline, altered_request, Path("unused"))

    def test_invalid_score_or_cache_token_accounting_is_rejected(self) -> None:
        valid = {
            "answers": {replay.bench.QUESTION_ID: {"noul": 0.5}},
            "cache_hit": False, "usage": {"input_tokens": 10, "cached_input_tokens": 0},
            "latency_ms": 1.0,
        }
        entry = {"expected_score": 0.5}
        self.assertEqual(0.0, replay.validate_response(valid, entry, False))
        cases = [
            ("NaN score", {"answers": {replay.bench.QUESTION_ID: {"noul": float("nan")}}}, False),
            ("out-of-range score", {"answers": {replay.bench.QUESTION_ID: {"noul": 1.1}}}, False),
            ("miss claims cached tokens", {"usage": {"input_tokens": 10, "cached_input_tokens": 4}}, False),
            ("hit claims inference tokens", {"cache_hit": True,
                                             "usage": {"input_tokens": 10, "cached_input_tokens": 4}}, True),
        ]
        for label, changes, should_hit in cases:
            with self.subTest(label=label):
                response = deepcopy(valid)
                response.update(changes)
                with self.assertRaises(ValueError):
                    replay.validate_response(response, entry, should_hit)

    def test_existing_output_is_refused_before_health_request(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "existing.json"
            output.write_text("saved run", encoding="utf-8")
            with patch.object(replay, "sys", SimpleNamespace(platform="win32")), \
                    patch.object(replay.bench, "call_json") as call_json:
                with self.assertRaisesRegex(ValueError, "Refusing to overwrite"):
                    replay.run(123, output, b"baseline", b"manifest", self.manifest,
                               self.entries, [{}])
                call_json.assert_not_called()
            self.assertEqual("saved run", output.read_text(encoding="utf-8"))

    def test_request_failure_and_interrupt_checkpoint_the_run(self) -> None:
        health = {
            "checkpoint": replay.CHECKPOINT, "models": {"english": "ready"},
            "response_cache": {"capacity": replay.CAPACITY, "entries": 0, "hits": 0},
        }
        resources = {"private_bytes": 1, "c_free_bytes": replay.C_FREE_FLOOR + 1,
                     "rss_bytes": 1, "threads": 1}
        for failure, expected_status in [(OSError("request failed"), "stopped"),
                                         (KeyboardInterrupt(), "interrupted")]:
            with self.subTest(expected_status=expected_status), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / "result.json"
                with patch.object(replay, "sys", SimpleNamespace(platform="win32")), \
                        patch.object(replay, "order", return_value=[0]), \
                        patch("windows_resources.process_resources", return_value=resources), \
                        patch.object(replay.bench, "call_json", side_effect=[
                            (health, 1.0), ({"cache_hit": False}, 2.0), failure,
                        ]) as call_json:
                    with self.assertRaises(type(failure)):
                        replay.run(123, output, b"baseline", b"manifest", self.manifest,
                                   self.entries, [{}])
                    self.assertEqual(3, call_json.call_count)
                saved = json.loads(output.read_text(encoding="utf-8"))
                self.assertEqual(expected_status, saved["status"])
                self.assertEqual(1, saved["stop_reason"]["number"])
                self.assertEqual(0, saved["stop_reason"]["request_index"])
                self.assertEqual([], saved["samples"])
                self.assertEqual(hashlib.sha256(b"baseline").hexdigest(),
                                 saved["provenance"]["baseline_sha256"])
                self.assertEqual(hashlib.sha256(Path(replay.__file__).read_bytes()).hexdigest(),
                                 saved["provenance"]["replay_script_sha256"])

    def test_completed_run_preserves_observations_and_health(self) -> None:
        health = {
            "checkpoint": replay.CHECKPOINT, "models": {"english": "ready"},
            "response_cache": {"capacity": replay.CAPACITY, "entries": 0, "hits": 0},
        }
        final_health = deepcopy(health)
        final_health["response_cache"]["entries"] = 2
        resources = {"private_bytes": 1, "c_free_bytes": replay.C_FREE_FLOOR + 1,
                     "rss_bytes": 1, "threads": 1}
        response = {
            "answers": {replay.bench.QUESTION_ID: {"noul": 0.5}}, "cache_hit": False,
            "usage": {"input_tokens": 10, "cached_input_tokens": 0}, "latency_ms": 1.0,
        }
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "result.json"
            with patch.object(replay, "sys", SimpleNamespace(platform="win32")), \
                    patch.object(replay, "order", return_value=[0]), \
                    patch("windows_resources.process_resources", return_value=resources), \
                    patch.object(replay.bench, "call_json", side_effect=[
                        (health, 1.0), ({"cache_hit": False}, 2.0), (response, 3.5), (final_health, 1.0),
                    ]):
                replay.run(123, output, b"baseline", b"manifest", self.manifest, self.entries, [{}])
            saved = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("complete", saved["status"])
            self.assertIsNone(saved["stop_reason"])
            self.assertEqual(final_health, saved["health_after"])
            self.assertEqual(1, len(saved["samples"]))
            self.assertEqual(10, saved["samples"][0]["input_tokens"])
            self.assertEqual(3.5, saved["samples"][0]["roundtrip_ms"])
            self.assertEqual(0.0, saved["samples"][0]["score_difference"])


if __name__ == "__main__":
    unittest.main()
