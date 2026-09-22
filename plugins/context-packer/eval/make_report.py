"""Build the Context Packer eval report (reports/context-packer-eval/) from the saved result files.

Recall, bootstrap intervals and the agent A/B are computed here from the raw run files in .cache/ and
eval/results/, so every one of those numbers traces to data. Figures that only exist in logs (in-IDE
timings, the gateway probes, API limits) are constants below, each with its source.

    uv run python make_report.py
"""

import html
import json
import statistics as st
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT / "spike"))
import fusion  # noqa: E402

RUNS = ROOT / ".cache" / "spike_runs"
OUT = ROOT.parents[1] / "reports" / "context-packer-eval"

# ---------------------------------------------------------------- data from logs (not in run files)

LIMITS = [(60, "ok", "16.8k", "1.6 s"), (100, "ok", "27.9k", "0.8 s"), (150, "400 max_tokens_exceeded", "-", "-")]
IDE_TIMINGS = [  # idea.log "pack:" lines, Koog, 2,206 files
    ("Fresh IDE, Structure View sketches", 42_701, 2_864, 2_159, 47.7),
    ("Fresh IDE, regex sketches (shipped)", 1_388, 4_224, 1_015, 6.6),
    ("Warm, sketches cached", 70, 3_378, 864, 4.4),
    ("After warm-up on project open", 44, 3_141, 836, 4.1),
]
GATEWAY = [  # probes against ai-gateway.vercel.sh, 2026-09-22
    ("1 call, 60 sketches", "0 of 1 ok (503)"),
    ("4 concurrent", "1 of 4 ok (3 × 503)"),
    ("16 concurrent", "2 of 16 ok (9 × 503, 5 × 429)"),
    ("Sequential, 0.5 s apart, 2-60 files", "about half 429 at every size; 503 at 60"),
    ("Simulated 10-call pack", "28-62 s, ~70% of attempts failed before a retry landed"),
]
SPEND = [
    ("Spike and eval, first TypeSafe account", "126M", "$5.30", "Ran dry at task 111 of the 136-task run."),
    ("Agent A/B, Jev (ledger)", "5.3M", "$0.22", "Capped at 11M tokens by the ledger."),
    ("In-IDE checks, Jev", "2.6M", "$0.11", "Four packs across three IDE restarts (idea.log)."),
    ("Agent A/B rerun, Jev (ledger)", "5.3M", "$0.22", "Vague wording, same cap."),
    ("Agent A/Bs, GLM on OpenRouter", "-", "$0.53", "Both arms, both runs, 10 tasks each."),
    ("LLM re-ranker baseline, GLM", "2.3M", "$0.52", "70 tasks, under a $0.80 cap."),
]


def load(name):
    return json.loads((RUNS / name).read_text())


def summary(name):
    return load(name)["summary"]


# ---------------------------------------------------------------- numbers from run files

jev_alone = {b: summary(f"recall_jev-latest_b{b}_c16_t10.json") for b in (30, 100)}
tune40 = summary("hybrid_jev-latest_read_or_edit_top40_pc6_c6000_t20.json")
tune60 = summary("hybrid_jev-latest_read_or_edit_top60_pc6_c6000_t20.json")
first_holdout = summary("hybrid_jev-latest_read_or_edit_top60_pc6_c6000_o20_t30.json")

clean = [r["dump"] for r in load("clean110.json")["rows"]]
dev, test = clean[:40], clean[40:]
names = list(fusion.variants(clean[0]))
dev_rank = sorted(names, key=lambda n: (-fusion.score(dev, n, 10), -fusion.score(dev, n, 5)))
chosen = next(n for n in dev_rank if n not in ("bm25", "jev_sketch"))
T = {n: {k: fusion.score(test, n, k) for k in (5, 10, 20)} for n in ("bm25", "jev_rerank", chosen)}
CI = {k: fusion.bootstrap_diff(test, chosen, "bm25", k) for k in (5, 10)}
ceiling = st.mean(fusion.recall(t["pool"], t["truth"], len(t["pool"])) for t in test)


