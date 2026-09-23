# Local Laya response-cache diagnostic replay

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
