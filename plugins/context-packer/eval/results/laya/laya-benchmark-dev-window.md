# Development-only Laya efficiency experiment

Koog 5e81c2277ecc, Laya 0.3.5 on cpu. Only the first 3 eligible development tasks were scored. No held-out task or label was used.
The variant sends a task-term-focused 1,000-character source window for each of BM25's top 40 files, one local HTTP request per file. Top 20 results use the first 20 of those saved calls. It has one pass instead of the plugin baseline's two.

| Development ranking | Recall@5 | Recall@10 | Calls/task |
| --- | ---: | ---: | ---: |
| bm25 | 0.456 | 0.456 | 0 |
| local_bm25 | 0.522 | 0.633 | 0 |
| fixed_two_pass | 0.456 | 0.633 | 94 |
| single_20_laya | 0.000 | 0.067 | 20 |
| single_20_blend | 0.456 | 0.456 | 20 |
| single_40_laya | 0.000 | 0.000 | 40 |
| single_40_blend | 0.456 | 0.456 | 40 |

Mean candidate ceiling: top 20 0.589; top 40 0.656. The fixed two-pass row uses 93–94 calls per task on these tasks; its 94 is a rounded comparison.
Actual calls: 120; reported input tokens: 41,747; errors: 0; local API fee: $0 excluding hardware and electricity.
This is exploratory development evidence. Any selected variant must be frozen before a first held-out evaluation; comparing these development results to the held-out baseline would not be a valid independent test.