def skip_pass1(d, n):
    bpos = {p: i for i, p in enumerate(d["bm25"])}
    return sorted(d["bm25"][:n], key=lambda p: -(d["s2"].get(p, 0) + 1.0 / (1 + bpos.get(p, 999) / 10)))


SKIP = {n: {k: st.mean(fusion.recall(skip_pass1(d, n), d["truth"], k) for d in test) for k in (5, 10)} for n in (60, 40)}

ab = json.loads((HERE / "results" / "agent_ab_o40_t10.json").read_text())
AB = ab["rows"]
AB_VAGUE = json.loads((HERE / "results" / "agent_ab_o40_t10_vague.json").read_text())["rows"]
VAGUE_TASKS = json.loads((HERE / "results" / "vague_tasks.json").read_text())
LLM = json.loads((HERE / "results" / "rerank_llm_pool30.json").read_text())["summary"]


def ab_summary(rows):
    seen = lambda a: [r[a]["first_seen_s"] if r[a]["first_seen_s"] is not None else r[a]["wall_s"] for r in rows]
    return {a: {
        "recall": st.mean(r[a]["recall"] for r in rows), "wall": st.median(r[a]["wall_s"] for r in rows),
        "calls": st.mean(r[a]["llm_calls"] for r in rows),
        "tokens": st.mean(r[a]["prompt_tokens"] + r[a]["completion_tokens"] for r in rows),
        "cost": sum(r[a]["cost"] for r in rows), "seen": st.median(seen(a)),
        "pack_s": st.median(r[a]["pack_s"] for r in rows)} for a in ("explore", "pack")}


AB_SUM, AB_VSUM = ab_summary(AB), ab_summary(AB_VAGUE)

# ---------------------------------------------------------------- rendering helpers

e = html.escape


def pct(x):
    return f"{x:.2f}"


def bar_chart() -> str:
    """Grouped bars: recall@k on the 70 held-out tasks, BM25 against Context Packer."""
    W, H, L, B, TOP = 560, 260, 44, 36, 16
    ph, groups = H - B - TOP, [5, 10, 20]
    gw = (W - L - 12) / len(groups)
    bw, gap = 34, 2
    y = lambda v: TOP + ph * (1 - v)
    parts = [f'<svg viewBox="0 0 {W} {H}" role="img" aria-labelledby="c1t" class="chart">',
             '<title id="c1t">Recall at 5, 10 and 20 on 70 held-out tasks: BM25 against Context Packer</title>']
    for v in (0, .25, .5, .75, 1):
        parts.append(f'<line x1="{L}" x2="{W - 8}" y1="{y(v):.1f}" y2="{y(v):.1f}" class="grid"/>'
                     f'<text x="{L - 8}" y="{y(v) + 4:.1f}" class="tick" text-anchor="end">{v:.2f}</text>')
    for i, k in enumerate(groups):
        cx = L + gw * i + gw / 2
        for j, (key, cls, label) in enumerate((("bm25", "s2", "BM25"), (chosen, "s1", "Context Packer"))):
            v = T[key][k]
            x = cx - bw - gap / 2 if j == 0 else cx + gap / 2
            top, r = y(v), 4
            path = (f"M{x:.1f},{y(0):.1f} V{top + r:.1f} Q{x:.1f},{top:.1f} {x + r:.1f},{top:.1f} "
                    f"H{x + bw - r:.1f} Q{x + bw:.1f},{top:.1f} {x + bw:.1f},{top + r:.1f} V{y(0):.1f} Z")
            tip = f"{label} · recall@{k} {v:.3f}"
            parts.append(f'<g class="mark" data-tip="{e(tip)}"><rect x="{x - 4:.1f}" y="{TOP}" width="{bw + 8}" '
                         f'height="{ph}" class="hit"/><path d="{path}" class="{cls}"/>'
                         f'<text x="{x + bw / 2:.1f}" y="{top - 6:.1f}" class="val" text-anchor="middle">{v:.2f}</text></g>')
        parts.append(f'<text x="{cx:.1f}" y="{H - 12}" class="tick" text-anchor="middle">recall@{k}</text>')
    parts.append(f'<line x1="{L}" x2="{W - 8}" y1="{y(0):.1f}" y2="{y(0):.1f}" class="axis"/></svg>')
    return "".join(parts)


