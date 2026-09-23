"""Call the sandbox IDE's MCP server the way an agent would.

    uv run --with mcp python tools/mcp_call.py list
    uv run --with mcp python tools/mcp_call.py pack "Add exponential backoff to retries"
"""

import asyncio
import sys
import time

from mcp import ClientSession
from mcp.client.sse import sse_client

URL = "http://127.0.0.1:64342/sse"
PROJECT = str(__import__("pathlib").Path(__file__).resolve().parents[1] / ".cache" / "koog")


async def main(cmd: str, arg: str | None):
    async with sse_client(URL) as (read, write), ClientSession(read, write) as session:
        await session.initialize()
        if cmd == "list":
            tools = (await session.list_tools()).tools
            print(len(tools), "tools:", ", ".join(sorted(t.name for t in tools)))
            for t in tools:
                if t.name == "pack_context":
                    print("\n" + t.description.strip() + "\n", getattr(t, "input_schema", None) or getattr(t, "inputSchema", None))
            return
        started = time.perf_counter()
        result = await session.call_tool("pack_context", {"task": arg, "limit": 10, "projectPath": PROJECT})
        print(f"[{time.perf_counter() - started:.1f}s wall, error={getattr(result, 'is_error', None) or getattr(result, 'isError', None)}]")
        for block in result.content:
            print(getattr(block, "text", block))


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None))
