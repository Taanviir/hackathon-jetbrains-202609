"""Stage 3: one comparative Jev `choice` over the current top K, to sharpen the very top of the ranking.

Passes 1 and 2 score each file independently. An LLM ranking a list compares them, and that's where it
beat us (recall@5). A Jev `choice` question over the top K files returns a probability per file, which is
a comparative judgement. Built on the saved rankings, so it costs one Jev call per task.

    uv run python stage3.py --split dev --k 10 --chars 6000      # choose on dev
    uv run python stage3.py --split test --k 10 --chars 6000     # then report once on test
"""

import argparse
import asyncio
import json
import statistics as st
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "spike"))
import fusion  # noqa: E402
import jev_spike as js  # noqa: E402
import koog  # noqa: E402

QUESTION = "Which file must be edited to implement the change described in `task`?"
RESULTS = HERE / "results"


async def ask(jev: js.Jev, task, ranked: list[str], files: dict[str, str], k: int, chars: int) -> dict[str, float]:
    top = ranked[:k]
    keys = {f"f{i:03d}": p for i, p in enumerate(top)}
    state = {"task": task.task, **{key: f"path: {p}\n{files[p][:chars]}" for key, p in keys.items()}}
    q = {"pick": {"type": "choice", "instructions": QUESTION, "criteria": {key: f"the file in `{key}` ({p})" for key, p in keys.items()}}}
    answers = await jev.ask(state, q)
    probs = getattr(answers.get("pick"), "probabilities", None) or {}
    return {keys[key]: float(v) for key, v in probs.items() if key in keys}


def rerank(d: dict, fused: list[str], probs: dict[str, float], k: int, how: str) -> list[str]:
    """Reorder only the top k; everything below keeps its place."""
    top, rest = fused[:k], fused[k:]
    if not probs:
        return fused
    if how == "choice":
        top = sorted(top, key=lambda p: -probs.get(p, 0))
    else:  # "add<a>": fused score plus a times the choice probability
        a = float(how.removeprefix("add"))
        bpos = {p: i for i, p in enumerate(d["bm25"])}
        fused_score = lambda p: d["s2"].get(p, 0) + 1.0 / (1 + bpos.get(p, 999) / 10)
        top = sorted(top, key=lambda p: -(fused_score(p) + a * probs.get(p, 0)))
    return top + rest


VARIANTS = ["choice", "add0.5", "add1", "add2"]


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--split", choices=["dev", "test"], required=True)
    ap.add_argument("--k", type=int, default=10)
    ap.add_argument("--chars", type=int, default=6000)
    ap.add_argument("--dev", type=int, default=40)
    args = ap.parse_args()

    dump = json.loads((koog.ROOT / ".cache" / "spike_runs" / "clean110.json").read_text())["rows"]
    rows = dump[:args.dev] if args.split == "dev" else dump[args.dev:]
    tasks = {t.sha[:8]: t for t in koog.load_tasks(limit=500)}
    jev = js.Jev("jev-latest", 16)

    async def one(row):
        d, t = row["dump"], tasks[row["sha"]]
        fused = fusion.variants(d)["blend_w1.0"]
        probs = await ask(jev, t, fused, koog.files_at(t.parent), args.k, args.chars)
        return {"sha": row["sha"], "truth": d["truth"], "fused": fused[:30], "probs": probs, "d": d}

    out = await asyncio.gather(*(one(r) for r in rows))
    ok = [o for o in out if o["probs"]]
    print(f"{args.split}: {len(ok)}/{len(out)} tasks answered, K={args.k}, {args.chars} chars, "
          f"{sum(c.get('in', 0) for c in jev.calls):,} tokens, ledger {js.SPENT['tokens']:,}")
    report = {"baseline": {k: st.mean(fusion.recall(o["fused"], o["truth"], k) for o in ok) for k in (5, 10)}}
    for how in VARIANTS:
        report[how] = {k: st.mean(fusion.recall(rerank(o["d"], o["fused"], o["probs"], args.k, how), o["truth"], k)
                               for o in ok) for k in (5, 10)}
    for name, r in report.items():
        print(f"  {name:10s} @5 {r[5]:.3f}  @10 {r[10]:.3f}")
    if args.split == "test":
        import random
        rng = random.Random(7)
        for how in VARIANTS:
            for k in (5, 10):
                per = [fusion.recall(rerank(o["d"], o["fused"], o["probs"], args.k, how), o["truth"], k)
                       - fusion.recall(o["fused"], o["truth"], k) for o in ok]
                means = sorted(st.mean(rng.choice(per) for _ in per) for _ in range(2000))
                report[f"{how}-baseline@{k}"] = [st.mean(per), means[50], means[1949]]
    RESULTS.mkdir(exist_ok=True)
    (RESULTS / f"stage3_{args.split}_k{args.k}_c{args.chars}.json").write_text(json.dumps({
        "report": report, "calls": len(jev.calls), "errors": [c["error"] for c in jev.calls if "error" in c][:3],
        "rows": [{k: v for k, v in o.items() if k != "d"} for o in out]}, indent=1))


if __name__ == "__main__":
    asyncio.run(main())