def ci_chart() -> str:
    """Improvement over BM25 with 95% paired-bootstrap intervals."""
    W, H, L, R = 560, 130, 90, 20
    lo_x, hi_x = -0.05, 0.30
    x = lambda v: L + (W - L - R) * (v - lo_x) / (hi_x - lo_x)
    parts = [f'<svg viewBox="0 0 {W} {H}" role="img" aria-labelledby="c2t" class="chart">',
             '<title id="c2t">Improvement over BM25 with 95% bootstrap intervals</title>']
    for v in (0, .1, .2, .3):
        parts.append(f'<line x1="{x(v):.1f}" x2="{x(v):.1f}" y1="10" y2="{H - 28}" class="{"zero" if v == 0 else "grid"}"/>'
                     f'<text x="{x(v):.1f}" y="{H - 10}" class="tick" text-anchor="middle">{v:+.1f}</text>')
    for i, k in enumerate((5, 10)):
        m, lo, hi = CI[k]
        cy = 34 + i * 38
        tip = f"recall@{k}: {m:+.3f}, 95% CI [{lo:+.3f}, {hi:+.3f}]"
        parts.append(f'<g class="mark" data-tip="{e(tip)}"><rect x="{L}" y="{cy - 14}" width="{W - L - R}" height="28" class="hit"/>'
                     f'<line x1="{x(lo):.1f}" x2="{x(hi):.1f}" y1="{cy}" y2="{cy}" class="whisker"/>'
                     f'<line x1="{x(lo):.1f}" x2="{x(lo):.1f}" y1="{cy - 6}" y2="{cy + 6}" class="whisker"/>'
                     f'<line x1="{x(hi):.1f}" x2="{x(hi):.1f}" y1="{cy - 6}" y2="{cy + 6}" class="whisker"/>'
                     f'<circle cx="{x(m):.1f}" cy="{cy}" r="5" class="dot"/></g>'
                     f'<text x="{L - 10}" y="{cy + 4}" class="tick" text-anchor="end">recall@{k}</text>')
    parts.append("</svg>")
    return "".join(parts)


def table(head, rows, cls=""):
    th = "".join(f"<th>{e(h)}</th>" for h in head)
    body = "".join("<tr>" + "".join(f"<td>{c}</td>" for c in r) + "</tr>" for r in rows)
    return f'<div class="tw"><table class="{cls}"><thead><tr>{th}</tr></thead><tbody>{body}</tbody></table></div>'


def b(s):
    return f"<strong>{s}</strong>"


# ---------------------------------------------------------------- page

fusion_rows = [[e(n) + (" ← chosen" if n == chosen else ""), pct(fusion.score(dev, n, 5)), pct(fusion.score(dev, n, 10))]
               for n in dev_rank[:6] + ["bm25"]]
ab_rows = []
for r in AB:
    ex, pk = r["explore"], r["pack"]
    fs = lambda a: f'{a["first_seen_s"]} s' if a["first_seen_s"] is not None else "never"
    ab_rows.append([e(r["task"][:64]), f'{ex["wall_s"]:.0f} s', f'{pk["wall_s"]:.0f} s', ex["llm_calls"], pk["llm_calls"],
                    fs(ex), fs(pk), pct(ex["recall"]), pct(pk["recall"])])
xs, pk = AB_SUM["explore"], AB_SUM["pack"]

page = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Context Packer eval</title>
<meta name="description" content="How Context Packer was measured: Jev plus BM25 file retrieval on held-out JetBrains/koog commits, and an agent A/B.">
<style>
:root {{
  --page: #f9f9f7; --surface: #fcfcfb; --ink: #0b0b0b; --ink-2: #52514e; --ink-3: #75746f;
  --rule: #e4e3de; --grid: #ecebe7; --s1: #2a78d6; --s2: #eb6834; --good: #0ca30c; --bad: #d03b3b;
  --code: #f0efec;
}}
@media (prefers-color-scheme: dark) {{ :root:not([data-theme="light"]) {{
  --page: #0d0d0d; --surface: #1a1a19; --ink: #ffffff; --ink-2: #c3c2b7; --ink-3: #9b9a92;
  --rule: #34332f; --grid: #2a2a28; --s1: #3987e5; --s2: #d95926; --code: #262624;
}} }}
:root[data-theme="dark"] {{
  --page: #0d0d0d; --surface: #1a1a19; --ink: #ffffff; --ink-2: #c3c2b7; --ink-3: #9b9a92;
  --rule: #34332f; --grid: #2a2a28; --s1: #3987e5; --s2: #d95926; --code: #262624;
}}
* {{ box-sizing: border-box; }}
body {{ margin: 0; background: var(--page); color: var(--ink); font: 16px/1.55 ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif; }}
main {{ max-width: 860px; margin: 0 auto; padding: 40px 16px 80px; }}
h1 {{ font-size: 30px; line-height: 1.2; margin: 0 0 8px; letter-spacing: -0.01em; }}
h2 {{ font-size: 20px; margin: 48px 0 8px; }}
h3 {{ font-size: 16px; margin: 24px 0 6px; }}
p, li {{ color: var(--ink-2); }} p strong, li strong {{ color: var(--ink); }}
.lede {{ font-size: 18px; color: var(--ink-2); margin: 0 0 20px; }}
.meta {{ font-size: 14px; color: var(--ink-3); }} .meta a {{ color: inherit; }}
a {{ color: var(--s1); }}
code {{ font: 13px/1.4 ui-monospace, SFMono-Regular, Menlo, monospace; background: var(--code); padding: 1px 5px; border-radius: 4px; }}
.card {{ background: var(--surface); border: 1px solid var(--rule); border-radius: 10px; padding: 20px; margin: 16px 0; }}
.tiles {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 12px; margin: 20px 0; }}
.tile {{ background: var(--surface); border: 1px solid var(--rule); border-radius: 10px; padding: 14px 16px; }}
.tile .n {{ font-size: 28px; font-weight: 650; font-variant-numeric: tabular-nums; }}
.tile .l {{ font-size: 13px; color: var(--ink-3); }}
.tw {{ overflow-x: auto; margin: 12px 0; }}
table {{ border-collapse: collapse; width: 100%; font-size: 14px; font-variant-numeric: tabular-nums; }}
th, td {{ text-align: left; padding: 7px 10px; border-bottom: 1px solid var(--rule); vertical-align: top; }}
th {{ color: var(--ink-3); font-weight: 600; font-size: 13px; }}
td:not(:first-child), th:not(:first-child) {{ text-align: right; }}
table.left td, table.left th {{ text-align: left; }}
.chart {{ width: 100%; height: auto; display: block; }}
.chart .grid {{ stroke: var(--grid); stroke-width: 1; }} .chart .axis {{ stroke: var(--ink-3); stroke-width: 1; }}
.chart .zero {{ stroke: var(--ink-3); stroke-width: 1; stroke-dasharray: 3 3; }}
.chart .tick {{ fill: var(--ink-3); font-size: 12px; }} .chart .val {{ fill: var(--ink-2); font-size: 12px; font-weight: 600; }}
.chart .s1 {{ fill: var(--s1); }} .chart .s2 {{ fill: var(--s2); }}
.chart .dot {{ fill: var(--s1); stroke: var(--surface); stroke-width: 2; }} .chart .whisker {{ stroke: var(--s1); stroke-width: 2; }}
.chart .hit {{ fill: transparent; }} .chart .mark:hover path, .chart .mark:hover .dot {{ opacity: .8; }}
.legend {{ display: flex; gap: 18px; font-size: 13px; color: var(--ink-2); margin: 0 0 6px; }}
.legend i {{ display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-right: 6px; vertical-align: -1px; }}
#tip {{ position: fixed; pointer-events: none; background: var(--ink); color: var(--page); font-size: 13px; padding: 6px 9px; border-radius: 6px; opacity: 0; transition: opacity .1s; }}
ol.timeline {{ padding-left: 20px; }} ol.timeline li {{ margin: 8px 0; }}
.flag {{ font-weight: 600; }} .flag.good {{ color: var(--good); }} .flag.bad {{ color: var(--bad); }}
.note {{ font-size: 14px; color: var(--ink-3); }}
</style>
</head>
<body>
<main>
<p class="meta"><a href="../../">All reports</a> · Context Packer · {date.today():%d %B %Y}</p>
<h1>Context Packer eval</h1>
<p class="lede">Can Jev, a model that answers typed questions but never writes text, find the files a coding task needs
better than keyword search, and does handing those files to an agent help it? Measured on real JetBrains/koog commits.</p>

