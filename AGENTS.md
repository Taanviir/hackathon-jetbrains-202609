# Working together

This hackathon repository is being edited by several people and agents concurrently.

- Read and respond to the current coordination inbox before overlapping another workstream: https://github.com/Taanviir/hackathon-jetbrains-202609/issues/1.
- Fetch current remote branches before integration. Use your own branch, preserve others' commits, and avoid force pushes or resets of shared branches.
- `plugins/context-packer/` and the root IntelliJev plugin are separate implementations. Keep both build paths working when integrating them.
- Keep model benchmark results separate by provider, task split, model/checkpoint and environment. Projected pitch figures are not measurements.
- API keys, model weights, virtual environments, dependency caches and private meeting transcripts do not belong in commits.
- Run the relevant tests and include limitations in the PR. Merge to main only after reviewing the current diff and verification results.
