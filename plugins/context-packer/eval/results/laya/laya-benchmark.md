# Laya benchmark: Koog pre-commit retrieval

Run: 2026-09-22T19:40:15.662704+00:00. Koog 5e81c2277ecc on develop; Laya 0.3.5 / PyTorch 2.14.0+cpu on cpu (AMD64 Family 25 Model 117 Stepping 2, AuthenticAMD, 16 logical CPUs).
The first 3 eligible tasks are development; the next 5 are held out. No settings were chosen using held-out outcomes. 1 otherwise eligible commits were excluded because a truth file could not be retrieved under the plugin's predeclared 100,000-byte cap.
Development timing conditions: Concurrent IntelliJ/Gradle toolchain unpack and build activity on this host; CPU latency may be inflated. Held-out timing conditions: First three held-out tasks used the English-only upstream playground; final two used the portable persistent-worker server after an infrastructure incident. IDE bootstrap and two 8-call MCP smoke runs overlapped around 00:09-00:10 Dubai, so latency may be inflated. No held-out tuning.

## Held-out quality

| Ranking | Recall@5 | Recall@10 |
| --- | ---: | ---: |
| BM25 full source | 0.435 | 0.542 |
| BM25 reranked within top 60 (0 Laya calls) | 0.329 | 0.435 |
| BM25 on Laya/BM25 pool (pass 1 calls) | 0.329 | 0.435 |
| Plugin fixed fusion (weight 1.0) | 0.329 | 0.435 |
| Laya pass 2 only | 0.100 | 0.169 |

Dev selection: local_bm25 based on development recall@10, then recall@5, then fewer local model calls. The plugin's fixed weight is 1.0 and is reported regardless of dev selection.
Mean BM25-top-60 ceiling on held-out truth: 0.881; mean two-pass pool ceiling: 0.729.

## Held-out tasks

| Commit | Task | Truth files | Candidates | BM25 @10 | Fixed Laya+BM25 @10 | Calls | Tokens | Wall time | Errors |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 164f57a7 | Use chatModel.options.mutate() in SpringAiLLMClient v2 to avoid ClassCastEx | 3 | 1693 | 0.67 | 0.67 | 93 | 36,117 | 145.6 s | 0 |
| a4576638 | Add freshHistory parameter to subgraph for optimization support | 7 | 1659 | 0.14 | 0.14 | 92 | 32,331 | 134.7 s | 0 |
| d97a6e4d | MessageTokenizer storage key conflict | 1 | 1659 | 1.00 | 1.00 | 97 | 34,205 | 141.0 s | 0 |
| a720371d | Carry strategy input on StrategyStartingContext | 5 | 1659 | 0.40 | 0.20 | 96 | 33,325 | 120.2 s | 0 |
| e4e9e407 | Add support for Anthropic Claude Fable 5 model | 6 | 1659 | 0.50 | 0.17 | 97 | 35,478 | 126.7 s | 0 |

## Cost and latency

- Evaluation made **756 local model requests**, consumed **277,832 reported input tokens** and generated zero output tokens. The model's API fee was **$0**. Electricity, hardware, and developer time are excluded.
- Protocol reconciliation reused saved development responses and discarded **13** earlier exploratory calls from protocol totals; those calls were actually made and also had $0 API fee.
- Successful request round-trip p50/p95: **1439/1872 ms**; model inference p50/p95: **1426/1839 ms**.
- Held-out mean BM25 computation: **1636 ms/task**. Two Laya passes: **131.0 s/task**. These are sequential CPU requests; file snapshot extraction is separate.
- Errors: **0**. Warmup: 359 ms round-trip, 357 ms inference, excluded from task metrics. Cold model load was not measured in this run; the separately observed cached startup was about 47 s.

- Separate development-only experiment: **120 calls**, recorded in laya-benchmark-dev-window.json and excluded from the fixed-protocol totals and held-out selection.

## Infrastructure incident

A prior attempt at a720371d made **96 calls**, with **47 failures** before the local Laya server exited. It is preserved in laya-benchmark-incident.json and excluded from the successful quality, token, call, and latency aggregates above. The task was retried under the identical frozen protocol after server recovery.
The following task, e4e9e407, was interrupted before its first task checkpoint. Its partial request count is 0 to 19 unknown local calls because only 20-call progress markers were emitted; it contributed no ranking or timing result to the aggregate.

## Interpretation and limits

This is a small, recent, commit-subject benchmark. Ground truth is modified Kotlin files in each commit; a file can be useful without being modified, and a modified file can be hard to infer from a short subject. The corpus is each commit's parent snapshot, so changed content cannot leak into retrieval.
The English checkpoint has a 512-token total window. Each request contains one file; the adapter caps its text at 1,000 characters, and the model may truncate further after the question header. BM25 reads full source. The pass-1 prefilter caps Laya's ceiling and the pass-2 pool caps final recall.
The script mirrors the Kotlin request body and retrieval math, but is a Python harness over Git snapshots, not an IntelliJ UI or end-to-end plugin timing measurement. Score ties are broken by lexicographic path, matching the plugin's explicit tie rules. A larger held-out sample and direct IDE run are needed before claiming a quality gain.
Excluded commits (before splitting): f3a8aa5a: truth exceeds plugin 100000-byte candidate cap.

Reproduction and exact protocol: eval/LAYA.md. Raw scores, rankings, tokens, request timings, and errors: laya-benchmark.json.
