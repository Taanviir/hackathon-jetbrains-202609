"""Offline HTTP contract tests for tools/laya_server.py (no Laya import or weights)."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import redirect_stderr, redirect_stdout
import http.client
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import laya_server


QUESTION = {"relevant": {"type": "noul", "instructions": "Is this file relevant?"}}
PAYLOAD = {"model": "english", "state": "private source text", "questions": QUESTION}


class FakeModel:
    version = "fake-laya"
    torch_version = "fake-torch"
    device = "cpu"
    checkpoint = "fake-checkpoint"

    def __init__(self, delay: float = 0) -> None:
        self.delay = delay
        self.calls = []
        self.active = 0
        self.max_active = 0
        self.thread_ids = []
        self.lock = threading.Lock()

    def predict(self, state: str | dict, questions: dict) -> dict:
        with self.lock:
            self.active += 1
            self.max_active = max(self.max_active, self.active)
            self.calls.append((state, questions))
            self.thread_ids.append(threading.get_ident())
        try:
            time.sleep(self.delay)
            return {
                "model": "laya-rl-agent",
                "answers": {qid: {"type": "noul", "noul": 0.75, "confidence": 0.75} for qid in questions},
                "usage": {"input_tokens": 23, "output_tokens": 0},
                "routing": {"model": "english"},
            }
        finally:
            with self.lock:
                self.active -= 1


class LayaServerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.model = FakeModel()
        self.server = laya_server.create_server(self.model, port=0)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(self, method: str, path: str, payload: object = None, headers: dict | None = None):
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        options = dict(headers or {})
        if body is not None and "Content-Type" not in options:
            options["Content-Type"] = "application/json"
        connection = http.client.HTTPConnection(laya_server.HOST, self.port, timeout=3)
        try:
            connection.request(method, path, body=body, headers=options)
            response = connection.getresponse()
            return response.status, json.loads(response.read().decode("utf-8")), dict(response.getheaders())
        finally:
            connection.close()

    def test_health_and_one_file_prediction_contract(self) -> None:
        status, health, _ = self.request("GET", "/api/health")
        self.assertEqual(200, status)
        self.assertEqual({"english": "ready"}, health["models"])
        self.assertEqual("fake-laya", health["version"])
        self.assertEqual("fake-torch", health["torch"])
        self.assertEqual("cpu", health["device"])
        self.assertEqual("fake-checkpoint", health["checkpoint"])

        output = io.StringIO()
        with redirect_stdout(output), redirect_stderr(output):
            status, result, headers = self.request("POST", "/api/predict", PAYLOAD)
        self.assertEqual(200, status)
        self.assertEqual((PAYLOAD["state"], QUESTION), self.model.calls[0])
        self.assertEqual(0.75, result["answers"]["relevant"]["noul"])
        self.assertEqual(23, result["usage"]["input_tokens"])
        self.assertEqual("english", result["routing"]["model"])
        self.assertIn("latency_ms", result)
        self.assertEqual("cpu", result["device"])
        self.assertEqual("no-store", headers["Cache-Control"])
        self.assertNotIn("private source text", output.getvalue())

    def test_valid_typed_questions_and_state_object(self) -> None:
        payload = {
            "state": {"message": "refund request"},
            "questions": {
                "yes": QUESTION["relevant"],
                "department": {"type": "choice", "instructions": "Where?", "criteria": {"billing": "money", "technical": "bugs"}},
                "priority": {"type": "score", "instructions": "How urgent?", "criteria": ["low", "high"]},
            },
        }
        status, _, _ = self.request("POST", "/api/predict", payload)
        self.assertEqual(200, status)
        self.assertEqual(payload["state"], self.model.calls[0][0])

    def test_bad_requests_are_rejected_before_inference(self) -> None:
        cases = [
            [],
            {"state": "", "questions": QUESTION},
            {"state": {}, "questions": QUESTION},
            {"state": "source", "questions": {}},
            {"state": "source", "questions": {"x": {"type": "noul", "instructions": " "}}},
            {"state": "source", "questions": {"x": {"type": "choice", "instructions": "Pick", "criteria": ["only"]}}},
            {"state": "source", "questions": {"x": {"type": "score", "instructions": "Rate", "criteria": ["only"]}}},
            {"state": {"value": float("nan")}, "questions": QUESTION},
            {**PAYLOAD, "model": "multilingual"},
            {**PAYLOAD, "lang": "pl"},
            {**PAYLOAD, "extra": "unsupported"},
        ]
        for payload in cases:
            with self.subTest(payload=payload):
                status, body, _ = self.request("POST", "/api/predict", payload)
                self.assertEqual(400, status)
                self.assertEqual({"error": "invalid prediction request"}, body)
        self.assertEqual([], self.model.calls)

    def test_host_origin_and_preflight_block_browser_drive_by(self) -> None:
        status, _, _ = self.request("POST", "/api/predict", PAYLOAD, {"Host": "evil.example"})
        self.assertEqual(403, status)
        status, _, _ = self.request("POST", "/api/predict", PAYLOAD, {"Origin": "https://evil.example"})
        self.assertEqual(403, status)
        status, _, _ = self.request("POST", "/api/predict", PAYLOAD, {"Origin": "null"})
        self.assertEqual(403, status)
        status, _, headers = self.request("OPTIONS", "/api/predict", headers={"Origin": f"http://127.0.0.1:{self.port}"})
        self.assertEqual(405, status)
        self.assertNotIn("Access-Control-Allow-Origin", headers)
        self.assertEqual([], self.model.calls)
        status, _, _ = self.request("POST", "/api/predict", PAYLOAD, {"Origin": f"http://localhost:{self.port}"})
        self.assertEqual(200, status)

    def test_body_limits_content_type_and_routes(self) -> None:
        status, _, _ = self.request("POST", "/api/predict", PAYLOAD, {"Content-Type": "text/plain"})
        self.assertEqual(415, status)
        status, _, _ = self.request("POST", "/api/predict")
        self.assertEqual(415, status)  # no JSON content type
        status, _, _ = self.request("POST", "/api/predict", PAYLOAD, {"Content-Length": str(laya_server.MAX_BODY + 1)})
        self.assertEqual(413, status)
        self.assertEqual(404, self.request("GET", "/private")[0])
        self.assertEqual(404, self.request("POST", "/private", PAYLOAD)[0])
        self.assertEqual(405, self.request("GET", "/api/predict")[0])
        self.assertEqual(405, self.request("POST", "/api/health", PAYLOAD)[0])
        self.assertEqual([], self.model.calls)

    def test_prediction_failures_are_bounded_and_do_not_echo_sources(self) -> None:
        class FailingModel(FakeModel):
            def predict(self, state, questions):
                raise RuntimeError("private source text: internal failure")

        self.server.model = FailingModel()
        output = io.StringIO()
        with redirect_stdout(output), redirect_stderr(output):
            status, body, _ = self.request("POST", "/api/predict", PAYLOAD)
        self.assertEqual(500, status)
        self.assertEqual({"error": "prediction failed"}, body)
        self.assertNotIn("private source text", output.getvalue())

    def test_inference_is_serialized_across_http_requests(self) -> None:
        self.model.delay = 0.03
        with ThreadPoolExecutor(max_workers=5) as pool:
            statuses = list(pool.map(lambda _: self.request("POST", "/api/predict", PAYLOAD)[0], range(5)))
        self.assertEqual([200] * 5, statuses)
        self.assertEqual(1, self.model.max_active)
        self.assertEqual(1, len(set(self.model.thread_ids)), "all inference must reuse one persistent worker thread")


class CliTest(unittest.TestCase):
    def test_cli_sets_cache_and_offline_mode_before_loading(self) -> None:
        class NoopServer:
            server_address = (laya_server.HOST, 8770)

            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return None

            def serve_forever(self):
                return None

        for download, expected in ((False, "1"), (True, "0")):
            with self.subTest(download=download), tempfile.TemporaryDirectory() as temporary:
                cache = Path(temporary) / "laya-cache"

                def check_environment(revision):
                    self.assertEqual(str(cache.resolve()), os.environ["HF_HOME"])
                    self.assertEqual(expected, os.environ["HF_HUB_OFFLINE"])
                    self.assertEqual(laya_server.DEFAULT_REVISION, revision)
                    return FakeModel()

                with mock.patch.dict(os.environ), mock.patch.object(laya_server, "EnglishModel", side_effect=check_environment), mock.patch.object(laya_server, "create_server", return_value=NoopServer()):
                    flags = ["--cache", str(cache), "--port", "8770"] + (["--download"] if download else [])
                    self.assertEqual(0, laya_server.main(flags))
                    self.assertTrue(cache.is_dir())


if __name__ == "__main__":
    unittest.main()