<div class="tiles">
  <div class="tile"><div class="n">{pct(T[chosen][10])}</div><div class="l">recall@10, Context Packer<br>70 held-out tasks</div></div>
  <div class="tile"><div class="n">{pct(T["bm25"][10])}</div><div class="l">recall@10, BM25 keyword search<br>same tasks</div></div>
  <div class="tile"><div class="n">{CI[10][0]:+.2f}</div><div class="l">difference, 95% CI<br>[{CI[10][1]:+.2f}, {CI[10][2]:+.2f}]</div></div>
  <div class="tile"><div class="n">4.1 s</div><div class="l">one pack in the IDE<br>2,206 files, about $0.03</div></div>
</div>

<h2>The headline</h2>
<p>Each task is a real Koog commit subject. The right answer is the <code>.kt</code> files that commit modified, and
every file is read as it was <em>before</em> the commit so the answer can't leak in. The way BM25 and Jev are combined
was chosen on {len(dev)} dev tasks, then measured once on {len(test)} test tasks nobody had looked at.</p>
<div class="card">
  <div class="legend"><span><i style="background:var(--s1)"></i>Context Packer</span><span><i style="background:var(--s2)"></i>BM25</span></div>
  {bar_chart()}
  {table(["70 held-out tasks", "recall@5", "recall@10", "recall@20"], [
      ["BM25 keyword search", pct(T["bm25"][5]), pct(T["bm25"][10]), pct(T["bm25"][20])],
      ["Jev re-rank alone", pct(T["jev_rerank"][5]), pct(T["jev_rerank"][10]), pct(T["jev_rerank"][20])],
      [b("Context Packer (Jev + BM25)"), b(pct(T[chosen][5])), b(pct(T[chosen][10])), b(pct(T[chosen][20]))],
  ])}
</div>
<div class="card">
  <p style="margin:0 0 8px">Improvement over BM25, paired bootstrap over tasks. Neither interval touches zero.</p>
  {ci_chart()}
</div>

<h2>How it works</h2>
<ol>
  <li><strong>Sketch</strong> every source file into ~300 tokens: path, package, declarations and first doc lines.</li>
  <li><strong>Pass 1.</strong> Jev reads 60 sketches per call and gives, for each, P("implementing the task requires reading or editing this file"). BM25 ranks the full text alongside.</li>
  <li><strong>Pool</strong> the top 60 of each, then <strong>pass 2</strong>: Jev asks the same question over the pool's <em>full source</em>, 6 files per call.</li>
  <li><strong>Fuse</strong>: <code>score = jev + 1 / (1 + bm25_rank / 10)</code>, chosen on dev.</li>
</ol>

