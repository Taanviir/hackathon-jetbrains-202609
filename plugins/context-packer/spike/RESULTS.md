# Spike results

Measured 2026-09-22 against the TypeSafe API directly, model `jev-latest` (served as
`jev-1.13.0`). Tasks are real JetBrains/koog commits: the task is the commit subject, the
right answer is the `.kt` files that commit modified, and every file is read as it was at the
parent commit so the answer can't leak in. 1,705 candidate files per task.

Reproduce with `uv run python jev_spike.py <cmd>` from this directory.

## Limits

| Files per call | Result |
| --- | --- |
| 60 | ok, 16.8k input tokens, 1.6 s |
| 100 | ok, 27.9k input tokens, 0.8 s |
| 150 | `400 max_tokens_exceeded` |

The ceiling is roughly 32k tokens of state. 100 sketches per call fits with headroom, so a
full sweep of Koog is 18 calls. No errors or rate limiting at 16 concurrent calls across
more than 2,000 calls in total.

## Jev alone does not beat keyword search

Jev scoring 300-token sketches of every file, 10 tasks:

| | recall@5 | recall@10 | recall@20 | full pass |
| --- | --- | --- | --- | --- |
| Jev on sketches, 30 per call | 0.30 | 0.35 | 0.49 | 2.1 s |
| Jev on sketches, 100 per call | 0.32 | 0.37 | 0.47 | 1.7 s |
| BM25 on full source | 0.35 | 0.46 | 0.56 | |

Sketches hide what a task is often about. "Map cachedContentTokenCount from Google usage
metadata" hinges on a field inside a method body, which BM25 sees and a sketch doesn't.

## Jev as a re-ranker does

Pool BM25's top K with Jev-on-sketches' top K, then ask Jev the same question again over the
**full source** of just that pool, 6 files per call. The pool is what makes full source
affordable.

20 tasks:

| | recall@5 | recall@10 | pool ceiling | time |
| --- | --- | --- | --- | --- |
| BM25 | 0.42 | 0.47 | | |
| Jev re-rank, pool 40 | 0.42 | 0.61 | 0.79 | 1.7 + 0.9 s |
| **Jev re-rank + BM25 tie-break, pool 60** | **0.47** | **0.64** | **0.85** | **1.7 + 1.0 s** |
| same, `jev-preview` | 0.48 | 0.61 | 0.78 | 1.7 + 0.9 s |

About 30% more of the right files land in the top 10 than with BM25, in under 3 seconds
end to end.

## Held-out evaluation (the number to quote)

The tables above tuned pool size, wording and fusion on the first 20 tasks, so they flatter the
pipeline. A first check on 30 unseen tasks with those settings did **not** beat BM25 at recall@10
(0.51 vs 0.56). Two problems, both fixed before the run below:

- Commits that mostly add new files were in the set. Nothing can retrieve a file that doesn't
  exist yet, so the task filter now drops commits with more added than modified `.kt` files.
- The BM25 weight was tuned on too few tasks.

Protocol: run the pipeline once over all 136 usable tasks and save every score
(`jev_spike.py hybrid`), choose the fusion on the first 40 (dev) only with `fusion.py`, then report
it once on the rest (test). The TypeSafe credits ran out at task 111, so the last 26 have partial
Jev scores and are excluded. That leaves 40 dev and **70 test** tasks, all complete.

Chosen on dev: `score = jev + 1.0 / (1 + bm25_rank / 10)`, equal weight.

| 70 test tasks | recall@5 | recall@10 | recall@20 |
| --- | --- | --- | --- |
| BM25 | 0.42 | 0.53 | 0.63 |
| Jev re-rank alone | 0.52 | 0.63 | 0.74 |
| **Jev + BM25** | **0.54** | **0.69** | **0.80** |

Paired bootstrap over tasks, Jev + BM25 minus BM25: recall@5 **+0.12** [+0.07, +0.18],
recall@10 **+0.16** [+0.10, +0.23] (95% intervals). The pool's ceiling is 0.90.

Pass 1 took 1.5 s and pass 2 took 1.0 s (medians), at 60 sketches per call.

## Decisions for the plugin

- Pipeline: sketch every file, then Jev pass 1 at 60 files per call, BM25 over full text in
  parallel, a pool of their top 60 each, and Jev pass 2 on full source (6,000 chars) at 6
  files per call. The final rank fuses the pass-2 score with BM25 rank at equal weight.
- Question wording: "Implementing the change described in `task` requires reading or
  editing the file in `fNNN`." The narrower "requires editing" scored worse (0.59 against 0.66
  at pool 40).
- Model: `jev-latest`. `jev-preview` currently returns the same `jev-1.13.0` and scores the same.
- Roles: tests come from the path (`/test/`, `*Test.kt`), which is deterministic and free. A
  Jev role question stays optional.
- Concurrency 16. Retries at 429 and 5xx, honouring `retry-after`.
