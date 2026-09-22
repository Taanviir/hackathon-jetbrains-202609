"""Spike: how well, and how fast, does Jev pick the files a real Koog commit changed?

    python spike/jev_spike.py limits
    python spike/jev_spike.py recall --tasks 10 --batch 60 --model jev-latest
    python spike/jev_spike.py rerank --tasks 10 --batch 60
"""

import argparse
import asyncio
import json
import math
import os
import re
import statistics as st
import time
from collections import Counter
from pathlib import Path
from types import SimpleNamespace

import httpx

from dotenv import load_dotenv
from typesafe_sdk import AsyncTypeSafeClient, RetryPolicy

import koog

load_dotenv(koog.ROOT.parents[1] / ".env")
RUNS = koog.ROOT / ".cache" / "spike_runs"
RUNS.mkdir(parents=True, exist_ok=True)
SKETCH_CHARS = 1200

# ---------------------------------------------------------------- sketches

DECL = re.compile(r"^(?:[\w@]+(?:\([^)]*\))?\s+)*?(class|interface|object|fun|typealias|val|var)\b")


def sketch(path: str, src: str) -> str:
    """Path, package, and non-private declarations up to two levels deep, with first KDoc lines."""
    out, doc = [f"path: {path}"], None
    for raw in src.splitlines():
        line = raw.rstrip()
        s = line.strip()
        if s.startswith("package "):
            out.append(s)
        elif s.startswith("/**"):
            doc = s.removeprefix("/**").removesuffix("*/").strip() or None
        elif doc is None and s.startswith("* ") and out and out[-1] != "":
            pass
        indent = len(line) - len(line.lstrip())
        if indent > 8 or s.startswith(("private ", "//", "*", "import ")):
            if s.startswith("* ") and doc is None:
                doc = s[2:].strip()
            continue
        if DECL.match(s):
            sig = re.split(r"\s[{=]\s|\s\{$|\{$", s, maxsplit=1)[0]
            out.append("  " * (indent // 4) + "- " + sig + (f"  // {doc}" if doc else ""))
            doc = None
    text = "\n".join(out)
    return text[:SKETCH_CHARS]


# ---------------------------------------------------------------- BM25 baseline

WORD = re.compile(r"[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|\d+")


def tokens(text: str) -> list[str]:
    return [w.lower() for w in WORD.findall(text) if len(w) > 1]


def bm25_rank(task: str, files: dict[str, str], k1=1.2, b=0.75) -> list[str]:
    docs = {p: Counter(tokens(p + " " + s)) for p, s in files.items()}
    n, avg = len(docs), st.mean(sum(c.values()) for c in docs.values())
    df = Counter(t for c in docs.values() for t in c)
    q = set(tokens(task))
    def score(c):
        ln = sum(c.values())
        return sum(math.log(1 + (n - df[t] + .5) / (df[t] + .5)) * c[t] * (k1 + 1)
                   / (c[t] + k1 * (1 - b + b * ln / avg)) for t in q if t in c)
    return sorted(docs, key=lambda p: -score(docs[p]))


# ---------------------------------------------------------------- Jev

GATEWAY_URL = "https://ai-gateway.vercel.sh/v4/ai/evaluation-model"
# Hard stop so no run can quietly spend someone's credits. The ledger persists across runs, so
# restarting a script doesn't reset it. Delete the file only on purpose, e.g. after a top-up.
LEDGER = koog.ROOT / ".cache" / "jev_ledger.json"
TOKEN_BUDGET = int(os.environ.get("JEV_TOKEN_BUDGET", 24_000_000))  # about $1.00 at $0.042/M


def _load_ledger() -> dict:
    try:
        return json.loads(LEDGER.read_text())
    except (OSError, ValueError):
        return {"tokens": 0, "calls": 0}


SPENT = _load_ledger()


def _record(tokens: int):
    SPENT["tokens"] += tokens
    SPENT["calls"] = SPENT.get("calls", 0) + 1
    LEDGER.parent.mkdir(parents=True, exist_ok=True)
    LEDGER.write_text(json.dumps(SPENT))


class BudgetExceeded(RuntimeError):
    pass


class Jev:
    """TypeSafe's own API. Vercel AI Gateway only with JEV_BACKEND=gateway (it's heavily rate-limited). Never OpenRouter."""

    def __init__(self, model: str, concurrency: int):
        use_gateway = os.environ.get("JEV_BACKEND", "").lower() == "gateway"
        self.gateway_key = (os.environ.get("AI_GATEWAY_API_KEY", "").strip() or None) if use_gateway else None
        if self.gateway_key:
            self.model = {"jev-latest": "typesafe-ai/jev"}.get(model, model)
            self.http = httpx.AsyncClient(timeout=30.0, headers={
                "Authorization": f"Bearer {self.gateway_key}", "ai-gateway-protocol-version": "0.0.1",
                "ai-gateway-auth-method": "api-key", "ai-evaluation-model-specification-version": "4",
                "ai-model-id": self.model})
        else:
            self.model = model
            self.client = AsyncTypeSafeClient(model=model, timeout=30.0, retry=RetryPolicy(max_retries=4, timeout=90.0))
        self.sem = asyncio.Semaphore(concurrency)
        self.calls: list[dict] = []

    async def _gateway(self, state: dict, questions: dict):
        qs = {k: {**q, "type": "boolean"} if q.get("type") == "noul" else q for k, q in questions.items()}
        for attempt in range(5):
            r = await self.http.post(GATEWAY_URL, json={"state": state, "questions": qs})
            if r.status_code == 200:
                body = r.json()
                answers = {k: SimpleNamespace(noul=a.get("probability"), probabilities=a.get("probabilities"), choice=a.get("choice"))
                           for k, a in body.get("answers", {}).items()}
                usage = body.get("usage") or {}
                return answers, usage.get("inputTokens", 0), self.model
            if r.status_code not in (408, 429) and r.status_code < 500:
                raise RuntimeError(f"gateway HTTP {r.status_code}: {r.text[:200]}")
            await asyncio.sleep(float(r.headers.get("retry-after", 0.5 * 2 ** attempt)))
        raise RuntimeError(f"gateway gave up: HTTP {r.status_code}: {r.text[:200]}")

    async def ask(self, state: dict, questions: dict) -> dict:
        async with self.sem:
            t0 = time.perf_counter()
            try:
                if SPENT["tokens"] >= TOKEN_BUDGET:
                    raise BudgetExceeded(f"Jev budget of {TOKEN_BUDGET:,} tokens reached ({LEDGER}); "
                                         "raise JEV_TOKEN_BUDGET deliberately to continue")
                if self.gateway_key:
                    answers, tokens, model = await self._gateway(state, questions)
                else:
                    r = await self.client.system_one(state=state, questions=questions)
                    answers, tokens, model = r.answers, r.usage.input_tokens, r.model
                _record(tokens or 0)
                self.calls.append({"ms": (time.perf_counter() - t0) * 1000, "in": tokens or 0,
                                   "q": len(questions), "model": model})
                return answers
            except Exception as e:  # recorded, not fatal: one failed batch shouldn't sink a pass
                self.calls.append({"ms": (time.perf_counter() - t0) * 1000, "error": f"{type(e).__name__}: {e}"[:300],
                                   "q": len(questions)})
                return {}


QUESTIONS = {
    "read_or_edit": "Implementing the change described in `task` requires reading or editing the file in `{key}`.",
    "edit": "Implementing the change described in `task` requires editing the code in `{key}`.",
}
QUESTION = "read_or_edit"


def relevance_question(key: str) -> dict:
    return {"type": "noul", "instructions": QUESTIONS[QUESTION].format(key=key)}


async def score_pass1(jev: Jev, task: str, sketches: dict[str, str], batch: int) -> dict[str, float]:
    paths = list(sketches)
    groups = [paths[i:i + batch] for i in range(0, len(paths), batch)]
    async def one(group):
        keys = {f"f{i:03d}": p for i, p in enumerate(group)}
        state = {"task": task, **{k: sketches[p] for k, p in keys.items()}}
        answers = await jev.ask(state, {k: relevance_question(k) for k in keys})
        return {p: getattr(answers.get(k), "noul", 0.0) or 0.0 for k, p in keys.items()}
    scores = {}
    for part in await asyncio.gather(*(one(g) for g in groups)):
        scores.update(part)
    return scores


ROLES = {
    "edit": "The change is implemented by editing this file.",
    "test": "This file tests the code being changed and would need updating.",
    "example": "This file shows an existing pattern the change should follow, but is not edited.",
    "dependency": "The change calls or implements an API declared here, but this file is not edited.",
    "unrelated": "This file has nothing to do with the change.",
}


async def score_pass2(jev: Jev, task: str, files: dict[str, str], top: list[str], per_call: int) -> dict[str, dict]:
    groups = [top[i:i + per_call] for i in range(0, len(top), per_call)]
    async def one(group):
        keys = {f"f{i:03d}": p for i, p in enumerate(group)}
        state = {"task": task, **{k: f"path: {p}\n{files[p][:6000]}" for k, p in keys.items()}}
        qs = {k: {"type": "choice", "instructions": f"What role does the file in `{k}` play in the change described in `task`?",
                  "criteria": ROLES} for k in keys}
        answers = await jev.ask(state, qs)
        return {p: (getattr(answers.get(k), "probabilities", None) or {}) for k, p in keys.items()}
    out = {}
    for part in await asyncio.gather(*(one(g) for g in groups)):
        out.update(part)
    return out


# ---------------------------------------------------------------- metrics

def recall(ranked: list[str], truth: list[str], k: int) -> float:
    return len(set(ranked[:k]) & set(truth)) / len(truth)


def call_stats(calls: list[dict]) -> dict:
    ok = [c for c in calls if "error" not in c]
    ms = sorted(c["ms"] for c in ok) or [0]
    return {"calls": len(calls), "errors": len(calls) - len(ok),
            "p50_ms": round(ms[len(ms) // 2]), "p95_ms": round(ms[int(.95 * (len(ms) - 1))]),
            "input_tokens": sum(c["in"] for c in ok), "models": sorted({c["model"] for c in ok}),
            "error_samples": [c["error"] for c in calls if "error" in c][:3]}


# ---------------------------------------------------------------- experiments

async def score_full(jev: Jev, task: str, files: dict[str, str], pool: list[str], per_call: int, chars: int) -> dict[str, float]:
    """Pass 2: the same relevance question, but over full source for a small pool."""
    return await score_pass1(jev, task, {p: f"path: {p}\n{files[p][:chars]}" for p in pool}, per_call)


async def cmd_hybrid(args):
    """Pool = BM25 top K plus Jev-on-sketches top K, then Jev re-ranks the pool on full source."""
    tasks = koog.load_tasks(limit=args.offset + args.tasks)[args.offset:]
    jev = Jev(args.model, args.concurrency)
    rows = []
    for t in tasks:
        files = koog.files_at(t.parent)
        sk = {p: sketch(p, s) for p, s in files.items()}
        t0 = time.perf_counter()
        s1 = await score_pass1(jev, t.task, sk, args.batch)
        t1 = time.perf_counter()
        jr = sorted(s1, key=lambda p: -s1[p])
        br = bm25_rank(t.task, files)
        pool = list(dict.fromkeys(br[:args.top] + jr[:args.top]))
        t2 = time.perf_counter()
        s2 = await score_full(jev, t.task, files, pool, args.per_call, args.chars)
        t3 = time.perf_counter()
        rr = sorted(pool, key=lambda p: -s2.get(p, 0))
        # blend keeps BM25's signal as a tie-breaker for files Jev scores alike
        bpos = {p: i for i, p in enumerate(br)}
        blend = sorted(pool, key=lambda p: -(s2.get(p, 0) + 0.15 / (1 + bpos.get(p, 999) / 10)))
        row = {"sha": t.sha[:8], "pass1_s": round(t1 - t0, 2), "bm25_s": round(t2 - t1, 2), "pass2_s": round(t3 - t2, 2),
               "pool": len(pool), "ceiling": recall(pool, t.truth, len(pool)),
               **{f"bm25@{k}": recall(br, t.truth, k) for k in (5, 10)},
               **{f"jev1@{k}": recall(jr, t.truth, k) for k in (5, 10)},
               **{f"rerank@{k}": recall(rr, t.truth, k) for k in (5, 10)},
               **{f"blend@{k}": recall(blend, t.truth, k) for k in (5, 10)}}
        row["dump"] = {"truth": t.truth, "bm25": br[:300], "pool": pool,
                       "s1": {p: s1[p] for p in pool}, "s2": {p: s2.get(p, 0.0) for p in pool},
                       "s1_rank": {p: jr.index(p) + 1 for p in pool}}
        rows.append(row)
        print(f"{row['sha']}  bm25@10={row['bm25@10']:.2f} jev1@10={row['jev1@10']:.2f} rerank@10={row['rerank@10']:.2f} "
              f"blend@10={row['blend@10']:.2f} ceil={row['ceiling']:.2f} pool={len(pool)}  "
              f"{row['pass1_s']}+{row['pass2_s']}s  {t.task[:40]}")
    summary = {"model": args.model, "question": QUESTION, "batch": args.batch, "top": args.top, "per_call": args.per_call,
               "chars": args.chars, "tasks": len(rows),
               **{m: round(st.mean(r[m] for r in rows), 3) for m in rows[0] if "@" in m or m == "ceiling"},
               "pass1_s_p50": st.median(r["pass1_s"] for r in rows), "pass2_s_p50": st.median(r["pass2_s"] for r in rows),
               **call_stats(jev.calls)}
    print(json.dumps({k: v for k, v in summary.items() if k != "error_samples"}), summary["error_samples"])
    (RUNS / f"hybrid_{args.model}_{QUESTION}_top{args.top}_pc{args.per_call}_c{args.chars}_o{args.offset}_t{args.tasks}.json").write_text(
        json.dumps({"summary": summary, "rows": rows}, indent=1))


async def cmd_limits(args):
    task = koog.load_tasks(limit=1)[0]
    files = koog.files_at(task.parent)
    sk = {p: sketch(p, s) for p, s in files.items()}
    paths = task.truth + [p for p in sk if p not in task.truth]
    jev = Jev(args.model, 1)
    for n in (60, 100, 150, 250):
        before = len(jev.calls)
        await score_pass1(jev, task.task, {p: sk[p] for p in paths[:n]}, batch=n)
        c = jev.calls[before]
        print(f"batch={n:4d} questions={c['q']:4d}  " + (f"ERROR {c['error']}" if "error" in c
              else f"ok {c['ms']:.0f} ms, {c['in']} input tokens, model {c['model']}"))
    print("sketch chars p50/p90:", st.median(map(len, sk.values())), sorted(map(len, sk.values()))[int(.9 * len(sk))])


async def cmd_recall(args):
    tasks = koog.load_tasks(limit=args.tasks)
    jev = Jev(args.model, args.concurrency)
    rows = []
    for t in tasks:
        files = koog.files_at(t.parent)
        sk = {p: sketch(p, s) for p, s in files.items()}
        t0 = time.perf_counter()
        scores = await score_pass1(jev, t.task, sk, args.batch)
        wall = time.perf_counter() - t0
        jr = sorted(scores, key=lambda p: -scores[p])
        br = bm25_rank(t.task, files)
        row = {"sha": t.sha[:8], "task": t.task, "n_truth": len(t.truth), "wall_s": round(wall, 2),
               **{f"jev@{k}": recall(jr, t.truth, k) for k in (5, 10, 20)},
               **{f"bm25@{k}": recall(br, t.truth, k) for k in (5, 10, 20)},
               "truth_ranks": {p: (jr.index(p) + 1 if p in scores else None) for p in t.truth}}
        rows.append(row)
        print(f"{row['sha']}  jev@10={row['jev@10']:.2f} bm25@10={row['bm25@10']:.2f}  {wall:5.1f}s  {t.task[:60]}")
    summary = {"model": args.model, "batch": args.batch, "concurrency": args.concurrency, "tasks": len(rows),
               **{m: round(st.mean(r[m] for r in rows), 3) for m in rows[0] if "@" in m},
               "wall_s_p50": st.median(r["wall_s"] for r in rows), **call_stats(jev.calls)}
    print(json.dumps(summary, indent=1))
    name = f"recall_{args.model}_b{args.batch}_c{args.concurrency}_t{args.tasks}.json"
    (RUNS / name).write_text(json.dumps({"summary": summary, "rows": rows}, indent=1))


async def cmd_rerank(args):
    tasks = koog.load_tasks(limit=args.tasks)
    jev = Jev(args.model, args.concurrency)
    rows = []
    for t in tasks:
        files = koog.files_at(t.parent)
        sk = {p: sketch(p, s) for p, s in files.items()}
        t0 = time.perf_counter()
        scores = await score_pass1(jev, t.task, sk, args.batch)
        top = sorted(scores, key=lambda p: -scores[p])[:args.top]
        t1 = time.perf_counter()
        roles = await score_pass2(jev, t.task, files, top, args.per_call)
        t2 = time.perf_counter()
        rel = {p: 1 - roles.get(p, {}).get("unrelated", 1.0) for p in top}
        edit = {p: roles.get(p, {}).get("edit", 0) + roles.get(p, {}).get("test", 0) for p in top}
        r1 = top
        r_rel = sorted(top, key=lambda p: -rel[p])
        r_edit = sorted(top, key=lambda p: -edit[p])
        row = {"sha": t.sha[:8], "pass1_s": round(t1 - t0, 2), "pass2_s": round(t2 - t1, 2),
               **{f"p1@{k}": recall(r1, t.truth, k) for k in (5, 10)},
               **{f"rel@{k}": recall(r_rel, t.truth, k) for k in (5, 10)},
               **{f"edit@{k}": recall(r_edit, t.truth, k) for k in (5, 10)},
               "ceiling": recall(top, t.truth, args.top),
               "truth_roles": {p: max(roles[p], key=roles[p].get) if roles.get(p) else None for p in t.truth}}
        rows.append(row)
        print(f"{row['sha']}  p1@10={row['p1@10']:.2f} rel@10={row['rel@10']:.2f} edit@10={row['edit@10']:.2f} "
              f"ceil={row['ceiling']:.2f}  p1 {row['pass1_s']}s p2 {row['pass2_s']}s  {t.task[:50]}")
    summary = {"model": args.model, "batch": args.batch, "top": args.top, "per_call": args.per_call, "tasks": len(rows),
               **{m: round(st.mean(r[m] for r in rows), 3) for m in rows[0] if "@" in m or m == "ceiling"},
               "pass1_s_p50": st.median(r["pass1_s"] for r in rows), "pass2_s_p50": st.median(r["pass2_s"] for r in rows),
               **call_stats(jev.calls)}
    print(json.dumps(summary, indent=1))
    (RUNS / f"rerank_{args.model}_b{args.batch}_top{args.top}_t{args.tasks}.json").write_text(
        json.dumps({"summary": summary, "rows": rows}, indent=1))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["limits", "recall", "rerank", "hybrid"])
    ap.add_argument("--model", default="jev-latest")
    ap.add_argument("--tasks", type=int, default=10)
    ap.add_argument("--offset", type=int, default=0, help="skip the first N tasks, e.g. the ones used for tuning")
    ap.add_argument("--batch", type=int, default=60)
    ap.add_argument("--concurrency", type=int, default=16)
    ap.add_argument("--top", type=int, default=30)
    ap.add_argument("--per-call", type=int, default=6)
    ap.add_argument("--chars", type=int, default=6000)
    ap.add_argument("--question", choices=list(QUESTIONS), default="read_or_edit")
    args = ap.parse_args()
    global QUESTION
    QUESTION = args.question
    asyncio.run({"limits": cmd_limits, "recall": cmd_recall, "rerank": cmd_rerank, "hybrid": cmd_hybrid}[args.cmd](args))


if __name__ == "__main__":
    main()