<h2>How we got here</h2>
<p>The number above is the end of a path with two wrong turns. Both are kept here, because they're why the final number is believable.</p>
<ol class="timeline">
  <li><strong>API limits.</strong> 100 sketches fit in one call (28k tokens, 0.8 s); 150 fail with <code>max_tokens_exceeded</code>. A full sweep of Koog is 18-37 calls.</li>
  <li><span class="flag bad">Jev alone loses to keyword search.</span> On sketches only, recall@10 was {pct(jev_alone[100]["jev@10"])} against BM25's {pct(jev_alone[100]["bm25@10"])} (10 tasks). Sketches hide what many tasks are about.</li>
  <li><span class="flag good">Re-ranking on full source wins, on the tuning set.</span> Pooling BM25 and Jev, then re-reading the pool in full, reached {pct(tune60["blend@10"])} against {pct(tune60["bm25@10"])} on 20 tasks.</li>
  <li><span class="flag bad">It didn't hold on new tasks.</span> On 30 held-out tasks with those settings: {pct(first_holdout["blend@10"])} against BM25's {pct(first_holdout["bm25@10"])}. Two causes: commits that mostly <em>add</em> files were in the set (nothing can find a file that doesn't exist yet), and the BM25 weight was tuned on too few tasks.</li>
  <li><strong>Fixed properly.</strong> We dropped add-dominated commits, ran the pipeline once over all 136 usable tasks saving every score, chose the fusion on dev only, and reported test once. The credits ran out at task 111; the 26 affected tasks are excluded, and none were in dev.</li>
  <li><span class="flag good">Held-out result:</span> {pct(T[chosen][10])} against {pct(T["bm25"][10])}, interval [{CI[10][1]:+.2f}, {CI[10][2]:+.2f}].</li>
</ol>

<h3>Choosing the fusion on dev</h3>
{table(["variant", "dev recall@5", "dev recall@10"], fusion_rows)}
<p class="note">The pool holds {ceiling:.0%} of the right files on test, so there's headroom left in the re-ranking.</p>

<h3>A cheaper variant: skip pass 1</h3>
<p>Pass 1 is two-thirds of the Jev calls. Scoring only BM25's top files with pass 2 costs little recall:</p>
{table(["test, 70 tasks", "recall@5", "recall@10", "Jev calls per pack"], [
    ["Full pipeline", pct(T[chosen][5]), pct(T[chosen][10]), "~56"],
    ["BM25 top 60 → pass 2", pct(SKIP[60][5]), pct(SKIP[60][10]), "10"],
    ["BM25 top 40 → pass 2", pct(SKIP[40][5]), pct(SKIP[40][10]), "7"],
    ["BM25 alone", pct(T["bm25"][5]), pct(T["bm25"][10]), "0"],
])}

<h2>Against an LLM re-ranker</h2>
<p>The obvious question: why not have an LLM re-rank BM25's shortlist? Same 70 test tasks, same pool (BM25's top
30), same full source per file. {e(LLM["model"])} read all 30 files in one call and listed the ones the task needs;
Jev's scores for the same 30 files were already saved.</p>
{table(["BM25 top 30, re-ranked by", "recall@5", "recall@10", "time per task", "cost per task"], [
    ["nothing (BM25 order)", pct(LLM["bm25@5"]), pct(LLM["bm25@10"]), "0", "0"],
    ["Jev + BM25", pct(LLM["jev_fused@5"]), pct(LLM["jev_fused@10"]), b("~1 s"), b("~$0.002")],
    [f"LLM ({e(LLM['model'])})", b(pct(LLM["llm@5"])), b(pct(LLM["llm@10"])), f'{LLM["llm_latency_s_median"]:.0f} s median',
     f'${LLM["llm_cost_total"] / LLM["tasks"]:.4f}'],
])}
<p><strong>The LLM picks the top five better; at ten they tie.</strong> Jev + BM25 minus LLM, paired bootstrap:
recall@5 {LLM["jev_fused-llm@5"][0]:+.2f} [{LLM["jev_fused-llm@5"][1]:+.2f}, {LLM["jev_fused-llm@5"][2]:+.2f}],
recall@10 {LLM["jev_fused-llm@10"][0]:+.2f} [{LLM["jev_fused-llm@10"][1]:+.2f}, {LLM["jev_fused-llm@10"][2]:+.2f}].
Jev's advantage is not judgement but economics: about 30× faster and 3.5× cheaper, which is what lets an agent call it
before every task. The shipped pipeline also pools Jev's own picks with BM25's, which this 30-file comparison leaves out.</p>

