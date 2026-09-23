"""Claude Code A/B: headless Claude Code finds the files for real Koog tasks, with and without Context Packer.

Same model, prompt and read-only tools in both arms, on a checkout at each task's parent commit. The only
difference is a UserPromptSubmit hook (agent/pack_hook.py) that packs the context before Claude starts, so the
model never has to choose to call a tool. (Given the tool instead, Sonnet ignored it; see claude_adoption.json.)
Runs one at a time so the Jev ledger stays exact.

    uv run python claude_ab.py --tasks 8
"""

import argparse
import json
import re
import statistics as st
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "spike"))
import jev_spike as js  # noqa: E402
import koog  # noqa: E402
from agent_ab import worktree  # noqa: E402

RESULTS = HERE / "results"
PROMPT = ("Find the source files in this repository that must be edited to implement this task. Do not edit "
          "anything and do not write code.\n\nTask: {task}\n\nWhen you are done, end your reply with one line in "
          "exactly this form, most likely first, up to 10 repository-relative paths:\nFILES: path/one.kt, path/two.kt")
READ_ONLY = "Read,Grep,Glob,LS,ToolSearch"  # ToolSearch loads deferred MCP tool schemas
DENY = "Bash,Edit,Write,MultiEdit,NotebookEdit,WebFetch,WebSearch,Task"


def run(task: koog.Task, text: str, with_pack: bool, model: str, budget: int) -> dict:
    root = worktree(task)
    hook = (f"cd {HERE} && TASK_PARENT={task.parent} JEV_TOKEN_BUDGET={budget} "
            f"uv run --project {HERE} python {HERE.parent / 'agent' / 'pack_hook.py'}")
    settings = {"hooks": {"UserPromptSubmit": [{"hooks": [{"type": "command", "command": hook, "timeout": 60}]}]}} if with_pack else {}
    cfg, mcp = RESULTS / f"settings_{'pack' if with_pack else 'none'}.json", RESULTS / "mcp_none.json"
    cfg.write_text(json.dumps(settings))
    mcp.write_text(json.dumps({"mcpServers": {}}))
    ledger_before = js._load_ledger()["tokens"]
    cmd = ["claude", "-p", PROMPT.format(task=text), "--model", model, "--output-format", "stream-json", "--verbose",
           "--settings", str(cfg), "--strict-mcp-config", "--mcp-config", str(mcp),
           "--allowedTools", READ_ONLY, "--disallowedTools", DENY]
    t0 = time.perf_counter()
    p = subprocess.run(cmd, cwd=root, capture_output=True, text=True, timeout=600)
    wall = time.perf_counter() - t0
    tools, result = {}, {}
    for line in p.stdout.splitlines():
        try:
            ev = json.loads(line)
        except ValueError:
            continue
        if ev.get("type") == "assistant":
            for block in ev.get("message", {}).get("content", []):
                if block.get("type") == "tool_use":
                    tools[block["name"]] = tools.get(block["name"], 0) + 1
        elif ev.get("type") == "result":
            result = ev
    text_out = result.get("result") or ""
    m = re.search(r"FILES:\s*(.+)", text_out)
    answer = [a.strip().strip("`").removeprefix("./").split(str(root) + "/")[-1] for a in m.group(1).split(",")][:10] if m else []
    u = result.get("usage") or {}
    return {"arm": "pack" if with_pack else "explore", "wall_s": round(wall, 1), "turns": result.get("num_turns"),
            "cost_usd": result.get("total_cost_usd"), "input_tokens": u.get("input_tokens", 0),
            "cache_read": u.get("cache_read_input_tokens", 0), "cache_write": u.get("cache_creation_input_tokens", 0),
            "output_tokens": u.get("output_tokens", 0), "tools": tools, "answer": answer, "answered": bool(m),
            "recall": len(set(answer) & set(task.truth)) / len(task.truth), "is_error": result.get("is_error"),
            "stderr": p.stderr[-300:] if p.returncode else "", "jev_tokens": js._load_ledger()["tokens"] - ledger_before}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tasks", type=int, default=8)
    ap.add_argument("--offset", type=int, default=40)
    ap.add_argument("--model", default="sonnet")
    ap.add_argument("--only", choices=["explore", "pack"])
    ap.add_argument("--jev-cap", type=int, default=7_100_000, help="more Jev input tokens this run may spend")

    args = ap.parse_args()
    budget = js.SPENT["tokens"] + args.jev_cap
    vague = {v["sha"]: v["vague"] for v in json.loads((RESULTS / "vague_tasks.json").read_text())}
    tasks = koog.load_tasks(limit=args.offset + args.tasks)[args.offset:]
    rows = []
    for t in tasks:
        text = vague.get(t.sha[:8], t.task)

        row = {"sha": t.sha[:8], "task": text, "truth": t.truth}
        for arm in ("explore", "pack"):
            if args.only and arm != args.only:
                continue
            row[arm] = r = run(t, text, arm == "pack", args.model, budget)
            print(f"{t.sha[:8]} {arm:7s} {r['wall_s']:5.1f}s turns={r['turns']} cost=${r['cost_usd'] or 0:.3f} "
                  f"in={r['input_tokens'] + r['cache_read'] + r['cache_write']:,} recall={r['recall']:.2f} tools={r['tools']} jev={r['jev_tokens']:,}"
                  + (f" ERR {r['stderr']}" if r['stderr'] else ""), flush=True)
        rows.append(row)
    RESULTS.mkdir(exist_ok=True)
    name = f"claude_ab_{args.model}_o{args.offset}_t{args.tasks}{'_' + args.only if args.only else ''}.json"
    (RESULTS / name).write_text(json.dumps({"model": args.model, "rows": rows}, indent=1))
    if not args.only:
        for arm in ("explore", "pack"):
            rs = [r[arm] for r in rows]
            print(f"{arm:8s} recall {st.mean(r['recall'] for r in rs):.2f} | wall med {st.median(r['wall_s'] for r in rs):.0f}s | "
                  f"turns {st.mean(r['turns'] or 0 for r in rs):.1f} | tokens {st.mean(r['input_tokens'] + r['cache_read'] + r['cache_write'] + r['output_tokens'] for r in rs):,.0f} | "
                  f"cost ${sum(r['cost_usd'] or 0 for r in rs):.2f}")


if __name__ == "__main__":
    main()
