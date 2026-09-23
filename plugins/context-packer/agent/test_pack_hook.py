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


class HookMcpTest(unittest.IsolatedAsyncioTestCase):
    async def test_ide_call_forwards_provider_limit_and_project_path(self):
        calls = []

        class FakeSse:
            async def __aenter__(self):
                return object(), object()

            async def __aexit__(self, *_):
                return False

        class FakeSession:
            def __init__(self, *_):
                pass

            async def __aenter__(self):
                return self

            async def __aexit__(self, *_):
                return False

            async def initialize(self):
                pass

            async def call_tool(self, name, arguments):
                calls.append((name, arguments))
                return types.SimpleNamespace(
                    is_error=False, content=[types.SimpleNamespace(text="Picked 8 files")]
                )

        mcp = types.ModuleType("mcp")
        mcp.__path__ = []
        mcp.ClientSession = FakeSession
        client = types.ModuleType("mcp.client")
        client.__path__ = []
        sse = types.ModuleType("mcp.client.sse")
        sse.sse_client = lambda _url: FakeSse()
        modules = {"mcp": mcp, "mcp.client": client, "mcp.client.sse": sse}
        with mock.patch.dict(sys.modules, modules), mock.patch.dict(
            os.environ, {"CONTEXT_PACKER_HOOK_PROVIDER": "laya", "CONTEXT_PACKER_HOOK_LIMIT": "6"},
            clear=True,
        ):
            self.assertEqual("Picked 8 files", await pack_hook.from_ide(PAYLOAD["prompt"], "/koog"))
        self.assertEqual("pack_context", calls[0][0])
        self.assertEqual({
            "task": PAYLOAD["prompt"], "limit": 6, "provider": "laya", "projectPath": "/koog",
        }, calls[0][1])


if __name__ == "__main__":
    unittest.main()
