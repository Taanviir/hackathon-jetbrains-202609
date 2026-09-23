"""Claude Code UserPromptSubmit hook: pack the context before the agent starts, so it never has to choose to.

Reads the hook payload from stdin, asks Context Packer for the files the prompt needs, and returns them as
additionalContext. Demo mode asks the running IDE over MCP (so its tool window shows the pack); with
TASK_PARENT set it runs the eval pipeline on that commit instead. Any failure exits 0 with no output, so
the hook can never block a prompt.
"""

import asyncio
import json
import math
import os
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
IDE_MCP = os.environ.get("CONTEXT_PACKER_MCP", "http://127.0.0.1:64342/sse")


def deadline() -> float:
    # Give up before Claude Code's hook timeout; malformed settings must also fail open.
    seconds = float(os.environ.get("CONTEXT_PACKER_HOOK_DEADLINE", "25"))
    if not math.isfinite(seconds) or seconds <= 0:
        raise ValueError("CONTEXT_PACKER_HOOK_DEADLINE must be finite and positive")
    return seconds


def provider() -> str:
    selected = os.environ.get("CONTEXT_PACKER_HOOK_PROVIDER", "keywords").lower()
    if selected not in {"keywords", "configured", "laya", "jev"}:
        raise ValueError("CONTEXT_PACKER_HOOK_PROVIDER must be keywords, configured, laya, or jev")
    return selected


async def from_ide(prompt: str, cwd: str) -> str:
    from mcp import ClientSession
    from mcp.client.sse import sse_client
    async with sse_client(IDE_MCP) as (read, write), ClientSession(read, write) as session:
        await session.initialize()
        result = await session.call_tool("pack_context", {
            "task": prompt, "limit": limit(), "provider": provider(), "projectPath": cwd,
        })
        if getattr(result, "is_error", None) or getattr(result, "isError", None):
            return ""  # e.g. budget used up: never feed an error message to the agent as context
        return "\n".join(getattr(b, "text", "") for b in result.content)


async def from_eval(prompt: str) -> str:
    sys.path.insert(0, str(HERE.parent / "eval"))
    import pack_mcp  # needs TASK_PARENT, which is set in eval mode
    return await pack_mcp.pack_context(prompt, limit())


def limit() -> int:
    count = int(os.environ.get("CONTEXT_PACKER_HOOK_LIMIT", "8"))
    if count not in range(1, 21):
        raise ValueError("CONTEXT_PACKER_HOOK_LIMIT must be between 1 and 20")
    return count


def run():
    payload = json.load(sys.stdin)
    prompt = (payload.get("prompt") or "").strip()
    if len(prompt.split()) < 4 or prompt.startswith("/"):
        return
    timeout = deadline()
    t0 = time.perf_counter()
    work = from_eval(prompt) if os.environ.get("TASK_PARENT") else from_ide(prompt, payload.get("cwd", ""))
    packed = asyncio.run(asyncio.wait_for(work, timeout))
    if not packed.strip():
        return
    seconds = time.perf_counter() - t0
    print(json.dumps({
        "systemMessage": f"Context Packer ranked the project for this request in {seconds:.1f} s",
        "hookSpecificOutput": {
            "hookEventName": "UserPromptSubmit",
            "additionalContext": "Context Packer selected likely source files for this task. "
                                 "Start with these files and verify relevance by reading them.\n\n" + packed,
        },
    }))


def main():
    try:
        run()
    except Exception:  # never block the user's prompt
        return


if __name__ == "__main__":
    main()
