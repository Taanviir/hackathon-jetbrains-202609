"""Offline tests for the fail-open Claude prompt hook; no IDE or model is needed."""

import asyncio
from contextlib import redirect_stdout
from io import StringIO
import json
import os
from pathlib import Path
import subprocess
import sys
import types
import unittest
from unittest import mock

import pack_hook


PAYLOAD = {"prompt": "Map cached tokens from Google usage metadata", "cwd": "/koog"}


def run_hook(environment, response="Picked 8 files"):
    output = StringIO()
    with mock.patch.dict(os.environ, environment, clear=True), \
            mock.patch.object(sys, "stdin", StringIO(json.dumps(PAYLOAD))), \
            mock.patch.object(pack_hook, "from_ide", new=mock.AsyncMock(return_value=response)) as ide, \
            mock.patch.object(pack_hook, "from_eval", new=mock.AsyncMock(return_value=response)) as evaluation, \
            redirect_stdout(output):
        pack_hook.main()
    return output.getvalue(), ide, evaluation


class HookSettingsTest(unittest.TestCase):
    def test_default_keyword_provider_and_bounded_limit(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            self.assertEqual("keywords", pack_hook.provider())
            self.assertEqual(8, pack_hook.limit())
            self.assertEqual(25, pack_hook.deadline())
        for value in ("0", "21", "invalid"):
            with self.subTest(limit=value), mock.patch.dict(
                os.environ, {"CONTEXT_PACKER_HOOK_LIMIT": value}, clear=True
            ):
                with self.assertRaises(ValueError):
                    pack_hook.limit()

    def test_malformed_deadline_fails_open_without_loading_mcp(self):
        script = Path(pack_hook.__file__)
        for value in ("invalid", "nan", "inf", "0", "-1"):
            with self.subTest(deadline=value):
                environment = dict(os.environ, CONTEXT_PACKER_HOOK_DEADLINE=value)
                completed = subprocess.run(
                    [sys.executable, str(script)], input=json.dumps(PAYLOAD), text=True,
                    capture_output=True, env=environment, timeout=5, check=False,
                )
                self.assertEqual(0, completed.returncode)
                self.assertEqual("", completed.stdout)
                self.assertEqual("", completed.stderr)

    def test_context_wording_does_not_claim_every_file_was_model_scored(self):
        stdout, ide, evaluation = run_hook({})
        self.assertEqual(1, ide.await_count)
        evaluation.assert_not_awaited()
        context = json.loads(stdout)["hookSpecificOutput"]["additionalContext"]
        self.assertIn("selected likely source files", context)
        self.assertNotIn("every file", context)
        self.assertIn("Picked 8 files", context)

    def test_eval_mode_still_uses_task_parent_path(self):
        stdout, ide, evaluation = run_hook({"TASK_PARENT": "abc123"})
        self.assertTrue(stdout)
        ide.assert_not_awaited()
        evaluation.assert_awaited_once_with(PAYLOAD["prompt"])

    def test_timeout_and_unavailable_ide_do_not_block_prompt(self):
        async def slow(*_):
            await asyncio.sleep(0.1)
            return "unreachable"

        output = StringIO()
        with mock.patch.dict(os.environ, {"CONTEXT_PACKER_HOOK_DEADLINE": "0.001"}, clear=True), \
                mock.patch.object(sys, "stdin", StringIO(json.dumps(PAYLOAD))), \
                mock.patch.object(pack_hook, "from_ide", side_effect=slow), \
                redirect_stdout(output):
            pack_hook.main()
        self.assertEqual("", output.getvalue())

        with mock.patch.dict(os.environ, {}, clear=True), \
                mock.patch.object(sys, "stdin", StringIO(json.dumps(PAYLOAD))), \
                mock.patch.object(pack_hook, "from_ide", new=mock.AsyncMock(side_effect=OSError("IDE stopped"))), \
                redirect_stdout(output):
            pack_hook.main()
        self.assertEqual("", output.getvalue())


def fake_mcp(answer):
    """MCP modules whose pack_context replies with answer(url, arguments); calls are recorded."""
    calls = []

    class FakeSse:
        def __init__(self, url):
            self.url = url

        async def __aenter__(self):
            if answer(self.url, None) is ConnectionRefusedError:
                raise ConnectionRefusedError(self.url)
            return self.url, object()

        async def __aexit__(self, *_):
            return False

    class FakeSession:
        def __init__(self, url, _write):
            self.url = url

        async def __aenter__(self):
            return self

        async def __aexit__(self, *_):
            return False

        async def initialize(self):
            pass

        async def call_tool(self, name, arguments):
            calls.append((self.url, name, arguments))
            error, text = answer(self.url, arguments)
            return types.SimpleNamespace(is_error=error, content=[types.SimpleNamespace(text=text)])

    mcp = types.ModuleType("mcp")
    mcp.__path__ = []
    mcp.ClientSession = FakeSession
    client = types.ModuleType("mcp.client")
    client.__path__ = []
    sse = types.ModuleType("mcp.client.sse")
    sse.sse_client = FakeSse
    return {"mcp": mcp, "mcp.client": client, "mcp.client.sse": sse}, calls


NOT_OPEN = (True, "`projectPath`=`/koog` doesn't correspond to any open project.")
WINDOWS_PATH = "\\\\wsl.localhost\\Ubuntu\\koog"


class HookMcpTest(unittest.IsolatedAsyncioTestCase):
    async def test_ide_call_forwards_provider_limit_and_project_path(self):
        modules, calls = fake_mcp(lambda _url, _args: (False, "Picked 8 files"))
        with mock.patch.dict(sys.modules, modules), mock.patch.dict(
            os.environ, {"CONTEXT_PACKER_HOOK_PROVIDER": "laya", "CONTEXT_PACKER_HOOK_LIMIT": "6"},
            clear=True,
        ):
            self.assertEqual("Picked 8 files", await pack_hook.from_ide(PAYLOAD["prompt"], "/koog"))
        self.assertEqual([("http://127.0.0.1:64342/sse", "pack_context", {
            "task": PAYLOAD["prompt"], "limit": 6, "provider": "laya", "projectPath": "/koog",
        })], calls)

    async def test_windows_ide_gets_the_wsl_path_it_knows(self):
        def answer(_url, args):
            return (False, "Picked 8 files") if args and args["projectPath"] == WINDOWS_PATH else NOT_OPEN

        modules, calls = fake_mcp(answer)
        with mock.patch.dict(sys.modules, modules), \
                mock.patch.dict(os.environ, {"WSL_DISTRO_NAME": "Ubuntu"}, clear=True), \
                mock.patch.object(pack_hook.subprocess, "check_output", return_value=WINDOWS_PATH + "\n"):
            self.assertEqual("Picked 8 files", await pack_hook.from_ide(PAYLOAD["prompt"], "/koog"))
        self.assertEqual(["/koog", WINDOWS_PATH], [args["projectPath"] for _, _, args in calls])

    async def test_next_port_when_one_is_closed_and_another_ide_lacks_the_project(self):
        def answer(url, _args):
            if url.endswith(":64342/sse"):
                return ConnectionRefusedError
            return NOT_OPEN if url.endswith(":64343/sse") else (False, "Picked 8 files")

        modules, calls = fake_mcp(answer)
        with mock.patch.dict(sys.modules, modules), mock.patch.dict(os.environ, {}, clear=True):
            self.assertEqual("Picked 8 files", await pack_hook.from_ide(PAYLOAD["prompt"], "/koog"))
        self.assertEqual(["http://127.0.0.1:64343/sse", "http://127.0.0.1:64344/sse"], [url for url, _, _ in calls])

    async def test_other_ide_errors_stop_without_retrying_or_leaking_the_message(self):
        modules, calls = fake_mcp(lambda _url, _args: (True, "Jev budget used up"))
        with mock.patch.dict(sys.modules, modules), \
                mock.patch.dict(os.environ, {"WSL_DISTRO_NAME": "Ubuntu"}, clear=True), \
                mock.patch.object(pack_hook.subprocess, "check_output", return_value=WINDOWS_PATH):
            self.assertEqual("", await pack_hook.from_ide(PAYLOAD["prompt"], "/koog"))
        self.assertEqual(1, len(calls))

    async def test_configured_endpoint_is_the_only_one_tried(self):
        modules, calls = fake_mcp(lambda _url, _args: NOT_OPEN)
        with mock.patch.dict(sys.modules, modules), \
                mock.patch.dict(os.environ, {"CONTEXT_PACKER_MCP": "http://127.0.0.1:9/sse"}, clear=True):
            self.assertEqual("", await pack_hook.from_ide(PAYLOAD["prompt"], "/koog"))
        self.assertEqual(["http://127.0.0.1:9/sse"], [url for url, _, _ in calls])


if __name__ == "__main__":
    unittest.main()
