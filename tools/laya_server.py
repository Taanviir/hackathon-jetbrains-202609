"""Small loopback API for Context Packer's local English Laya provider.

Run with an existing cache:
    python tools/laya_server.py --cache work/laya/hf-cache

Pass --download only when fetching the English checkpoint is intended. Importing this
module does not import Laya, load weights, or access the network.
"""

from __future__ import annotations

import argparse
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
import re
from pathlib import Path
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


HOST = "127.0.0.1"
DEFAULT_PORT = 8770
DEFAULT_REVISION = "1c5edc17a7acd8701df6fc341c0d179f1c62c982"
MAX_BODY = 1 << 20
MAX_QUESTIONS = 32
MAX_RESPONSE_CACHE = 1024
MAX_CACHED_RESULT_BYTES = 64 << 10
DEFAULT_CACHE = Path(__file__).resolve().parents[1] / "plugins" / "context-packer" / ".cache" / "laya"


def _reject_non_json_number(_value: str) -> None:
    raise ValueError("non-JSON number")


def validate_payload(payload: object) -> tuple[str | dict, dict]:
    """Accept Laya's typed question shape while serving only its English checkpoint."""
    if not isinstance(payload, dict):
        raise ValueError("request must be a JSON object")
    if set(payload) - {"state", "questions", "model", "lang"}:
        raise ValueError("unsupported request field")
    if payload.get("model") not in (None, "english"):
        raise ValueError("only model=english is available")
    if payload.get("lang") not in (None, "en", "eng", "english"):
        raise ValueError("only English language is available")

    state = payload.get("state")
    if not ((isinstance(state, str) and state.strip()) or (isinstance(state, dict) and state)):
        raise ValueError("state must be a non-empty string or object")
    questions = payload.get("questions")
    if not isinstance(questions, dict) or not 1 <= len(questions) <= MAX_QUESTIONS:
        raise ValueError("questions must contain 1 to 32 definitions")
    for qid, definition in questions.items():
        if not isinstance(qid, str) or not qid.strip() or len(qid) > 128:
            raise ValueError("question ids must be non-empty strings of at most 128 characters")
        if not isinstance(definition, dict):
            raise ValueError("question definition must be an object")
        kind = definition.get("type")
        if kind not in ("noul", "choice", "score"):
            raise ValueError("question type must be noul, choice, or score")
        instructions = definition.get("instructions")
        if not isinstance(instructions, str) or not instructions.strip() or len(instructions) > 2_048:
            raise ValueError("question instructions must be non-empty text of at most 2048 characters")
        if set(definition) - {"type", "instructions", "criteria"}:
            raise ValueError("unsupported question field")
        criteria = definition.get("criteria")
        if kind == "noul":
            if criteria is not None and (not isinstance(criteria, dict) or set(criteria) - {"false", "true"}):
                raise ValueError("noul criteria must define false and/or true")
        elif kind == "choice":
            if isinstance(criteria, dict):
                labels = list(criteria)
            elif isinstance(criteria, list):
                labels = criteria
            else:
                raise ValueError("choice criteria must be an object or list")
            if not 2 <= len(labels) <= 20 or any(not isinstance(label, str) or not label.strip() for label in labels):
                raise ValueError("choice needs 2 to 20 named options")
            if len(set(labels)) != len(labels):
                raise ValueError("choice options must be unique")
        elif not isinstance(criteria, list) or not 2 <= len(criteria) <= 20:
            raise ValueError("score needs 2 to 20 ordered levels")
    return state, questions


class EnglishModel:
    """The real adapter; constructed only after CLI cache and offline settings are applied."""

    def __init__(self, revision: str = DEFAULT_REVISION) -> None:
        import laya
        import torch
        from huggingface_hub import snapshot_download
        from laya import Router

        snapshot = snapshot_download(
            "convaiinnovations/laya", revision=revision,
            allow_patterns=["rl_agent_config.json", "model.safetensors", "tokenizer/*", "encoder/*"],
            local_files_only=os.environ.get("HF_HUB_OFFLINE") == "1",
        )
        self.router = Router(models={"english": snapshot}, max_loaded=1, default="english")
        agent = self.router.load("english")
        self.checkpoint = revision
        self.version = laya.__version__
        self.torch_version = torch.__version__
        self.device = str(agent.device)

    def predict(self, state: str | dict, questions: dict) -> dict:
        return self.router.predict(state, questions, model="english")


class LocalServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, model: object, port: int = DEFAULT_PORT, response_cache: int = 0) -> None:
        if not 0 <= response_cache <= MAX_RESPONSE_CACHE:
            raise ValueError(f"response_cache must be between 0 and {MAX_RESPONSE_CACHE}")
        super().__init__((HOST, port), Handler)
        self.model = model
        self.inference_lock = threading.Lock()
        self.cache_lock = threading.Lock()
        self.cache_capacity = response_cache
        self.cache_hits = 0
        # Keys contain only SHA-256 digests. Values are JSON snapshots, never mutable model objects.
        self.response_cache_entries: OrderedDict[str, str] = OrderedDict()
        # HTTP connections get separate threads. Keep all PyTorch inference on one persistent
        # worker to avoid repeatedly constructing per-thread CPU runtime resources.
        self.inference_worker = ThreadPoolExecutor(max_workers=1, thread_name_prefix="laya-inference")

    def predict(self, state: str | dict, questions: dict) -> dict:
        return self.inference_worker.submit(self.model.predict, state, questions).result()

    def prediction_response(self, state: str | dict, questions: dict) -> dict:
        with self.inference_lock:
            started = time.perf_counter()
            key = None
            if self.cache_capacity:
                # The effective model is always English. Compact JSON preserves state/question
                # object order, unlike sort_keys=True; only the digest stays in memory as a key.
                effective = json.dumps(["english", state, questions], ensure_ascii=False, separators=(",", ":"))
                key = hashlib.sha256(effective.encode("utf-8")).hexdigest()
                with self.cache_lock:
                    snapshot = self.response_cache_entries.get(key)
                    if snapshot is not None:
                        self.response_cache_entries.move_to_end(key)
                        self.cache_hits += 1
                if snapshot is not None:
                    result = json.loads(snapshot)
                    usage = result.get("usage")
                    tokens = usage.get("input_tokens") if isinstance(usage, dict) else None
                    result["usage"] = {
                        "input_tokens": 0,
                        "output_tokens": 0,
                        "cached_input_tokens": tokens if type(tokens) is int and tokens >= 0 else None,
                    }
                    result["cache_hit"] = True
                    result.setdefault("routing", {"model": "english"})
                    result["latency_ms"] = round((time.perf_counter() - started) * 1_000, 1)
                    result["device"] = self.model.device
                    return result

            result = dict(self.predict(state, questions))
            elapsed_ms = round((time.perf_counter() - started) * 1_000, 1)
            if self.cache_capacity:
                # Validate and detach before caching. Non-finite or non-JSON model output is a
                # request failure, and never poisons a later exact-match request.
                snapshot = json.dumps(result, ensure_ascii=False, allow_nan=False)
                result = json.loads(snapshot)
                answers = result.get("answers")
                cacheable = isinstance(answers, dict) and all(isinstance(answers.get(qid), dict) for qid in questions) and "error" not in result
                if cacheable and len(snapshot.encode("utf-8")) <= MAX_CACHED_RESULT_BYTES:
                    with self.cache_lock:
                        self.response_cache_entries[key] = snapshot
                        self.response_cache_entries.move_to_end(key)
                        if len(self.response_cache_entries) > self.cache_capacity:
                            self.response_cache_entries.popitem(last=False)
                result["cache_hit"] = False
                usage = result.get("usage")
                if not isinstance(usage, dict):
                    usage = {}
                    result["usage"] = usage
                usage["cached_input_tokens"] = 0
            result.setdefault("routing", {"model": "english"})
            result["latency_ms"] = elapsed_ms
            result["device"] = self.model.device
            return result

    def cache_status(self) -> dict:
        with self.cache_lock:
            return {
                "capacity": self.cache_capacity,
                "hits": self.cache_hits,
                "entries": len(self.response_cache_entries),
            }

    def server_close(self) -> None:
        super().server_close()
        self.inference_worker.shutdown(wait=True, cancel_futures=True)