<h2>In the IDE</h2>
<p>Timings from the plugin's own log on Koog, 2,206 candidate files. The first version built sketches from the IDE's
Structure View, which runs Kotlin analysis at about 19 ms a file. The shipped version uses the regex sketcher the eval
measured (a parity test checks the Kotlin port against it).</p>
{table(["", "sketch", "pass 1 + BM25", "pass 2", "total"],
       [[e(n), f"{s / 1000:.1f} s", f"{p1 / 1000:.1f} s", f"{p2 / 1000:.1f} s", b(f"{t} s")] for n, s, p1, p2, t in IDE_TIMINGS])}
<p class="note">Every pack: 53-56 Jev calls, 0 failed, about 0.65M input tokens ($0.03). Since the warm-up pass, the
plugin sketches every file in the background when a project opens (11 s alongside indexing, no Jev calls).</p>

<h3>Why TypeSafe's API and not the free gateway</h3>
<p>Vercel's AI Gateway serves the same Jev model for free, but under load it mostly refused:</p>
{table(["probe", "result"], [[e(a), e(r)] for a, r in GATEWAY], "left")}
<p class="note">TypeSafe's own API ran more than 5,000 calls at 16 concurrent with no rate limiting. The plugin uses it by
default and keeps the gateway only as a fallback.</p>

<h2>Agent A/B</h2>
<p>The same agent (<code>z-ai/glm-5.3-flash</code>), prompt and tools (<code>list_dir</code>, <code>grep</code>,
<code>read_file</code>) on a real checkout of Koog at each task's parent commit, 10 held-out tasks. The only difference
is whether a <code>pack_context</code> tool exists. Every run ends when the agent submits its file list.</p>
{table(["10 tasks", "without pack_context", "with pack_context"], [
    ["Final recall", pct(xs["recall"]), pct(pk["recall"])],
    ["Wall time, median", f'{xs["wall"]:.0f} s', b(f'{pk["wall"]:.0f} s')],
    ["LLM calls, mean", f'{xs["calls"]:.1f}', b(f'{pk["calls"]:.1f}')],
    ["Tokens, mean", f'{xs["tokens"] / 1000:.0f}k', b(f'{pk["tokens"] / 1000:.0f}k')],
    ["LLM cost, 10 tasks", f'${xs["cost"]:.3f}', b(f'${pk["cost"]:.3f}')],
    ["Time to first right file, median", b(f'{xs["seen"]:.1f} s'), f'{pk["seen"]:.1f} s'],
])}
<p><strong>This result is mixed.</strong> With the packer the agent used fewer calls and tokens for the same recall. It was
<em>not</em> faster to the first right file: these commit subjects name identifiers, so the explorer's first
<code>grep</code> often hits at once, while the harness's Python <code>pack_context</code> took a median of 9.2 s (the plugin
takes 4.4 s). The agent also kept exploring after it had the pack. Ten tasks is too few to call any of these differences
significant.</p>
<h3>Rerun with vaguer wording</h3>
<p>Commit subjects often name the exact class or field, which hands <code>grep</code> the answer. So GLM rewrote the
same 10 tasks without any code identifiers, keeping product names a person would say (Ollama, Langfuse). For example,
<em>"{e(VAGUE_TASKS[2]["original"])}"</em> became <em>"{e(VAGUE_TASKS[2]["vague"])}"</em>.</p>
{table(["10 tasks, vague wording", "without pack_context", "with pack_context"], [
    ["Final recall", pct(AB_VSUM["explore"]["recall"]), pct(AB_VSUM["pack"]["recall"])],
    ["Wall time, median", b(f'{AB_VSUM["explore"]["wall"]:.0f} s'), f'{AB_VSUM["pack"]["wall"]:.0f} s'],
    ["LLM calls, mean", f'{AB_VSUM["explore"]["calls"]:.1f}', b(f'{AB_VSUM["pack"]["calls"]:.1f}')],
    ["Tokens, mean", f'{AB_VSUM["explore"]["tokens"] / 1000:.0f}k', b(f'{AB_VSUM["pack"]["tokens"] / 1000:.0f}k')],
    ["Time to first right file, median", b(f'{AB_VSUM["explore"]["seen"]:.1f} s'), f'{AB_VSUM["pack"]["seen"]:.1f} s'],
])}
<p>Vaguer wording cost <em>both</em> agents recall equally (0.97 to {pct(AB_VSUM["explore"]["recall"])}) and did not
favour the packer: product names still give <code>grep</code> a way in. Running BM25 alongside pass 1 cut the harness's
<code>pack_context</code> from {AB_SUM["pack"]["pack_s"]:.1f} s to {AB_VSUM["pack"]["pack_s"]:.1f} s, yet a grep-first
agent still reaches a right file sooner. <strong>Across both runs: the packer does not make this agent faster.</strong>
It saves some calls and tokens, and ten tasks can't separate that from noise.</p>
<details><summary>Per task</summary>
{table(["task", "wall, explore", "wall, pack", "calls, explore", "calls, pack", "first right file, explore", "first right file, pack", "recall, explore", "recall, pack"], ab_rows)}
</details>

