# Local Laya provider

Choose **Laya (local)** in the Context Packer tool window, or call the MCP tool with
`provider: "laya"`. No Jev key is required and no cloud fallback is used. For a
fresh sandbox, `CONTEXT_PACKER_PROVIDER=laya` selects Laya initially; a later UI
selection is saved in project workspace settings.

The provider uses the [Laya playground](https://github.com/wdobry/laya-playground)
HTTP contract backed by [Laya](https://github.com/NandhaKishorM/laya). This repository
includes an English-only server at [`tools/laya_server.py`](tools/laya_server.py).
It binds to `127.0.0.1`, serializes inference, and keeps source bodies out of its
request logs. It does not require the playground UI or download other checkpoints.

## Install and run

Install Git, Python 3.10 or newer, and [uv](https://docs.astral.sh/uv/). From the
repository root in PowerShell, create an isolated environment and install the
same author revision used in the first local benchmark:

```powershell
uv venv --python 3.10 plugins/context-packer/.cache/laya-venv
uv pip install --no-cache --python plugins/context-packer/.cache/laya-venv/Scripts/python.exe 'laya @ git+https://github.com/NandhaKishorM/laya.git@573e5b62696ba441230cd6be71d593331b5d23af'
& plugins/context-packer/.cache/laya-venv/Scripts/python.exe tools/laya_server.py --download
```

The first run fetches the English checkpoint (about 807 MB on the tested host)
and then serves it. Keep that terminal running. Later starts can omit `--download`:

```powershell
& plugins/context-packer/.cache/laya-venv/Scripts/python.exe tools/laya_server.py
```

Without `--download`, startup requires cached weights and sets offline mode before
loading Laya. `--cache D:/path/to/cache` selects a different model cache and `--port`
selects a different port. The default cache is `plugins/context-packer/.cache/laya`,
which is ignored by Git. On macOS/Linux, use `.cache/laya-venv/bin/python` in place
of the Windows `Scripts/python.exe` path above. CPU inference is supported; a GPU
is not required. The tested environment was Python 3.10.0, Laya 0.3.5,
torch 2.14.0+cpu and transformers 5.17.0; the source pin alone does not pin every
transitive dependency.

The model checkpoint is pinned to `1c5edc17a7acd8701df6fc341c0d179f1c62c982`, the
snapshot used for this evaluation. `--revision <40-character commit>` explicitly
selects another checkpoint; `/api/health` reports the loaded checkpoint. Moving
branch names are rejected so a later download cannot silently change this default.

From another terminal, confirm the server has loaded before packing:

```powershell
Invoke-RestMethod http://127.0.0.1:8770/api/health
```

The response must contain `models.english: ready`. Starting a second server on the
same port fails; use the existing instance or stop its terminal first.

### Optional exact-response cache

By default, every `/api/predict` request runs local inference, including repeated
requests. To reuse identical results during an editing session, start the server
with `--response-cache 128` (any capacity from 0 to 1024; 0 disables it). This is
an in-memory least-recently-used cache, separate from `--cache` for model weights.
It matches the effective English model, state and ordered question definitions
exactly. Keys retain only SHA-256 hashes, and entries are limited to 64 KiB each.
Nothing persists after the server exits or a different checkpoint is loaded.

With this option enabled, responses include `cache_hit`. On a hit, the server
skips inference, reports `usage.input_tokens: 0` for that request, and puts the
original inferred input size in `usage.cached_input_tokens`. A miss reports
`cache_hit: false`, `usage.cached_input_tokens: 0`, and its actual input tokens;
`/api/health` shows cache capacity,
hits and current entries. Failed or non-finite model results are never cached.
The default-off response shape and benchmark protocol are unchanged. A cache hit
still makes a local HTTP request, but incurs no new model inference.

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
Laya retains the regex sketch format used in its frozen evaluation, independently
of Jev's corrected sketcher and optional PSI sketches. Switching providers
invalidates cached sketches when their format differs.

The displayed **API fee is $0** for local inference. Hardware, electricity and
setup/download time are excluded. The optional **Ask LLM** button still uses the
separately configured OpenRouter model and its API key; Laya supplies decisions,
not generated code.

## Failure behavior

Cancel stops the active request and queued scoring work. An unavailable server or
timed-out request stops the pack without waiting again for every queued file. Once
the server recovers, start a new pack. An invalid probability or missing answer is
a failed batch, not a valid zero score.
Partial scoring failures are disclosed. If every batch in a pass fails, packing
fails instead of presenting keyword-only results as model output.

Prompt export shares a 48,000-source-character budget across selected files, keeps
selected tests represented, labels excerpts and gives the generation model explicit
instructions to request missing evidence rather than invent source or test results.
