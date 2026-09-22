"""Baseline: have an LLM re-rank BM25's shortlist instead of Jev, on the same held-out tasks.

Same pool (BM25 top N), same full source (6,000 chars a file), same 70 test tasks as the headline
result. Jev's pass-2 scores for that pool are already saved, so only the LLM side makes calls.
Stops itself once OpenRouter reports MAX_COST_USD spent.

    uv run python rerank_llm.py --pool 30
"""

import argparse
import asyncio
import json
import os
import re
import statistics as st
import sys
import time
from pathlib import Path

import httpx
from dotenv import load_dotenv

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "spike"))
import fusion  # noqa: E402
import koog  # noqa: E402

load_dotenv(koog.ROOT.parents[1] / ".env")
MODEL = os.environ.get("CONTEXT_PACKER_LLM_MODEL", "z-ai/glm-5.3-flash")
MAX_COST_USD = float(os.environ.get("RERANK_MAX_COST_USD", "0.80"))
SPENT = {"usd": 0.0}

PROMPT = """You are helping a coding agent decide which files to read first.

TASK: {task}

Below are {n} files from the repository, each labelled f000, f001, and so on. Decide which of them
implementing the task would require reading or editing.

Reply with JSON only, in this shape: {{"ranked": ["f007", "f002", ...]}}
List up to 10 labels, most likely first. Use only labels that appear below.

{files}"""


async def rerank(http, task: str, pool: list[str], files: dict[str, str]) -> tuple[list[str], dict]:
    labels = {f"f{i:03d}": p for i, p in enumerate(pool)}
    body = "\n\n".join(f"=== {k}: {p} ===\n{files[p][:6000]}" for k, p in labels.items())
    msg = PROMPT.format(task=task, n=len(pool), files=body)
    for attempt in range(4):
        if SPENT["usd"] >= MAX_COST_USD:
            raise RuntimeError(f"cost cap ${MAX_COST_USD} reached")
        t0 = time.perf_counter()
        r = await http.post("https://openrouter.ai/api/v1/chat/completions", json={
            "model": MODEL, "temperature": 0, "messages": [{"role": "user", "content": msg}]})
        if r.status_code == 200 and "choices" in r.json():
            d = r.json()
            u = d.get("usage") or {}
            SPENT["usd"] += u.get("cost") or 0.0
            text = d["choices"][0]["message"].get("content") or ""
            picked = [k for k in re.findall(r"f\d{3}", text) if k in labels]
            ranked = list(dict.fromkeys(labels[k] for k in picked))
            return ranked, {"ms": (time.perf_counter() - t0) * 1000, "in": u.get("prompt_tokens", 0),
                            "out": u.get("completion_tokens", 0), "cost": u.get("cost") or 0.0, "parsed": len(ranked)}
        await asyncio.sleep(2 * (attempt + 1))
    raise RuntimeError(f"HTTP {r.status_code}: {r.text[:200]}")


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pool", type=int, default=30)
    ap.add_argument("--dev", type=int, default=40)
    ap.add_argument("--parallel", type=int, default=4)
    args = ap.parse_args()

    dump = json.loads((koog.ROOT / ".cache" / "spike_runs" / "clean110.json").read_text())["rows"]
    tasks = {t.sha[:8]: t for t in koog.load_tasks(limit=500)}
    test = [(r, tasks[r["sha"]]) for r in dump[args.dev:]]
    sem = asyncio.Semaphore(args.parallel)
    key = os.environ["OPENROUTER_API_KEY"]

    async def one(http, row, task):
        d = row["dump"]
        pool = d["bm25"][:args.pool]
        async with sem:
            files = koog.files_at(task.parent)
            try:
                ranked, call = await rerank(http, task.task, pool, files)
            except RuntimeError as e:
                return {"sha": row["sha"], "error": str(e)}
        rest = [p for p in pool if p not in ranked]  # unlisted files follow in BM25 order
        bpos = {p: i for i, p in enumerate(d["bm25"])}
        jev = sorted(pool, key=lambda p: -d["s2"].get(p, 0))
        jev_fused = sorted(pool, key=lambda p: -(d["s2"].get(p, 0) + 1.0 / (1 + bpos[p] / 10)))
        rk = {"llm": ranked + rest, "jev": jev, "jev_fused": jev_fused, "bm25": pool}
        out = {"sha": row["sha"], "call": call, **{f"{n}@{k}": fusion.recall(v, d["truth"], k)
                                                    for n, v in rk.items() for k in (5, 10)}}
        print(f"{row['sha']}  llm@10 {out['llm@10']:.2f}  jev_fused@10 {out['jev_fused@10']:.2f}  "
              f"bm25@10 {out['bm25@10']:.2f}  {call['ms'] / 1000:.1f}s ${call['cost']:.4f}  spent ${SPENT['usd']:.3f}", flush=True)
        return out

    async with httpx.AsyncClient(timeout=180, headers={"Authorization": f"Bearer {key}", "X-Title": "Context Packer eval"}) as http:
        rows = await asyncio.gather(*(one(http, r, t) for r, t in test))
    ok = [r for r in rows if "error" not in r]
    summ = {"model": MODEL, "pool": args.pool, "tasks": len(ok), "skipped": len(rows) - len(ok),
            **{m: round(st.mean(r[m] for r in ok), 3) for m in ok[0] if "@" in m},
            "llm_latency_s_median": round(st.median(r["call"]["ms"] for r in ok) / 1000, 1),
            "llm_input_tokens_mean": round(st.mean(r["call"]["in"] for r in ok)),
            "llm_cost_total": round(sum(r["call"]["cost"] for r in ok), 4),
            "llm_parsed_mean": round(st.mean(r["call"]["parsed"] for r in ok), 1)}
    # paired bootstrap on per-task differences, as for the headline
    import random
    rng = random.Random(7)
    for a, b in (("jev_fused", "llm"), ("llm", "bm25")):
        for k in (5, 10):
            per = [r[f"{a}@{k}"] - r[f"{b}@{k}"] for r in ok]
            means = sorted(st.mean(rng.choice(per) for _ in per) for _ in range(2000))
            summ[f"{a}-{b}@{k}"] = [round(st.mean(per), 3), round(means[50], 3), round(means[1949], 3)]
    print(json.dumps(summ, indent=1))
    (HERE / "results").mkdir(exist_ok=True)
    (HERE / "results" / f"rerank_llm_pool{args.pool}.json").write_text(json.dumps({"summary": summ, "rows": rows}, indent=1))


if __name__ == "__main__":
    asyncio.run(main())
