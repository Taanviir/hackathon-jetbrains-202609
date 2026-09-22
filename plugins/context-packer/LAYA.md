# Local Laya provider

Choose **Laya (local)** in the Context Packer tool window, or call the MCP tool with
`provider: "laya"`. No Jev key is required and no cloud fallback is used. For a
fresh sandbox, `CONTEXT_PACKER_PROVIDER=laya` selects Laya initially; a later UI
selection is saved in project workspace settings.

The provider uses the [Laya playground](https://github.com/wdobry/laya-playground)
HTTP contract backed by [Laya](https://github.com/NandhaKishorM/laya). Start its
local server, then confirm `GET http://127.0.0.1:8770/api/health` reports the English
model ready. Its documented installation starts at
[brainfunctioncollapse.com/laya](https://brainfunctioncollapse.com/laya).

Optional environment variables:

| Variable | Default |
| --- | --- |
| `CONTEXT_PACKER_LAYA_URL` | `http://127.0.0.1:8770/api/predict` |
| `CONTEXT_PACKER_LAYA_MODEL` | `english` |

Only loopback HTTP endpoints are accepted. The plugin does not download model
weights or install Python automatically. Initial model download/load time is
separate from the pack latency shown in the plugin.

## What differs from Jev

The English checkpoint has a 512-token input window including its question. It
truncates long input internally. Sending the Jev multi-file batches would silently
drop later files, so the plugin sends **one file per request**, with a short source
excerpt. Task descriptions are limited to 500 characters for this provider.

To bound local CPU work, BM25 selects at most 60 candidates before Laya's two
passes. Each pass sends at most 1,000 source characters plus the path and task;
this character limit is not an exact tokenizer budget. Scores are fused with
keyword rank. The status tooltip and MCP response identify the provider and
candidate count. Laya has not inherited Jev's measured retrieval claims.

The displayed **API fee is $0** for local inference. Hardware, electricity and
setup/download time are excluded. The optional **Ask LLM** button still uses the
separately configured OpenRouter model and its API key; Laya supplies decisions,
not generated code.

## Failure behavior

Cancel stops the active request and queued scoring work. An unavailable server,
invalid probability or missing answer is a failure, not a valid zero score.
Partial scoring failures are disclosed. If every batch in a pass fails, packing
fails instead of presenting keyword-only results as model output.

Prompt export shares a 48,000-source-character budget across selected files, keeps
selected tests represented, labels excerpts and gives the generation model explicit
instructions to request missing evidence rather than invent source or test results.
