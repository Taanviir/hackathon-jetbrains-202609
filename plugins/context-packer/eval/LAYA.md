# Local Laya retrieval benchmark

This benchmark evaluates the Laya adapter against real JetBrains/koog history. The
task is a commit subject. The relevant files are the Kotlin files that commit
modified. Every candidate is read from the parent commit, before the change.
It makes no paid API calls.

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
