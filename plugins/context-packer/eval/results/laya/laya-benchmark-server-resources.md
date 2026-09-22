# Laya server resource replay

The English-only upstream playground and portable persistent-worker server were run **sequentially**, never loaded together, on this Windows CPU host. Each received one warmup and the same 60 saved, varied Koog development requests. No retrieval settings or benchmark quality outcomes were selected using this diagnostic.

Portable run: 2026-09-22T20:47:08.128026+00:00; playground run: 2026-09-22T20:49:49.460008+00:00. Laya 0.3.5 and PyTorch 2.14.0+cpu. Portable checkpoint: 1c5edc17a7acd8701df6fc341c0d179f1c62c982. The playground used the same local English cache; exact score parity against saved requests is checked below.

| Measure | Portable worker | Upstream playground |
| --- | ---: | ---: |
| Completed / 60 | 60 | 60 |
| Errors | 0 | 0 |
| Round-trip p50 (ms) | 1281 | 1397 |
| Round-trip p95 (ms) | 1435 | 1657 |
| Max absolute score difference vs saved run | 0.000000 | 0.000000 |
| RSS after warmup (MiB) | 1978 | 1941 |
| RSS after replay (MiB) | 2005 | 2899 |
| Private bytes after warmup (MiB) | 3028 | 2969 |
| Private bytes after replay (MiB) | 3040 | 3970 |
| Threads after warmup | 33 | 35 |
| Threads after replay | 33 | 450 |
| Minimum C: free (GiB) | 8.45 | 8.44 |

Portable stop reason: completed; playground stop reason: completed.
The first benchmark's playground process exited after many more calls while the machine's pagefile expanded under concurrent IDE load. This 60-request replay can show short-run memory trends, but cannot by itself prove the thread-local resource hypothesis or rule out host memory pressure. Safety stops were 5 GiB private bytes and 3 GiB free on C:.
Full per-request latency, score, RSS, private bytes, thread count, and disk data: laya-benchmark-server-portable.json and laya-benchmark-server-playground.json.
