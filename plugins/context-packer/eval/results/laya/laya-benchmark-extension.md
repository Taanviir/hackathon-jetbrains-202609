# Laya benchmark: frozen 25-task extension

Official Koog history at 5e81c2277ecc. The exact next 25 eligible commits were frozen in laya-benchmark-extension-manifest.json before evaluation. No settings were changed after the three development tasks; the retrieval settings, prompt, model, and weights are identical to the initial run.
Extension started at 2026-09-22T21:00:23.604728+00:00. Manifest SHA-256: `dae05b2e0fcdfef3ba4323cf84906180c6d9a702a83edd7a6c0ef9a706076e8e`. Server health reported checkpoint `1c5edc17a7acd8701df6fc341c0d179f1c62c982` before any extension prediction.
The executing portable server was `tools/laya_server.py` at repository commit `3e27658`, Git blob `b59f95cc892a86fad64d67d3964eb0431ae4a6dd`. Its health endpoint reported the pinned checkpoint before the extension started. Later response-cache changes were not loaded in this process. The executing benchmark harness was `plugins/context-packer/eval/laya_benchmark.py` at commit `943d58d`, Git blob `3462efafc3edbe786b1915184c6eec6aeb6c7272`; later fresh-run provenance and uncached-response validation changes were not loaded.

## Quality

| Ranking | Next 25 recall@5 | Next 25 recall@10 | Combined 30 recall@5 | Combined 30 recall@10 |
| --- | ---: | ---: | ---: | ---: |
| Full-source BM25 (0 model calls) | 0.405 | 0.555 | 0.410 | 0.553 |
| Shortlist BM25 (0 model calls) | 0.224 | 0.427 | 0.242 | 0.428 |
| Fixed Laya+BM25 | 0.252 | 0.382 | 0.265 | 0.391 |
| Laya only | 0.036 | 0.103 | 0.047 | 0.114 |

Macro recall divides each task's retrieved truth files by its total truth files, then averages tasks equally. Ground truth is modified Kotlin files in the parent snapshot; relevance is therefore an imperfect proxy.

## Cost and runtime

The extension made **2,329 successful local model calls** over 25 tasks and consumed **812,274 reported input tokens**. API fee was **$0**; electricity, hardware, and developer time are excluded.
Per-call round-trip p50/p95: **1315/1560 ms**; server inference p50/p95: **1303/1549 ms**. Mean two-pass inference: **117.2 s/task**.
Calls were sequential on CPU. Cold model startup and Git snapshot loading are separate from per-call latency. The next-25 latency was measured on the portable persistent-worker server; the combined-30 latency spans two server implementations and earlier IDE contention, so use next-25 for a cleaner runtime estimate.

## Individual extension tasks

| Commit | Task | Truth | Candidates | BM25 @10 | Fixed Laya @10 | Calls | Tokens | Wall |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 58a8168f | Use full JavaType in decodeFromJSONElement to preserve generics | 2 | 1659 | 0.50 | 0.00 | 94 | 34,531 | 125.8 s |
| 485e294d | Stop double-encoding tool-call arguments in OpenAI-compatible requests | 3 | 1659 | 0.33 | 0.33 | 96 | 36,699 | 133.6 s |
| 6ee3b635 | Resolve @Contextual properties in GenericJsonSchemaGenerator | 2 | 1663 | 0.50 | 0.50 | 90 | 30,374 | 111.4 s |
| 482a4344 | Support sealed type outputs in subgraphWithTask | 6 | 1663 | 0.33 | 0.17 | 93 | 31,899 | 117.1 s |
| c779c048 | Drop .lowercase() call in AnthropicLLMClient enum serialization | 1 | 1658 | 1.00 | 0.00 | 93 | 33,679 | 122.9 s |
| 581d6af6 | Don't start a new tool call on repeated tool call id | 3 | 1658 | 0.00 | 0.00 | 94 | 34,728 | 126.7 s |
| 4f3a14b4 | #2089 added check guard for Bedrcok Nova empty system prompt array | 2 | 1649 | 1.00 | 1.00 | 97 | 37,209 | 135.4 s |
| 2ffea411 | Add auto-discovery for Amazon Bedrock AgentCore Memory | 6 | 1643 | 0.17 | 0.17 | 93 | 33,580 | 122.4 s |
| 921f7c1c | Support multiple tool calls per response in LiteRTLLMClient | 3 | 1643 | 1.00 | 1.00 | 97 | 37,377 | 135.9 s |
| 54b8ecb1 | Update OpenAI JSON schema generation for serialization of nullable col | 2 | 1643 | 0.50 | 0.00 | 95 | 33,728 | 122.0 s |
| d7800fa2 | Fix ollama message converter which fix ollama tests | 1 | 1614 | 0.00 | 0.00 | 91 | 31,614 | 115.1 s |
| 7fdf1b35 | Fix agents integration test | 2 | 1614 | 0.50 | 0.50 | 95 | 28,871 | 108.5 s |
| 61017581 | Change provider-specific SpringBoot beans to PromptExecutor | 7 | 1614 | 0.57 | 0.14 | 94 | 29,097 | 107.7 s |
| fe5acfad | Don't fail on unknown tools in Persistence checkpoint | 3 | 1614 | 0.33 | 0.33 | 94 | 31,197 | 114.8 s |
| be773aa9 | Rewrite prompt message parts in PromptAugmenter implementations | 5 | 1626 | 1.00 | 1.00 | 97 | 33,875 | 124.4 s |
| 14650533 | . Fix the type for the LLMCallStarting event | 1 | 1626 | 1.00 | 0.00 | 88 | 30,118 | 109.3 s |
| c5e45950 | . Add missing LLMCallFailedEvent agent remote events | 5 | 1626 | 0.60 | 0.20 | 88 | 30,299 | 111.2 s |
| 242314c3 | Add back ModeratedMessage class, and add Message textContent() handy f | 5 | 1626 | 0.20 | 0.20 | 93 | 34,793 | 126.1 s |
| 45e805aa | Tool schema generation for nested nullable objects | 3 | 1626 | 1.00 | 1.00 | 89 | 30,481 | 111.1 s |
| 294ce31b | Add package to Stub | 1 | 1626 | 1.00 | 1.00 | 89 | 25,010 | 96.5 s |
| f70ec8f4 | Handle exceptions from decodeResponse() in AbstractOpenAILLMClient | 1 | 1605 | 1.00 | 1.00 | 90 | 32,936 | 124.7 s |
| 69d6f12d | Add ios stub for litert, revive FactRetrieval constructor with varargs | 2 | 1604 | 0.50 | 0.50 | 94 | 29,624 | 111.8 s |
| 2e3cfe78 | Add resultObject to ReceivedToolResult to get raw intermediate result  | 6 | 1604 | 0.17 | 0.17 | 95 | 34,865 | 126.7 s |
| ebd39457 | Mask Anthropic key autoconfiguration (security bug) | 1 | 1594 | 0.00 | 0.00 | 93 | 30,857 | 114.1 s |
| 24f66953 | SubgraphWithTask & subtask missing tool results in prompt if other too | 3 | 1586 | 0.67 | 0.33 | 97 | 34,833 | 127.2 s |

The English checkpoint has a 512-token input window; each model request includes at most the first 1,000 file characters. Full-source BM25 reads more of each file. The benchmark is a source-retrieval proxy, not an IDE end-to-end timing test.
Raw scores and timings: laya-benchmark-extended.json. Initial run and incident: laya-benchmark.json and laya-benchmark-incident.json.
