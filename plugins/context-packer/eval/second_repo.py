"""Out-of-domain check: the shipped pipeline, frozen as it is, on a repository it was never tuned on.

Nothing here is chosen on the new repo: pool 60, equal-weight BM25 fusion, stage 3 (top 10, add2), all
as shipped. Run it once and report whatever comes out.

    EVAL_REPO=exposed EVAL_BRANCH=main EVAL_TICKET_RE='\\bEXPOSED-\\d+\\b:?\\s*' uv run python second_repo.py --tasks 40
"""

import argparse
import asyncio
import json
import random
import statistics as st
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "spike"))
import fusion  # noqa: E402
import jev_spike as js  # noqa: E402
import koog  # noqa: E402
import stage3  # noqa: E402

POOL, PER_CALL, CHARS, BATCH = 60, 6, 6000, 60


def boot(per, n=2000, seed=7):
    rng = random.Random(seed)
    means = sorted(st.mean(rng.choice(per) for _ in per) for _ in range(n))
    return [round(st.mean(per), 3), round(means[int(.025 * n)], 3), round(means[int(.975 * n)], 3)]


async def pack(jev, t):
    files = koog.files_at(t.parent)
    sk = {p: js.sketch(p, s) for p, s in files.items()}
    t0 = time.perf_counter()
    bm25 = asyncio.create_task(asyncio.to_thread(js.bm25_rank, t.task, files))
    s1 = await js.score_pass1(jev, t.task, sk, BATCH)
    br = await bm25
    pool = list(dict.fromkeys(br[:POOL] + sorted(s1, key=lambda p: -s1[p])[:POOL]))
    s2 = await js.score_full(jev, t.task, files, pool, PER_CALL, CHARS)
    d = {"bm25": br, "s2": s2, "truth": t.truth}
    bpos = {p: i for i, p in enumerate(br)}
    fused = sorted(pool, key=lambda p: -(s2.get(p, 0) + 1.0 / (1 + bpos.get(p, 999) / 10)))
    probs = await stage3.ask(jev, t, fused, files, 10, CHARS)
    shipped = stage3.rerank(d, fused, probs, 10, "add2")
    return {"sha": t.sha[:8], "task": t.task, "n_files": len(files), "truth": t.truth, "seconds": round(time.perf_counter() - t0, 2),
            **{f"{n}@{k}": fusion.recall(r, t.truth, k) for n, r in (("bm25", br), ("fused", fused), ("shipped", shipped)) for k in (5, 10, 20)},
            "ceiling": fusion.recall(pool, t.truth, len(pool))}


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tasks", type=int, default=40)
    args = ap.parse_args()
    tasks = koog.load_tasks(limit=args.tasks)
    jev = js.Jev("jev-latest", 16)
    rows = []
    for t in tasks:
        r = await pack(jev, t)
        rows.append(r)
        print(f"{r['sha']}  shipped@10 {r['shipped@10']:.2f}  bm25@10 {r['bm25@10']:.2f}  {r['seconds']:4.1f}s  "
              f"ledger {js.SPENT['tokens']:,}  {r['task'][:50]}", flush=True)
    errors = [c["error"] for c in jev.calls if "error" in c]
    summary = {"repo": koog.REPO.name, "tasks": len(rows), "jev_calls": len(jev.calls), "jev_errors": len(errors),
               "error_samples": errors[:3], "files_median": st.median(r["n_files"] for r in rows),
               "seconds_median": st.median(r["seconds"] for r in rows),
               **{m: round(st.mean(r[m] for r in rows), 3) for m in rows[0] if "@" in m or m == "ceiling"},
               **{f"{a}-bm25@{k}": boot([r[f"{a}@{k}"] - r[f"bm25@{k}"] for r in rows]) for a in ("fused", "shipped") for k in (5, 10)}}
    print(json.dumps(summary, indent=1))
    (HERE / "results").mkdir(exist_ok=True)
    (HERE / "results" / f"second_repo_{koog.REPO.name}_t{len(rows)}.json").write_text(json.dumps({"summary": summary, "rows": rows}, indent=1))


if __name__ == "__main__":
    asyncio.run(main())
