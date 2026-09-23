"""A stdio MCP server exposing the plugin's pack_context over a checkout at a task's parent commit.

Headless Claude Code can't use the IDE's MCP server in a batch run, so this serves the same pipeline
(pass 1, BM25, pass 2, fusion, stage 3, roles) with the plugin's own output format.

    TASK_PARENT=<sha> uv run python pack_mcp.py        # launched by claude_ab.py via --mcp-config
"""

import asyncio
import os
import re
import sys
from pathlib import Path

from mcp.server.mcpserver import MCPServer

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "spike"))
import jev_spike as js  # noqa: E402
import koog  # noqa: E402
import stage3  # noqa: E402

PARENT = os.environ["TASK_PARENT"]
IS_TEST = re.compile(r"(^|/)[\w-]*[tT]est[\w-]*/|(Test|Tests|Spec|IT)\.[A-Za-z]+$")
mcp = MCPServer("context-packer")
jev = js.Jev("jev-latest", 16)
ROLES = {"edit": "Implementing the change requires editing this file.",
         "test": "This file tests the code being changed and would need updating.",
         "example": "This file shows an existing pattern the change should follow, but is not edited.",
         "dependency": "The change uses an API declared in this file, but the file is not edited.",
         "unrelated": "This file has nothing to do with the change."}


@mcp.tool()
async def pack_context(task: str, limit: int = 10) -> str:
    """Find the files in the open project that a coding task needs, ranked by relevance. Call this FIRST, before
    searching or listing directories: it scores every file in the project in about three seconds, so you can go
    straight to reading the top results instead of exploring. Returns project-relative paths with a 0-1 relevance
    score, and the top files labelled edit, test, example or dependency."""
    import time
    t0 = time.perf_counter()
    files = koog.files_at(PARENT)
    sk = {p: js.sketch(p, s) for p, s in files.items()}
    bm25 = asyncio.create_task(asyncio.to_thread(js.bm25_rank, task, files))
    s1 = await js.score_pass1(jev, task, sk, 60)
    br = await bm25
    pool = list(dict.fromkeys(br[:60] + sorted(s1, key=lambda p: -s1[p])[:60]))
    s2 = await js.score_full(jev, task, files, pool, 6, 6000)
    bpos = {p: i for i, p in enumerate(br)}
    fused_score = {p: s2.get(p, 0) + 1.0 / (1 + bpos.get(p, 999) / 10) for p in pool}
    fused = sorted(pool, key=lambda p: -fused_score[p])
    top = fused[:10]

    class T:  # stage3.ask wants an object with .task
        pass
    t = T(); t.task = task
    keys = {f"f{i:03d}": p for i, p in enumerate(top)}
    state = {"task": task, **{k: f"path: {p}\n{files[p][:6000]}" for k, p in keys.items()}}
    role_q = {f"role_{k}": {"type": "choice", "criteria": ROLES,
                            "instructions": f"What part does the file in `{k}` play in the change described in `task`?"} for k in keys}
    probs, role_ans = await asyncio.gather(stage3.ask(jev, t, fused, files, 10, 6000), jev.ask(state, role_q))
    score = {p: (fused_score[p] + 2.0 * probs.get(p, 0)) / 4.0 for p in pool}
    ranked = sorted(pool, key=lambda p: -score[p])
    def role(p):
        k = next((k for k, v in keys.items() if v == p), None)
        pr = getattr(role_ans.get(f"role_{k}"), "probabilities", None) if k else None
        if not pr:
            return "test" if IS_TEST.search(p) else None
        best = max(pr, key=pr.get)
        return best if pr[best] >= 0.5 and best != "unrelated" else ("test" if IS_TEST.search(p) else None)
    limit = max(1, min(20, int(limit or 10)))
    rows = [f"{score[p]:.2f}   {p}" + (f"  ({role(p)})" if role(p) else "") for p in ranked[:limit]]
    return (f"Picked {limit} of {len(files):,} files in {time.perf_counter() - t0:.1f} s. Paths are relative to the "
            f"project root.\nscore  path\n" + "\n".join(rows) +
            "\nRead the top few first. Scores come from Jev reading each file's full source against the task, "
            "fused with keyword match.")


if __name__ == "__main__":
    mcp.run()
