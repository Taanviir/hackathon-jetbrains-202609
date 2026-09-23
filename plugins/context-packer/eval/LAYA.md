# Local Laya retrieval benchmark

This benchmark evaluates the Laya adapter against real JetBrains/koog history. The
task is a commit subject. The relevant files are the Kotlin files that commit
modified. Every candidate is read from the parent commit, before the change.
It makes no paid API calls.

## Results

The frozen two-pass Laya configuration underperformed full-source BM25 on this Koog source-retrieval proxy. Across **30 held-out tasks**, mean recall@10 was **0.391** for fixed Laya+BM25 versus **0.553** for full-source BM25. The 25-task extension was fixed before evaluation and showed the same direction. These results support keeping full-source BM25 available as a fast local option; they do not establish quality on other repositories or in an end-to-end IDE workflow.

| Ranking | Next 25 recall@5 | Next 25 recall@10 | All 30 recall@5 | All 30 recall@10 |
| --- | ---: | ---: | ---: | ---: |
| Full-source BM25, 0 model calls | 0.405 | 0.555 | 0.410 | 0.553 |
| BM25 reranked within top 60, 0 model calls | 0.224 | 0.427 | 0.242 | 0.428 |
| Fixed two-pass Laya+BM25 | 0.252 | 0.382 | 0.265 | 0.391 |
| Laya pass 2 only | 0.036 | 0.103 | 0.047 | 0.114 |

As a **secondary uncertainty analysis** of the unchanged 30-task held-out result, the paired mean difference in recall@10 (fixed Laya+BM25 minus full-source BM25) was **−0.162**. A 10,000-resample whole-task bootstrap with fixed seed 20260923 gave a 95% percentile interval of **[−0.269, −0.070]**; Laya improved 0 tasks, tied 20, and worsened 10. This recomputes exact per-task recall from saved rankings and truth, before four-decimal rounding, and made no new model calls or tuning decisions. The tasks share one repository and period, so this interval describes variation within that sample rather than broader generalization. See the [method sheet](results/laya/laya-benchmark-uncertainty.md) and [task-level audit JSON](results/laya/laya-benchmark-uncertainty.json.gz).

