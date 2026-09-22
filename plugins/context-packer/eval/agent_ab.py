"""Agent A/B: the same agent finds the files a real Koog commit changed, with and without pack_context.

Both arms get the same model, system prompt and exploration tools on a real checkout of Koog at the
task's parent commit. The only difference is whether a `pack_context` tool exists. Each run ends when
the agent calls `answer` with its file list.

    uv run python agent_ab.py --tasks 10 --offset 20
"""

import argparse
import asyncio
import json
import os
import statistics as st
import subprocess
import sys
import time
from pathlib import Path

import httpx
from dotenv import load_dotenv

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "spike"))
import jev_spike as js  # noqa: E402
import koog  # noqa: E402

load_dotenv(koog.ROOT.parents[1] / ".env")
MODEL = os.environ.get("CONTEXT_PACKER_LLM_MODEL", "z-ai/glm-5.3-flash")
WORKTREES = koog.ROOT / ".cache" / "worktrees"
RESULTS = HERE / "results"
MAX_STEPS = 20
FORCE_ANSWER_AT = MAX_STEPS - 2
MAX_TOOL_CHARS = 8_000
IS_TEST = __import__("re").compile(r"(^|/)[\w-]*[tT]est[\w-]*/|(Test|Tests|Spec|IT)\.[A-Za-z]+$")  # same as Packer.isTest

SYSTEM = (
    "You are a coding agent working in the Koog repository, a Kotlin framework. Your job right now is ONLY to find "
    "the source files that must be edited to implement the task. Do not write code. Use the tools, then call "
    "`answer` with up to 10 repository-relative file paths, most likely first. You have at most 20 steps. Answer as "
    "soon as you are confident you have the right files; do not verify exhaustively."
)


def fn(name: str, description: str, props: dict, required: list[str]) -> dict:
    return {"type": "function", "function": {"name": name, "description": description,
                                             "parameters": {"type": "object", "properties": props, "required": required}}}


BASE_TOOLS = [
    fn("list_dir", "List a directory. Directories end with /.", {"path": {"type": "string"}}, ["path"]),
    fn("grep", "Search file contents with an extended regex (git grep). Returns path:line: text, at most 60 lines.",
       {"pattern": {"type": "string"}, "path": {"type": "string", "description": "directory to search, default ."}},
       ["pattern"]),
    fn("read_file", "Read a file, 200 lines at a time.",
       {"path": {"type": "string"}, "start_line": {"type": "integer"}}, ["path"]),
    fn("answer", "Finish: the files that must be edited, most likely first.",
       {"paths": {"type": "array", "items": {"type": "string"}}}, ["paths"]),
]
# Same description the IDE plugin registers over MCP.
PACK_TOOL = fn(
    "pack_context",
    "Find the files in the open project that a coding task needs, ranked by relevance. Call this FIRST, before "
    "searching or listing directories: it scores every file in the project in about three seconds, so you can go "
    "straight to reading the top results instead of exploring. Returns project-relative paths with a 0-1 relevance "
    "score; test files are marked.",
    {"task": {"type": "string"}, "limit": {"type": "integer"}}, ["task"],
)


# ---------------------------------------------------------------- tools

class Tools:
    def __init__(self, root: Path, task: koog.Task, jev: js.Jev):
        self.root, self.task, self.jev = root, task, jev
        self.pack_s = 0.0

    def _safe(self, path: str) -> Path:
        p = (self.root / path.lstrip("/").removeprefix(str(self.root).lstrip("/"))).resolve()
        if not str(p).startswith(str(self.root.resolve())):
            raise ValueError("path outside the repository")
        return p

    def list_dir(self, path: str = ".") -> str:
        d = self._safe(path)
        entries = sorted(e.name + ("/" if e.is_dir() else "") for e in d.iterdir() if e.name != ".git")
        return "\n".join(entries[:200]) or "(empty)"

    def grep(self, pattern: str, path: str = ".") -> str:
        r = subprocess.run(["git", "-C", str(self.root), "grep", "-n", "-I", "-E", "--max-count=3", "-e", pattern,
                            "--", path or "."], capture_output=True, text=True)
        if r.returncode not in (0, 1):
            return f"grep error: {r.stderr.strip()[:200]}"
        lines = [ln[:200] for ln in r.stdout.splitlines()]
        return "\n".join(lines[:60]) + (f"\n… {len(lines) - 60} more" if len(lines) > 60 else "") or "(no matches)"

    def read_file(self, path: str, start_line: int = 1) -> str:
        lines = self._safe(path).read_text(errors="replace").splitlines()
        start = max(1, int(start_line or 1))
        chunk = lines[start - 1:start + 199]
        more = f"\n… {len(lines) - start - 199} more lines" if len(lines) > start + 199 else ""
        return "\n".join(f"{start + i}: {ln}" for i, ln in enumerate(chunk)) + more

    async def pack_context(self, task: str, limit: int = 10) -> str:
        """The plugin's pipeline: Jev on sketches + BM25, pool of 60 each, Jev re-rank on full source."""
        t0 = time.perf_counter()
        files = koog.files_at(self.task.parent)
        sk = {p: js.sketch(p, s) for p, s in files.items()}
        # BM25 runs alongside pass 1, as it does in the plugin
        bm25 = asyncio.create_task(asyncio.to_thread(js.bm25_rank, task, files))
        s1 = await js.score_pass1(self.jev, task, sk, 60)
        br = await bm25
        pool = list(dict.fromkeys(br[:60] + sorted(s1, key=lambda p: -s1[p])[:60]))
        s2 = await js.score_full(self.jev, task, files, pool, 6, 6000)
        pos = {p: i for i, p in enumerate(br)}
        score = {p: (s2.get(p, 0) + 1.0 / (1 + pos.get(p, 999) / 10)) / 2.0 for p in pool}  # as the plugin
        ranked = sorted(pool, key=lambda p: -score[p])
        self.pack_s += time.perf_counter() - t0
        limit = max(1, min(20, int(limit or 10)))
        rows = [f"{score[p]:.2f}   {p}{'  (test)' if IS_TEST.search(p) else ''}" for p in ranked[:limit]]
        return (f"Picked {limit} of {len(files):,} files in {time.perf_counter() - t0:.1f} s.\nscore  path\n"
                + "\n".join(rows) + "\nRead the top few first. Scores come from Jev reading each file's full source "
                "against the task, fused with keyword match.")

    async def call(self, name: str, args: dict) -> str:
        try:
            if name == "pack_context":
                return await self.pack_context(**args)
            return getattr(self, name)(**args)
        except Exception as e:  # the agent sees tool errors, like a real one would
            return f"error: {type(e).__name__}: {e}"


