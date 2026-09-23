# Laya response-cache stability soak

This diagnostic replays only saved development requests. The sequence was frozen before any soak inference: 150 distinct exact requests, 14 cycles, and the last 10 requests repeated at the end of each cycle to create known cache hits. It tests stability and eviction, not retrieval quality; no held-out settings or rankings were changed.

Sequence SHA-256: `bbcc2f582e833cd97255b856adfec93db306b3194baa03c3ad26ed3c75fb8c37`. Manifest SHA-256: `1a6f5b543c0ed37141aeff878d127f0c53db65381b140b646e230ec74b44f42b`. Server commit `7dd2a4e`, blob `861702b56125643c23a3d5e4bfdad71800e86be3`. Checkpoint: `1c5edc17a7acd8701df6fc341c0d179f1c62c982`; response cache capacity 128.

Status: **complete**; 2,240/2,240 planned calls completed. Start: 2026-09-22T23:03:48.651291+00:00; finish: 2026-09-23T00:01:41.642720+00:00. Stop reason: none.

| Measure | Cache misses | Exact cache hits |
| --- | ---: | ---: |
| Round-trip p50 (ms) | 1427.6 | 4.0 |
| Round-trip p95 (ms) | 1618.4 | 26.2 |
| Server p50 (ms) | 1417.2 | 0.1 |
| Server p95 (ms) | 1601.5 | 0.1 |
| Calls | 2,100 | 140 |
| Reported input tokens | 751,436 | 0 |
| Cached input tokens | 0 | 49,322 |

Maximum absolute score difference from saved uncached results: **0.000000**. Wall time: **57.9 min**. Server cache reported 140 hits at the end.

Private memory: 3015 MiB initially, 3065 MiB after the last call, peak 3088 MiB. Threads: 25 initially, 31 after the last call, peak 33. Lowest C: free space: 7.86 GiB. The run stopped if server private memory reached 5 GiB or C: free space fell below 3 GiB.

All calls used local CPU with $0 API fee, excluding electricity and hardware. Exact cache hits are faster because they skip inference; changed task or file text will miss. Raw per-call parity, latency, resource, and hit data: [laya-benchmark-soak.json.gz](laya-benchmark-soak.json.gz).