<h2>What it cost</h2>
{table(["", "input tokens", "cost", "note"], [[e(a), t, c, e(n)] for a, t, c, n in SPEND])}
<p class="note">Jev lists at $0.042 per million input tokens, output free. After the first account ran dry, every eval run
checks a persistent ledger and stops at a token cap, and the plugin caps each IDE session at 20M tokens.</p>

<h2>Caveats</h2>
<ul>
  <li>One repository (Koog), one language (Kotlin). The sketcher handles other languages, but they aren't evaluated.</li>
  <li>Commit subjects stand in for tasks. Real requests are often vaguer, which should favour Jev over keywords, but that isn't measured.</li>
  <li>Recall counts only files the commit modified. A pick that's useful to read but wasn't edited counts as a miss.</li>
  <li>The agent A/B is 10 tasks, with one model, and that model keeps exploring after it has the pack.</li>
  <li>The LLM baseline is one model (GLM-5.3 Flash) on a 30-file pool. A stronger model would likely widen its lead
  at the top and its latency gap.</li>
</ul>
<p class="meta">Generated by <code>plugins/context-packer/eval/make_report.py</code> from the run files. Code and raw notes:
<code>plugins/context-packer/</code>, <code>spike/RESULTS.md</code>.</p>
</main>
<div id="tip" role="tooltip"></div>
<script>
(() => {{
  const tip = document.getElementById("tip");
  document.querySelectorAll(".mark").forEach(m => {{
    m.addEventListener("mousemove", ev => {{
      tip.textContent = m.dataset.tip; tip.style.opacity = 1;
      tip.style.left = Math.min(ev.clientX + 12, innerWidth - tip.offsetWidth - 8) + "px";
      tip.style.top = (ev.clientY + 14) + "px";
    }});
    m.addEventListener("mouseleave", () => tip.style.opacity = 0);
  }});
}})();
</script>
</body>
</html>
"""

OUT.mkdir(parents=True, exist_ok=True)
(OUT / "index.html").write_text(page)
(OUT / "meta.json").write_text(json.dumps({
    "title": "Context Packer eval",
    "author": "Taanviir",
    "date": date.today().isoformat(),
    "description": f"Jev + BM25 file retrieval on held-out Koog commits: recall@10 {T[chosen][10]:.2f} vs {T['bm25'][10]:.2f} for BM25. Plus an agent A/B.",
    "tags": ["context-packer", "eval", "jev"],
}, indent=1) + "\n")
print(f"wrote {OUT / 'index.html'}  (chosen fusion: {chosen}; test recall@10 {T[chosen][10]:.3f} vs {T['bm25'][10]:.3f})")
