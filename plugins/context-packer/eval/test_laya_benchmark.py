"""Offline checks that the benchmark mirrors the plugin's retrieval inputs."""

import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import laya_benchmark as bench


class LayaBenchmarkTest(unittest.TestCase):
    def test_bm25_tokens_and_order_match_kotlin_examples(self):
        self.assertEqual(
            ["cached", "content", "token", "count"],
            bench.tokens("cachedContentTokenCount"),
        )
        self.assertEqual(
            ["http", "client", "retry", "after", "ms"],
            bench.tokens("HTTPClient_retry-after-ms"),
        )
        docs = {
            "src/Colors.kt": "object Colors { val red = 1 }",
            "src/RetryPolicy.kt": "class RetryPolicy { fun nextDelay(attempt: Int) = backoff(attempt) }",
            "src/Http.kt": "class Http { fun send() = retry { } }",
        }
        self.assertEqual(
            "src/RetryPolicy.kt",
            bench.bm25_rank("add exponential backoff to retry policy", docs)[0],
        )
        self.assertEqual([], bench.bm25_rank("anything", {}))
        self.assertEqual(
            ["a.kt", "z.kt"],
            bench.bm25_rank("no matching terms", {"z.kt": "same", "a.kt": "same"}),
        )

    def test_regex_sketch_matches_kotlin_parity_fixture(self):
        fixture = (
            bench.ROOT / "src" / "test" / "resources" / "fixtures" / "regex_parity.json"
        )
        cases = json.loads(fixture.read_text(encoding="utf-8"))["cases"]
        self.assertGreater(len(cases), 10)
        for case in cases:
            with self.subTest(path=case["path"]):
                self.assertEqual(
                    case["sketch"],
                    bench.regex_sketch(case["path"], case["text"]),
                )

    def test_request_is_one_capped_file_and_one_noul_question(self):
        body = bench.request_body("task " * 200, "x" * 300, "y" * 2000)
        self.assertEqual("english", body["model"])
        self.assertEqual(240, len(body["state"].split("\n")[0]) - len("File: "))
        self.assertEqual(1000, len(body["state"].split("\n")[1]))
        self.assertEqual(["relevant"], list(body["questions"]))
        self.assertEqual("noul", body["questions"]["relevant"]["type"])
        self.assertEqual(
            bench.QUESTION_PREFIX + ("task " * 200)[:500],
            body["questions"]["relevant"]["instructions"],
        )

    def test_fixed_fusion_uses_zero_based_bm25_position(self):
        row = {
            "bm25": ["a", "b", "c"],
            "local_bm25": ["a", "b", "c"],
            "pool": ["a", "b", "c"],
            "s1": {"a": .1, "b": .4, "c": .8},
            "s2": {"a": .1, "b": .2, "c": 1.0},
        }
        ranked = bench.rankings(row)
        self.assertEqual(["c", "b", "a"], ranked["laya_only"])
        self.assertEqual("c", ranked["blend_w1.0"][0])
        self.assertEqual("a", ranked["bm25"][0])

    def test_task_window_reaches_code_beyond_import_prefix(self):
        source = ("import unrelated.package.Thing\n" * 80) + (
            "fun mapCachedContentTokenCount(usage: GoogleUsage) = usage.cachedContentTokenCount\n"
        ) + ("fun other() = 1\n" * 40)
        excerpt, offset = bench.task_window(
            "Map cachedContentTokenCount from Google usage metadata", source
        )
        self.assertGreater(offset, 1000)
        self.assertIn("mapCachedContentTokenCount", excerpt)
        self.assertLessEqual(len(excerpt), 1000)

    def test_failed_model_call_aborts_before_an_invalid_ranking_is_saved(self):
        task = bench.Task("a" * 40, "b" * 40, "Fix retry handling in client", ["a.kt"])
        failed = {"score": 0.0, "input_tokens": 0, "roundtrip_ms": 2.0,
                  "server_ms": None, "model": None, "error": "connection refused"}
        with patch.object(bench, "files_at", return_value={"a.kt": "class RetryClient"}), \
                patch.object(bench, "score_one", return_value=failed) as score:
            with self.assertRaises(bench.BenchmarkCallError) as raised:
                bench.measure_task(task, Path("."), "http://127.0.0.1:8770/api/predict",
                                   1, 1, 1, {})
        self.assertEqual(1, score.call_count)
        self.assertEqual("pass1", raised.exception.phase)
        self.assertEqual(1, raised.exception.request_count)
        self.assertEqual(1, raised.exception.error_count)
        self.assertEqual({}, raised.exception.second)

    def test_frozen_extension_manifest_checks_parent_and_truth(self):
        tasks = [
            bench.Task("a", "pa", "First task", ["A.kt"]),
            bench.Task("b", "pb", "Second task", ["B.kt"]),
            bench.Task("c", "pc", "Third task", ["C.kt"]),
        ]
        protocol = {"dev_tasks": 1, "heldout_tasks": 2, "prefilter": 60}
        manifest = {
            "source_koog_head": "head", "protocol": {**protocol, "heldout_tasks": 1},
            "development_shas": ["a"], "initial_heldout_shas": ["b"],
            "extension_heldout_tasks": [{"sha": "c", "parent": "pc", "task": "Third task", "truth": ["C.kt"]}],
            "skipped_before_extension": [],
        }
        bench.validate_extension_manifest(manifest, tasks, [], protocol, "head")
        changed = dict(manifest, extension_heldout_tasks=[dict(manifest["extension_heldout_tasks"][0], parent="wrong")])
        with self.assertRaisesRegex(RuntimeError, "parents"):
            bench.validate_extension_manifest(changed, tasks, [], protocol, "head")

    def test_expected_checkpoint_rejects_missing_or_mismatched_server(self):
        expected = "1c5edc17a7acd8701df6fc341c0d179f1c62c982"
        bench.require_checkpoint({"checkpoint": expected}, expected)
        with self.assertRaisesRegex(RuntimeError, "server reports none"):
            bench.require_checkpoint({}, expected)
        with self.assertRaisesRegex(RuntimeError, "server reports"):
            bench.require_checkpoint({"checkpoint": "0" * 40}, expected)

    def test_uncached_protocol_rejects_enabled_or_unknown_cache(self):
        bench.require_uncached({})  # The original playground exposes no cache field.
        bench.require_uncached({"response_cache": {"capacity": 0}})
        for cache in ({"capacity": 128}, {"capacity": "0"}, {"capacity": True}, {}):
            with self.subTest(cache=cache), self.assertRaisesRegex(RuntimeError, "Disable.*cache"):
                bench.require_uncached({"response_cache": cache})

    def test_predict_rejects_cache_hits_and_non_numeric_probabilities(self):
        response = {"answers": {bench.QUESTION_ID: {"noul": 0.5}}, "usage": {"input_tokens": 12},
                    "latency_ms": 1.0, "model": "english"}
        for value in ("0.5", True, None, float("nan"), -0.1, 1.1):
            invalid = {**response, "answers": {bench.QUESTION_ID: {"noul": value}}}
            with self.subTest(value=value), patch.object(bench, "call_json", return_value=(invalid, 1.0)):
                self.assertIn("Invalid probability", bench.score_one("http://localhost:8770/api/predict", 1, "task", "a.kt", "source")["error"])
        with patch.object(bench, "call_json", return_value=({**response, "cache_hit": True}, 1.0)):
            self.assertIn("Cached prediction", bench.score_one("http://localhost:8770/api/predict", 1, "task", "a.kt", "source")["error"])
        with patch.object(bench, "call_json", return_value=(response, 1.0)):
            self.assertIsNone(bench.score_one("http://localhost:8770/api/predict", 1, "task", "a.kt", "source")["error"])

    def test_invalid_usage_and_latency_cannot_enter_benchmark_totals(self):
        response = {"answers": {bench.QUESTION_ID: {"noul": 0.5}}, "usage": {"input_tokens": 12},
                    "latency_ms": 1.0, "model": "english"}
        bad = [{**response, "usage": {"input_tokens": n}} for n in (-1, True, "12")]
        bad += [{**response, "latency_ms": n} for n in (-1, True, "1", float("inf"))]
        for invalid in bad:
            with self.subTest(response=invalid), patch.object(bench, "call_json", return_value=(invalid, 1.0)):
                self.assertIsNotNone(bench.score_one("http://localhost:8770/api/predict", 1, "task", "a.kt", "source")["error"])

    def test_fresh_then_resume_retains_checkpoint_requirement(self):
        expected = "1c5edc17a7acd8701df6fc341c0d179f1c62c982"
        tasks = [bench.Task("a", "pa", "First task", ["A.kt"]),
                 bench.Task("b", "pb", "Second task", ["A.kt"])]
        health = {"models": {"english": "ready"}, "checkpoint": expected}

        def fake_git(_repo, *args):
            return b"head\n" if args[0] == "rev-parse" else b"local\n"

        def fake_row(task, *_args):
            return {"sha": task.sha, "protocol_revision": 2,
                    "recall": {"bm25": {"10": 1.0}, "blend_w1.0": {"10": 1.0}},
                    "request_count": 1, "total_ms": 1.0, "error_count": 0}

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "result.json"
            args = ["laya_benchmark.py", "--repo", directory, "--dev", "1",
                    "--heldout", "1", "--expected-checkpoint", expected,
                    "--output", str(output), "--markdown", str(Path(directory) / "report.md")]
            with patch.object(bench, "git", side_effect=fake_git), \
                    patch.object(bench, "load_tasks", return_value=(tasks, [])), \
                    patch.object(bench, "call_json", return_value=(health, 1.0)), \
                    patch.object(bench, "score_one", return_value={"error": None}) as warmup, \
                    patch.object(bench, "measure_task", side_effect=fake_row) as measure, \
                    patch.object(bench, "rankings", return_value={"bm25": ["A.kt"]}), \
                    patch.object(bench, "add_latency_stats"):
                with patch.object(sys, "argv", args + ["--stop-after", "1"]):
                    bench.main()
                self.assertEqual(expected, json.loads(output.read_text())["expected_checkpoint"])
                with patch.object(sys, "argv", [a for a in args if a not in ("--expected-checkpoint", expected)]
                                  + ["--stop-after", "2", "--resume"]):
                    with self.assertRaisesRegex(RuntimeError, "requires its exact"):
                        bench.main()
                with patch.object(sys, "argv", args + ["--stop-after", "2", "--resume"]):
                    bench.main()
                self.assertEqual(1, warmup.call_count)
                self.assertEqual(2, measure.call_count)
                self.assertEqual(expected, json.loads(output.read_text())["expected_checkpoint"])


if __name__ == "__main__":
    unittest.main()
