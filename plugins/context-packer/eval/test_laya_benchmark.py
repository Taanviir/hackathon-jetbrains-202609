"""Offline checks that the benchmark mirrors the plugin's retrieval inputs."""

import json
from pathlib import Path
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


if __name__ == "__main__":
    unittest.main()
