"""Claude Code UserPromptSubmit hook: pack the context before the agent starts, so it never has to choose to.

Reads the hook payload from stdin, asks Context Packer for the files the prompt needs, and returns them as
additionalContext. Demo mode asks the running IDE over MCP (so its tool window shows the pack); with
TASK_PARENT set it runs the eval pipeline on that commit instead. Any failure exits 0 with no output, so
the hook can never block a prompt.
"""

import asyncio
import json
import os
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
IDE_MCP = os.environ.get("CONTEXT_PACKER_MCP", "http://127.0.0.1:64342/sse")
LIMIT = int(os.environ.get("CONTEXT_PACKER_HOOK_LIMIT", "8"))


async def from_ide(prompt: str, cwd: str) -> str:
    from mcp import ClientSession
    from mcp.client.sse import sse_client
    async with sse_client(IDE_MCP) as (read, write), ClientSession(read, write) as session:
        await session.initialize()
        result = await session.call_tool("pack_context", {"task": prompt, "limit": LIMIT, "projectPath": cwd})
        return "\n".join(getattr(b, "text", "") for b in result.content)


async def from_eval(prompt: str) -> str:
    sys.path.insert(0, str(HERE.parent / "eval"))
    import pack_mcp  # needs TASK_PARENT, which is set in eval mode
    return await pack_mcp.pack_context(prompt, LIMIT)


def main():
    payload = json.load(sys.stdin)
    prompt = (payload.get("prompt") or "").strip()
    if len(prompt.split()) < 4 or prompt.startswith("/"):
        return
    t0 = time.perf_counter()
    packed = asyncio.run(from_eval(prompt) if os.environ.get("TASK_PARENT") else from_ide(prompt, payload.get("cwd", "")))
    if not packed.strip():
        return
    seconds = time.perf_counter() - t0
    print(json.dumps({
        "systemMessage": f"Context Packer ranked the project for this request in {seconds:.1f} s",
        "hookSpecificOutput": {
            "hookEventName": "UserPromptSubmit",
            "additionalContext": "Context Packer already scored every file in this project against the request. "
                                 "Start with these files instead of searching; verify by reading them.\n\n" + packed,
        },
    }))


if __name__ == "__main__":
    try:
        main()
    except Exception:  # never block the user's prompt
        sys.exit(0)