# ---------------------------------------------------------------- agent

async def chat(http: httpx.AsyncClient, messages: list, tools: list, force_answer: bool = False) -> dict:
    body = {"model": MODEL, "messages": messages, "tools": tools, "temperature": 0}
    if force_answer:
        body["tool_choice"] = {"type": "function", "function": {"name": "answer"}}
    for attempt in range(4):
        r = await http.post("https://openrouter.ai/api/v1/chat/completions", json=body)
        if r.status_code == 200 and "choices" in r.json():
            return r.json()
        if r.status_code not in (408, 429) and r.status_code < 500:
            raise RuntimeError(f"HTTP {r.status_code}: {r.text[:300]}")
        await asyncio.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"gave up: HTTP {r.status_code}: {r.text[:200]}")


PATH_RE = __import__("re").compile(r"[\w./-]+\.kts?\b")


def normalise(path: str, root: Path) -> str:
    p = path.strip().removeprefix("./")
    return p.split(str(root) + "/", 1)[-1].lstrip("/")


async def run_arm(http, task: koog.Task, root: Path, with_pack: bool, jev: js.Jev) -> dict:
    tools = Tools(root, task, jev)
    schema = BASE_TOOLS + ([PACK_TOOL] if with_pack else [])
    messages = [{"role": "system", "content": SYSTEM}, {"role": "user", "content": f"Task: {task.task}"}]
    stats = {"llm_calls": 0, "prompt_tokens": 0, "completion_tokens": 0, "cost": 0.0, "tool_calls": {}}
    answer, started = None, time.perf_counter()
    truth = set(task.truth)
    first_seen = first_opened = None  # (step, seconds) when a right file first reached / was opened by the agent
    for step in range(MAX_STEPS):
        forced = step >= FORCE_ANSWER_AT
        if step == FORCE_ANSWER_AT:
            messages.append({"role": "user", "content": "Out of steps. Call `answer` now with your best list."})
        resp = await chat(http, messages, schema, force_answer=forced)
        stats["llm_calls"] += 1
        u = resp.get("usage") or {}
        stats["prompt_tokens"] += u.get("prompt_tokens", 0)
        stats["completion_tokens"] += u.get("completion_tokens", 0)
        stats["cost"] += u.get("cost") or 0.0
        msg = resp["choices"][0]["message"]
        calls = msg.get("tool_calls") or []
        messages.append({"role": "assistant", "content": msg.get("content") or "", **({"tool_calls": calls} if calls else {})})
        if not calls:
            if forced:  # a model that ignores tool_choice still usually lists the paths in prose
                answer = [normalise(p, root) for p in PATH_RE.findall(msg.get("content") or "")][:10]
                break
            messages.append({"role": "user", "content": "Call `answer` with your list of file paths."})
            continue
        for c in calls:
            name = c["function"]["name"]
            try:
                args = json.loads(c["function"].get("arguments") or "{}")
            except json.JSONDecodeError:
                args = {}
            stats["tool_calls"][name] = stats["tool_calls"].get(name, 0) + 1
            if name == "answer":
                answer = [normalise(p, root) for p in args.get("paths", [])][:10]
                break
            out = await tools.call(name, args)
            now = round(time.perf_counter() - started, 1)
            if first_seen is None and any(p in out for p in truth):
                first_seen = (step + 1, now)
            if first_opened is None and name == "read_file" and normalise(args.get("path", ""), root) in truth:
                first_opened = (step + 1, now)
            messages.append({"role": "tool", "tool_call_id": c["id"], "content": out[:MAX_TOOL_CHARS]})
        if answer is not None:
            break
    wall = time.perf_counter() - started
    found = set(answer or []) & set(task.truth)
    return {"arm": "pack" if with_pack else "explore", "wall_s": round(wall, 1), "pack_s": round(tools.pack_s, 1),
            "recall": len(found) / len(task.truth), "answered": answer is not None, "answer": answer or [],
            "first_seen_step": first_seen and first_seen[0], "first_seen_s": first_seen and first_seen[1],
            "first_opened_step": first_opened and first_opened[0], "first_opened_s": first_opened and first_opened[1],
            "steps": stats["llm_calls"], **stats, "transcript": [
                {"role": m["role"], "content": (m.get("content") or "")[:400],
                 "tools": [c["function"]["name"] + " " + c["function"].get("arguments", "")[:160] for c in m.get("tool_calls", [])]}
                for m in messages[1:]]}


