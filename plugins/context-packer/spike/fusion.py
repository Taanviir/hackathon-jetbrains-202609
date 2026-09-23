"""Choose how to combine BM25 and Jev on a dev split, then report it once on an untouched test split.

    uv run python fusion.py ../.cache/spike_runs/hybrid_..._o0_t136.json --dev 40
"""

import argparse
import json
import random
import statistics as st


def recall(ranked, truth, k):
    return len(set(ranked[:k]) & set(truth)) / len(truth)


def variants(d: dict) -> dict[str, list[str]]:
    """Every candidate ranking for one task, built from the saved scores. No new Jev calls."""
    truth, bm25, pool, s1, s2 = d["truth"], d["bm25"], d["pool"], d["s1"], d["s2"]
    bpos = {p: i for i, p in enumerate(bm25)}
    b = lambda p: bpos.get(p, 999)
    s2_rank = {p: i for i, p in enumerate(sorted(pool, key=lambda p: -s2[p]))}
    s1_rank = {p: i for i, p in enumerate(sorted(pool, key=lambda p: -s1[p]))}
    out = {"bm25": bm25, "jev_sketch": sorted(pool, key=lambda p: -s1[p]), "jev_rerank": sorted(pool, key=lambda p: -s2[p])}
    for w in (0.05, 0.15, 0.3, 0.5, 1.0):
        out[f"blend_w{w}"] = sorted(pool, key=lambda p: -(s2[p] + w / (1 + b(p) / 10)))
    for k in (5, 10, 30, 60):
        out[f"rrf_k{k}"] = sorted(pool, key=lambda p: -(1 / (k + b(p)) + 1 / (k + s2_rank[p])))
        out[f"rrf3_k{k}"] = sorted(pool, key=lambda p: -(1 / (k + b(p)) + 1 / (k + s2_rank[p]) + 1 / (k + s1_rank[p])))
    return out


def score(tasks: list[dict], name: str, k: int) -> float:
    return st.mean(recall(variants(t)[name], t["truth"], k) for t in tasks)


def bootstrap_diff(tasks, a, b, k, n=2000, seed=7):
    """Paired bootstrap over tasks: 95% interval for recall@k(a) - recall@k(b)."""
    rng = random.Random(seed)
    per = [recall(variants(t)[a], t["truth"], k) - recall(variants(t)[b], t["truth"], k) for t in tasks]
    means = sorted(st.mean(rng.choice(per) for _ in per) for _ in range(n))
    return st.mean(per), means[int(.025 * n)], means[int(.975 * n)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dump")
    ap.add_argument("--dev", type=int, default=40)
    args = ap.parse_args()
    rows = [r["dump"] for r in json.load(open(args.dump))["rows"]]
    dev, test = rows[:args.dev], rows[args.dev:]
    names = list(variants(rows[0]))

    print(f"DEV ({len(dev)} tasks), used only to choose")
    ranked = sorted(names, key=lambda n: (-score(dev, n, 10), -score(dev, n, 5)))
    for n in ranked:
        print(f"  {n:14s} @5 {score(dev, n, 5):.3f}  @10 {score(dev, n, 10):.3f}")
    best = next(n for n in ranked if n not in ("bm25", "jev_sketch"))
    print(f"\nchosen on dev: {best}")

    print(f"\nTEST ({len(test)} tasks), reported once")
    for n in ["bm25", "jev_sketch", "jev_rerank", best]:
        print(f"  {n:14s} @5 {score(test, n, 5):.3f}  @10 {score(test, n, 10):.3f}  @20 {score(test, n, 20):.3f}")
    for k in (5, 10):
        m, lo, hi = bootstrap_diff(test, best, "bm25", k)
        print(f"  {best} - bm25 @{k}: {m:+.3f}  95% CI [{lo:+.3f}, {hi:+.3f}]")
    ceiling = st.mean(recall(t["pool"], t["truth"], len(t["pool"])) for t in test)
    print(f"  pool ceiling {ceiling:.3f}")


if __name__ == "__main__":
    main()
