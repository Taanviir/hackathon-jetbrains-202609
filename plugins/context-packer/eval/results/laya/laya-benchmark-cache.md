# Laya exact-response cache diagnostic

This is a separate local performance check on 60 saved development inputs, not a held-out retrieval evaluation. The same real Laya checkpoint was queried twice in a fixed order. The server used `--response-cache 128`; one distinct warmup request was excluded from the 60-by-two timings.

Checkpoint: `1c5edc17a7acd8701df6fc341c0d179f1c62c982`; executing server commit `7dd2a4e` (blob `861702b56125643c23a3d5e4bfdad71800e86be3`); Laya 0.3.5 / PyTorch 2.14.0+cpu on cpu.

| Measure | First pass (60 misses) | Second pass (60 hits) |
| --- | ---: | ---: |
| Round-trip p50 (ms) | 1303.2 | 2.8 |
| Round-trip p95 (ms) | 1431.7 | 24.5 |
| Server p50 (ms) | 1290.2 | 0.1 |
| Server p95 (ms) | 1419.9 | 0.1 |
| Sum reported input tokens | 21,438 | 0 |
| Sum cached input tokens | 0 | 21,438 |
| Cache hits | 0 | 60 |

All 120 scores matched saved uncached development scores exactly; errors: 0. Private memory: 3014 MiB before warmup and 3074 MiB after both passes. This measures exact repeated requests only. New or changed task/file text causes a cache miss. The model remains on local CPU; no paid API calls were made.
Raw per-call scores, latency, token usage, hits, and resources: laya-benchmark-cache.json.