def worktree(task: koog.Task) -> Path:
    path = WORKTREES / task.sha[:10]
    if not path.exists():
        subprocess.run(["git", "-C", str(koog.REPO), "worktree", "add", "--detach", "-q", str(path), task.parent],
                       check=True, capture_output=True)
    return path


async def run_task(http, task: koog.Task, sem: asyncio.Semaphore) -> dict:
    async with sem:
        root = worktree(task)
        jev = js.Jev("jev-latest", 16)
        explore, pack = await asyncio.gather(
            run_arm(http, task, root, False, jev), run_arm(http, task, root, True, jev))
        fs = lambda a: f"{a['first_seen_s']}s@{a['first_seen_step']}" if a["first_seen_s"] is not None else "never"
        print(f"{task.sha[:8]}  explore {explore['wall_s']:5.1f}s {explore['llm_calls']:2d} calls seen {fs(explore):>9} "
              f"recall {explore['recall']:.2f} | pack {pack['wall_s']:5.1f}s {pack['llm_calls']:2d} calls seen {fs(pack):>9} "
              f"recall {pack['recall']:.2f}  {task.task[:40]}", flush=True)
        return {"sha": task.sha[:8], "task": task.task, "truth": task.truth, "explore": explore, "pack": pack}


def summarise(rows: list[dict]) -> dict:
    out = {}
    for arm in ("explore", "pack"):
        rs = [r[arm] for r in rows]
        out[arm] = {
            "wall_s_median": round(st.median(r["wall_s"] for r in rs), 1),
            "wall_s_mean": round(st.mean(r["wall_s"] for r in rs), 1),
            "llm_calls_mean": round(st.mean(r["llm_calls"] for r in rs), 1),
            "tokens_mean": round(st.mean(r["prompt_tokens"] + r["completion_tokens"] for r in rs)),
            "cost_total": round(sum(r["cost"] for r in rs), 4),
            "recall_mean": round(st.mean(r["recall"] for r in rs), 3),
            "answered": sum(r["answered"] for r in rs),
            "used_pack_context": sum("pack_context" in r["tool_calls"] for r in rs),
            # a run that never surfaced a right file counts as the whole run, so misses aren't hidden
            "first_seen_s_median": round(st.median(r["first_seen_s"] if r["first_seen_s"] is not None else r["wall_s"] for r in rs), 1),
            "first_seen_step_median": st.median(r["first_seen_step"] or r["steps"] for r in rs),
            "surfaced": sum(r["first_seen_s"] is not None for r in rs),
            "first_opened_s_median": round(st.median(r["first_opened_s"] if r["first_opened_s"] is not None else r["wall_s"] for r in rs), 1),
        }
    return out


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tasks", type=int, default=10)
    ap.add_argument("--offset", type=int, default=20)
    ap.add_argument("--parallel", type=int, default=5)
    ap.add_argument("--vague", action="store_true", help="give both arms the identifier-free rewrite from vague.py")
    args = ap.parse_args()
    tasks = koog.load_tasks(limit=args.offset + args.tasks)[args.offset:]
    if args.vague:
        rewrites = {v["sha"]: v["vague"] for v in json.loads((RESULTS / "vague_tasks.json").read_text())}
        tasks = [koog.Task(t.sha, t.parent, rewrites[t.sha[:8]], t.truth) for t in tasks]
    key = os.environ["OPENROUTER_API_KEY"]
    async with httpx.AsyncClient(timeout=120, headers={"Authorization": f"Bearer {key}", "X-Title": "Context Packer eval"}) as http:
        sem = asyncio.Semaphore(args.parallel)
        rows = await asyncio.gather(*(run_task(http, t, sem) for t in tasks))
    summary = {"model": MODEL, "tasks": len(rows), "offset": args.offset, "vague": args.vague, **summarise(rows)}
    print(json.dumps(summary, indent=1))
    RESULTS.mkdir(exist_ok=True)
    (RESULTS / f"agent_ab_o{args.offset}_t{args.tasks}{'_vague' if args.vague else ''}.json").write_text(json.dumps({"summary": summary, "rows": rows}, indent=1))


if __name__ == "__main__":
    asyncio.run(main())