Each task is a real `fix`, `feat`, `refactor`, or `perf` commit subject from the official [JetBrains/koog](https://github.com/JetBrains/koog) `develop` history at `5e81c2277ecccdc8dcfa0c03be7865062a6ea956`. Truth files are the modified Kotlin files in that commit; all candidate contents come from its **parent** snapshot. The first 3 eligible commits were development tasks, the next 5 were the original held-out set, and the next 25 were frozen in the [extension manifest](results/laya/laya-benchmark-extension-manifest.json.gz) before any extension calls. One otherwise eligible commit, `f3a8aa5a`, was excluded before splitting because a truth file exceeded the plugin's 100,000-byte candidate cap. No task was excluded for poor quality or latency. The task selection, parent and truth files, cap, and retrieval settings were validated against the manifest before the extension started.

The harness mirrors the evaluated plugin configuration: full-source BM25 selects 60 candidates; BM25 is recomputed within those 60; Laya scores one regex sketch per file; the top 20 from local BM25 and the top 20 from sketch Laya form a pool; Laya scores each pooled file again using the first 1,000 source characters; the final score adds the Laya probability to a local BM25 rank bonus at weight 1.0. Ties use lexicographic paths. Every Laya request uses one file, the English checkpoint, and one `noul` relevance question. The development split selected shortlist BM25 with **zero** model calls by recall@10, then recall@5, then fewer calls. The fixed two-pass Laya result is reported regardless of that selection. A separate 120-call development-only task-window experiment did not improve development recall and used no held-out labels.

The extension made **2,329 successful local requests** and consumed **812,274 reported input tokens** across 25 tasks, with **zero errors**. Median/95th-percentile round-trip latency was **1,315/1,560 ms per call**, and the two inference passes averaged **117.2 seconds per task** on CPU. The combined eight-task original run plus extension made **3,085 protocol requests** and consumed **1,090,106 input tokens**. Local API fee was **$0**; electricity, hardware, and developer time are excluded. The extension timings came from the portable persistent-worker server without concurrent IDE/Gradle load. The original five held-out timings included IDE contention and two server implementations, so the extension is the cleaner latency sample. The [25-task sheet](results/laya/laya-benchmark-extension.md) lists every task; the [original five-task sheet](results/laya/laya-benchmark.md) preserves the initial result.

The original playground server exited during an earlier attempt at held-out task `a720371d`, after **96 calls with 47 errors** (4 HTTP 500, 1 connection reset, 42 connection refused). That attempt is retained in the [incident JSON](results/laya/laya-benchmark-incident.json.gz) and excluded from successful quality, token, call, and latency aggregates. The following task was interrupted before its first checkpoint, leaving **0–19 possible uncounted local calls** and no quality result. Both tasks were rerun under the identical frozen ranking protocol after switching to the portable server, and their successful rows were included. This interruption and retry are disclosed in the [combined raw results](results/laya/laya-benchmark-extended.json.gz).

The separate [paired server resource replay](results/laya/laya-benchmark-server-resources.md) sent the same 60 saved development requests sequentially to each server, never loading both at once. Both returned identical scores and zero errors. The portable server's private memory moved **3,028→3,040 MiB** with threads **33→33**; the original playground moved **2,969→3,970 MiB** with threads **35→450**. This strongly implicates per-request thread resources in the earlier failure, though concurrent IDE load and pagefile pressure were also present; a 60-call replay alone does not prove a single root cause. Neither resource test used held-out labels.

A separate [exact-response cache diagnostic](results/laya/laya-benchmark-cache.md) ran 60 saved development requests twice with `--response-cache 128`. First-pass misses had **1,303/1,432 ms** median/95th-percentile round-trip latency; exact second-pass hits had **2.8/24.5 ms**. All 120 scores matched the uncached saved scores exactly, with zero errors. The server reported **21,438 input tokens** on misses and **0 new input tokens plus 21,438 cached input tokens** on hits. This measures exact repeats only; changed task or file text misses the cache. The held-out evaluation ran with caching **off**.

A separate [frozen stability soak](results/laya/laya-benchmark-soak.md) replayed only saved development requests after the evaluation: 150 distinct exact requests across 14 cycles, plus 10 repeated requests per cycle. All **2,240/2,240 calls** completed in **57.9 minutes** with zero errors, exact score parity against the saved uncached results, and the expected **2,100 misses / 140 hits**. Private memory moved **3,015→3,065 MiB** (peak **3,088 MiB**), threads **25→31** (peak **33**), and C: free space remained at least **7.86 GiB**. This tests the final cached server's bounded stability and eviction on repeated development inputs, not held-out retrieval quality; its [raw call and resource data](results/laya/laya-benchmark-soak.json.gz) remain separate from the uncached evaluation.

An [actual headless IDE/MCP and hook smoke](results/laya/laya-ide-mcp-smoke.json.gz) used a 1,705-file Koog source fixture from an already-seen development task. The explicit Laya provider completed **93 local requests with zero errors**, and an exact repeat served **93/93 from cache**; the local keyword provider and keyword hook also returned ranked paths. This is a development functional check, not an additional held-out quality result or a controlled latency comparison. It ran against an earlier plugin build; a later manual-pin readability fix was tested and packaged separately, without rerunning this IDE smoke.

### Limitations

The English checkpoint has a 512-token input window, and the evaluated adapter caps each file to its first 1,000 characters; full-source BM25 sees more text. Short commit subjects and modified-file truth are imperfect relevance labels, and all 30 held-out tasks come from one repository and period. The prefilter and two-pass pool cap Laya's recall ceiling (combined mean 0.859 and 0.654 respectively). This is a reproducible source-retrieval proxy, not an IDE latency or downstream coding-quality test. See [Reproduce](#reproduce) and [Protocol](#protocol) below for the executable protocol and [../LAYA.md](../LAYA.md) for local setup.

## Reproduce

Use Python 3.10 or newer, Git, and a local Laya playground server with the English
checkpoint ready on 127.0.0.1:8770. The upstream components are:

- Laya source and package: https://github.com/NandhaKishorM/laya
- Laya weights: https://huggingface.co/convaiinnovations/laya
- Local API playground: https://github.com/wdobry/laya-playground
- Evaluation corpus: https://github.com/JetBrains/koog

The evaluated Laya package was 0.3.5 from author commit
573e5b62696ba441230cd6be71d593331b5d23af. The playground was at
5a825ba0472a460830bc937907d43262a87410f8. The local package and
checkpoint setup is in work/laya/SETUP.md, outside this repository. The
reproducible English-only API is tools/laya_server.py in this repository. It
keeps inference on one persistent worker thread. The upstream playground's
default server preloads three checkpoints; the first six measured tasks used
work/laya/start_english.py to load English only. After that server exited
during task seven, tasks seven and eight were retried with the portable server,
the same package, checkpoint, request contract, and frozen ranking protocol.
The failed attempt is retained separately. Model checkpoint snapshot:
1c5edc17a7acd8701df6fc341c0d179f1c62c982.

From the repository root, start the portable server with an existing English
checkpoint cache (or add `--download` to fetch it explicitly):

    python tools/laya_server.py --cache PATH_TO_CACHED_HF_HOME

Keep `--response-cache 0` (the default) for this benchmark. The runner rejects an
enabled response cache and any cached prediction so repeated responses cannot
appear as new inference in the latency or token totals. Cache timings belong in
a separate repeated-request experiment.

From the plugin directory in PowerShell, fetch a bounded Koog history:

    git clone --depth 100 --filter=blob:limit=100k --no-checkout --single-branch --branch develop https://github.com/JetBrains/koog.git .cache/koog

The measured Koog head was 5e81c2277ecccdc8dcfa0c03be7865062a6ea956.
If develop has moved, fetch that commit and keep at least 100 ancestor
commits in the local cache, then pass its hash as --ref. Check the server first:

    Invoke-RestMethod http://127.0.0.1:8770/api/health

Run the bounded evaluation from the plugin directory:

    python eval/laya_benchmark.py --ref 5e81c2277ecccdc8dcfa0c03be7865062a6ea956 --dev 3 --heldout 5 --prefilter 60 --pool 20 --output .cache/laya-benchmark.json --markdown .cache/laya-benchmark.md

If interrupted, append --resume. Completed tasks are stored atomically in
the JSON after each task. The interrupted task repeats. On the first failed
model call, the script saves exact partial call details to a neighboring
`*-incident-*.json` file, records the attempt in the checkpoint, and stops;
`--resume` retries that task under the same settings. The script requires a
loopback HTTP endpoint and caps held-out tasks at 30, candidates at 60, and
the pool at 20.
Its code uses only Python's standard library.

For a pre-frozen extension, pass its manifest with `--task-manifest` and set
`--heldout 30`. Also pass
`--expected-checkpoint 1c5edc17a7acd8701df6fc341c0d179f1c62c982`; this requires the portable
server to report the exact cached weights before any prediction and stores the
expectation in the resumable checkpoint. The script verifies the Koog HEAD, eligibility exclusions,
development split, initial held-out split, every extension task's parent and
truth files, and all retrieval settings before it sends a model request. The
manifest is also hash-checked against a resumed checkpoint. A fresh 33-task
run can write to `.cache/laya-benchmark-extended.json` and its matching
Markdown path. Our exact 25-task continuation manifest is delivered separately
as `laya-benchmark-extension-manifest.json`.

To recompute the paired 30-task recall interval from the committed saved
rankings, without a model server, run from the plugin directory:

    python eval/paired_recall.py --input eval/results/laya/laya-benchmark-extended.json.gz --output .cache/paired-recall.json

The script validates the 3-development/30-held-out split and saved per-task
recall, hashes the decompressed source JSON, and refuses to overwrite an
existing output. Its default seed is 20260923 with 10,000 task resamples;
`--seed` and `--resamples` can be set explicitly.

## Protocol

The first three eligible first-parent, non-merge commits are development
tasks. The next five are held out. Eligibility follows spike/koog.py: fix,
feat, refactor, or perf subject; 1 to 8 modified Kotlin files; no
documentation/release/revert subjects; no test-only changes; and newly added
Kotlin files cannot dominate modified ones. An answer file must exist and be
at most 100 kB at the parent commit.

For each task, the script:

1. Ranks all parent-snapshot Kotlin files with the same camel-case tokenizer
   and BM25 formula as src/main/kotlin/dev/contextpacker/pack/Bm25.kt.
2. Recomputes BM25 within the selected 60, exactly as Packer does, and sends
   one regex sketch per file to Laya. The sketch is capped at 1,200 characters.
3. Pools local BM25's top 20 with sketch Laya's top 20, removing duplicates.
4. Sends each pool file to Laya again with the first 1,000 source characters
   prepared by Packer. The adapter also caps the passed text at 1,000
   characters, including Packer's path line.
5. Ranks the pool by Laya probability plus the local BM25 position bonus:
   probability + 1 / (1 + zero_based_local_BM25_rank / 10). The reported fixed
   plugin ranking uses weight 1.0, divided by 2 for display only.

Both BM25 passes and both model-score sorts break ties by lexicographic path.
The HTTP body exactly matches LayaRelevance.kt: English model, a state
beginning with "File: " plus the path and excerpt, and one noul question
beginning "This source file is relevant to implementing the following coding
task: ". No cross-file packing occurs. Per-request input tokens, server
inference time, wall round-trip time, errors, and returned model label are
saved. Failed calls stop the run before an invalid ranking is aggregated; the
attempt remains auditable in its incident file.

The development tasks select among several already-saved fusion weights
and lexical baselines using recall@10, then recall@5, then fewer local
model calls. This does not trigger extra model calls. The held-out report
always includes full-corpus BM25, local BM25 within the 60-file shortlist,
BM25 on the two-pass pool, the plugin's fixed weight 1.0, and any
development-selected variant. The local BM25 row is essential: it isolates
the gain from lexical reranking so it is not incorrectly credited to Laya.
No held-out task chooses a setting.
Metrics are macro recall@5 and recall@10 over task truth files. The JSON
contains the full BM25 ranking and raw Laya scores for auditing.

One separate development-only efficiency experiment can reuse the saved
task split and score BM25's top 40 files in a single Laya pass. For each
file, it sends a 1,000-character window around the code line with the
greatest task-term overlap. The top-20 result uses the first 20 calls
from the same development experiment:

    python eval/laya_benchmark.py --ref 5e81c2277ecccdc8dcfa0c03be7865062a6ea956 --dev-variant-from .cache/laya-benchmark.json --variant-candidates 40 --output .cache/laya-benchmark-dev-window.json --markdown .cache/laya-benchmark-dev-window.md

That experiment makes no held-out calls. It can inform a variant to freeze
before a future independent evaluation, but its development result is not
a held-out quality claim.

Cold model load is separate from warm calls. A single warmup is excluded
from task metrics. Local API fee is zero, excluding electricity, hardware,
and developer time. The English checkpoint's entire input window is 512
tokens, including question and options; the remainder of a long file is
silently truncated. This is a source retrieval proxy, not a direct IDE UI
latency measurement.

## Response-cache diagnostic replay

The recorded stability run used the workspace scripts `work/laya/soak_diagnostic.py`
and `work/laya/paired_resource_check.py`. `soak_replay.py` is the checked-in
replay of that frozen diagnostic; it is separate from the retrieval benchmark
and does not retune its tasks or model.

From `plugins/context-packer`, verify the saved 150 development request bodies
and 2,240-call order without loading Laya or contacting a server:

```sh
python eval/soak_replay.py --baseline eval/results/laya/laya-benchmark.json.gz --manifest eval/results/laya/laya-benchmark-soak-manifest.json.gz --koog .cache/koog --verify-only
```

The Koog path must be a bare repository containing the parent commits recorded
in the baseline. The command reconstructs every source sketch and exact JSON
request body, then compares its path, score, request hash, sequence hash,
checkpoint, cache capacity, and stop limits with the saved manifest. It exits
nonzero on missing or inconsistent data.

For a new live replay on Windows, start a **fresh**, pinned English Laya server
with `--response-cache 128`. Give its process ID and a new output path:

```sh
python eval/soak_replay.py --baseline eval/results/laya/laya-benchmark.json.gz --manifest eval/results/laya/laya-benchmark-soak-manifest.json.gz --koog .cache/koog --pid SERVER_PID --output .cache/laya-soak-replay.json
```

The replay verifies the server health and empty cache before inference, sends
one warmup request, and checkpoints the result after each call. It refuses to
overwrite an existing result, checks saved score parity and expected cache
hits, and stops at the manifest's 5 GiB server-private-memory or 3 GiB C:
free-space limits. Live replay needs Windows resource counters; offline
verification is portable. The server endpoint is loopback
`127.0.0.1:8770`. New replay results record the baseline and replay-script
SHA-256 hashes as provenance; these identify the new run, not the original
workspace-script run.

## Provenance and audit files

- Model package: [Laya author source](https://github.com/NandhaKishorM/laya) commit `573e5b62696ba441230cd6be71d593331b5d23af`, version **0.3.5**. Weights: [convaiinnovations/laya](https://huggingface.co/convaiinnovations/laya) English checkpoint `1c5edc17a7acd8701df6fc341c0d179f1c62c982`. Runtime: PyTorch `2.14.0+cpu` on Windows CPU.
- Initial API implementation: [laya-playground](https://github.com/wdobry/laya-playground) commit `5a825ba0472a460830bc937907d43262a87410f8`, loading English only. Extension server: `tools/laya_server.py` at repository commit `3e27658`, Git blob `b59f95cc892a86fad64d67d3964eb0431ae4a6dd`; the executing process predated response-cache changes. Extension harness: `eval/laya_benchmark.py` at commit `943d58d`, blob `3462efafc3edbe786b1915184c6eec6aeb6c7272`. Later validation edits did not change the already-running evaluation.
- Frozen extension manifest SHA-256: `dae05b2e0fcdfef3ba4323cf84906180c6d9a702a83edd7a6c0ef9a706076e8e`. The server health response reported the expected model checkpoint before extension inference. [Full combined raw JSON](results/laya/laya-benchmark-extended.json.gz), [initial raw JSON](results/laya/laya-benchmark.json.gz), [development-window raw JSON](results/laya/laya-benchmark-dev-window.json.gz), [cache raw JSON](results/laya/laya-benchmark-cache.json.gz), and [paired portable](results/laya/laya-benchmark-server-portable.json.gz)/[playground](results/laya/laya-benchmark-server-playground.json.gz) resource samples are retained for audit. The separate [soak sequence manifest](results/laya/laya-benchmark-soak-manifest.json.gz) has SHA-256 `1a6f5b543c0ed37141aeff878d127f0c53db65381b140b646e230ec74b44f42b`; it was frozen before the soak and contains no outcome.
- All large JSON files are deterministically gzip-compressed with mtime 0 and no embedded filename. [ARTIFACTS.json](results/laya/ARTIFACTS.json) gives SHA-256 and byte size for both compressed and original content. Read one with `python -c "import gzip,json; d=json.load(gzip.open('results/laya/laya-benchmark-extended.json.gz','rt',encoding='utf-8')); print(d['protocol'])"` from this `eval` directory. Proposed evidence was scanned for common credential forms, private local paths, and long embedded text; it includes public Koog commit subjects and paths, scores, timings, and process telemetry, with no model weights or private transcripts.