class Handler(BaseHTTPRequestHandler):
    server_version = "context-packer-laya"

    def log_message(self, _format: str, *_args: object) -> None:
        # Paths, query strings, errors, and source bodies never go to stdout/stderr.
        pass

    def _send(self, status: int, body: dict) -> None:
        data = json.dumps(body, ensure_ascii=False, allow_nan=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        try:
            self.end_headers()
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            # A cancelled IDE request can disconnect while local inference finishes.
            pass

    def _allowed(self) -> bool:
        port = self.server.server_address[1]
        hosts = {f"127.0.0.1:{port}", f"localhost:{port}"}
        origins = {f"http://{host}" for host in hosts}
        host_headers = self.headers.get_all("Host", [])
        origin_headers = self.headers.get_all("Origin", [])
        if len(host_headers) != 1 or host_headers[0].lower() not in hosts:
            self._send(403, {"error": "forbidden host"})
            return False
        if len(origin_headers) > 1 or (origin_headers and origin_headers[0].lower() not in origins):
            self._send(403, {"error": "forbidden origin"})
            return False
        return True

    def do_GET(self) -> None:
        if not self._allowed():
            return
        if self.path == "/api/health":
            model = self.server.model
            body = {
                "models": {"english": "ready"},
                "version": model.version,
                "torch": model.torch_version,
                "device": model.device,
                "checkpoint": getattr(model, "checkpoint", None),
            }
            if self.server.cache_capacity:
                body["response_cache"] = self.server.cache_status()
            self._send(200, body)
        elif self.path == "/api/predict":
            self._send(405, {"error": "method not allowed"})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self) -> None:
        if not self._allowed():
            return
        if self.path == "/api/health":
            self._send(405, {"error": "method not allowed"})
            return
        if self.path != "/api/predict":
            self._send(404, {"error": "not found"})
            return
        if self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower() != "application/json":
            self._send(415, {"error": "Content-Type must be application/json"})
            return
        if self.headers.get("Transfer-Encoding"):
            self._send(400, {"error": "chunked requests are unsupported"})
            return
        lengths = self.headers.get_all("Content-Length", [])
        if not lengths:
            self._send(411, {"error": "Content-Length is required"})
            return
        if len(lengths) != 1:
            self._send(400, {"error": "invalid Content-Length"})
            return
        try:
            length = int(lengths[0])
        except ValueError:
            self._send(400, {"error": "invalid Content-Length"})
            return
        if length <= 0:
            self._send(400, {"error": "request body is empty"})
            return
        if length > MAX_BODY:
            self._send(413, {"error": "request body exceeds 1 MB"})
            return
        try:
            self.connection.settimeout(10)
            raw = self.rfile.read(length)
            if len(raw) != length:
                raise ValueError("incomplete request body")
            payload = json.loads(raw.decode("utf-8"), parse_constant=_reject_non_json_number)
            state, questions = validate_payload(payload)
        except TimeoutError:
            self._send(408, {"error": "request body timed out"})
            return
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
            self._send(400, {"error": "invalid prediction request"})
            return
        try:
            result = self.server.prediction_response(state, questions)
            self._send(200, result)
        except Exception:
            self._send(500, {"error": "prediction failed"})

    def do_OPTIONS(self) -> None:
        if self._allowed():
            self._send(405, {"error": "method not allowed"})


def create_server(model: object, port: int = DEFAULT_PORT, response_cache: int = 0) -> LocalServer:
    """Inject a fake model in tests without importing Laya or loading any weights."""
    return LocalServer(model, port, response_cache)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Serve the English Laya checkpoint on 127.0.0.1")
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE, help="Hugging Face cache directory")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--revision", default=DEFAULT_REVISION, help="40-character model checkpoint commit (pinned by default)")
    parser.add_argument("--download", action="store_true", help="allow fetching English weights into --cache")
    parser.add_argument("--response-cache", type=int, default=0, metavar="N", help="cache 0 to 1024 exact responses in memory (default: off)")
    args = parser.parse_args(argv)
    if not 1 <= args.port <= 65_535:
        parser.error("--port must be between 1 and 65535")
    if not re.fullmatch(r"[0-9a-fA-F]{40}", args.revision):
        parser.error("--revision must be a 40-character checkpoint commit, not a moving branch")
    if not 0 <= args.response_cache <= MAX_RESPONSE_CACHE:
        parser.error(f"--response-cache must be between 0 and {MAX_RESPONSE_CACHE}")
    cache = args.cache.expanduser().resolve()
    cache.mkdir(parents=True, exist_ok=True)
    os.environ["HF_HOME"] = str(cache)
    os.environ["HF_HUB_OFFLINE"] = "0" if args.download else "1"
    os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
    os.environ["HF_HUB_DISABLE_XET"] = "1"
    os.environ["USE_TF"] = "0"
    os.environ["TOKENIZERS_PARALLELISM"] = "false"

    print("Loading the English Laya checkpoint...", flush=True)
    try:
        model = EnglishModel(args.revision.lower())
    except Exception as error:
        print(f"Could not load English Laya ({type(error).__name__}). Check --cache or pass --download.", file=sys.stderr)
        return 1
    with create_server(model, args.port, args.response_cache) as server:
        print(f"English Laya ready at http://{HOST}:{server.server_address[1]}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
