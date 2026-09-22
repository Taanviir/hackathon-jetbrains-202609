# Context Packer

An IntelliJ plugin that finds the files a coding task needs in about four seconds, so an agent
can skip the part where it reads the repository one file at a time.

Type a task, or let an agent call the `pack_context` MCP tool, and it scores every source file in
the project against that task. You get back the ten or twenty files most likely to matter, with
tests flagged.

## Does it work?

It's measured on real history. Each task is a real commit subject from
[JetBrains/koog](https://github.com/JetBrains/koog) and the right answer is the `.kt` files that
commit changed. Every file is read as it was *before* the commit, so the answer can't leak in.
Settings were chosen on 40 dev tasks, then measured once on 70 test tasks nobody had looked at.

| 70 held-out tasks | recall@5 | recall@10 | recall@20 |
| --- | --- | --- | --- |
| BM25 keyword search | 0.42 | 0.53 | 0.63 |
| **Context Packer** | **0.57** | **0.69** | **0.80** |

That's +16 points of recall@10, with a 95% bootstrap interval of +10 to +23. In the IDE on Koog
(2,206 files) a pack takes about 4.4 s, for 54-56 Jev calls and about $0.03. Files are sketched in the
background when the project opens, so the first pack is no slower. Details, including the version that *didn't* beat BM25 and why, are in
[spike/RESULTS.md](spike/RESULTS.md).

## How it works

Jev is TypeSafe's decision model. It can't write text; it answers typed questions with calibrated
probabilities, fast and cheaply. That makes it the right tool for asking one question about
thousands of files, and the wrong one for writing the fix. So the fix is left to an LLM, and the
plugin does the looking.

1. **Collect.** Every source file in the project, minus excluded, generated, library, binary and
   files over 100 KB.
2. **Sketch.** Each file becomes a ~300-token summary: path, package, and declarations two levels
   deep with the first line of each doc comment. It's the same sketcher the eval measured (a
   parity test checks the Kotlin port against it), it takes about 1.4 s for 2,206 files, and it's
   cached by modification stamp. Sketches built from the IDE's Structure View read better but
   cost ~19 ms a file cold and are unmeasured; `CONTEXT_PACKER_PSI_SKETCH=1` turns them on.
3. **Pass 1.** Jev reads 60 sketches per call and answers, for each one, "Implementing the change
   described in `task` requires reading or editing the file in `f07`." BM25 ranks the full text
   at the same time.
4. **Pool.** The top 60 from each.
5. **Pass 2.** Jev asks the same question again over the *full source* of just the pool, 6 files
   per call. Sketches hide what many tasks are about, and a small pool makes full source affordable.
6. **Fuse.** Jev's score and BM25 rank count equally. That weighting was chosen on dev tasks.
7. **Stage 3.** One more Jev call, a `choice` over the top 10: "which file must be edited?". Passes 1
   and 2 judge each file alone; this compares them, and lifts recall@5 on held-out tasks from 0.54 to
   0.57 (+0.03, interval +0.006 to +0.07) for about 0.4 s.

Jev alone on sketches loses to plain keyword search (0.39 against 0.46 recall@10 in the spike).
It's the re-rank on full source, fused with BM25, that wins.

## Install

Needs an IntelliJ-based IDE, 2025.2 or newer.

1. Get `build/distributions/context-packer-0.1.0.zip`, or build it with `./gradlew buildPlugin`.
2. **Settings | Plugins | ⚙ | Install Plugin from Disk…** and pick the zip.
3. **Tools | Context Packer: Set API Keys…**:
   - `TYPESAFE_API_KEY` for Jev, through TypeSafe's own API. This is the fast path.
   - `AI_GATEWAY_API_KEY` is the fallback when there's no TypeSafe key, or when
     `JEV_BACKEND=gateway` is set. Vercel serves the same model, but in testing it answered only
     about 30% of calls under load (429s and 503s), so a pack takes 30-60 s.
   - `OPENROUTER_API_KEY`, only for the **Ask LLM** button. Jev never goes through OpenRouter.

   Environment variables with the same names work too, and win over stored keys. A changed key takes
   effect on the next pack. Each IDE session stops after 20M Jev input tokens (about 30 packs, $0.84)
   so a looping agent can't drain an account; set `CONTEXT_PACKER_TOKEN_BUDGET` to change that.
4. Optional: `CONTEXT_PACKER_EXTENSIONS=kt` restricts candidates to Kotlin, which is exactly the set every
   published number was measured on. By default the plugin scores all source languages.

## Use it

**In the IDE.** Open the **Context Packer** tool window (magnifier icon, right stripe), describe
the change and press **Pack context** or Ctrl+Enter. Double-click a pick to open it, press Delete
to drop one, and use **Add open file** to pin one it missed. Then **Copy prompt** puts the task
plus every picked file on the clipboard, or **Ask LLM** sends it to `z-ai/glm-5.3-flash` through
OpenRouter. Set `CONTEXT_PACKER_LLM_MODEL` to use a different model.

![An agent's pack shown in the tool window](docs/tool-window-agent-pack.png)

*An agent called `pack_context` over MCP; the tool window shows what it was handed, 4.4 s later.*

**From an agent.** The plugin adds a `pack_context` tool to the IDE's built-in MCP server. Turn the
server on in **Settings | Tools | MCP Server**, then point your agent at it. For Claude Code:

```
claude mcp add --transport sse jetbrains http://127.0.0.1:64342/sse
```

**Better: let the agent start with the files.** Strong agents tend to trust their own search: headless
Claude Code ignored `pack_context` in five tries, even when told to use it. So there's also a Claude Code
hook, `agent/pack_hook.py`. It runs on every request before Claude sees it, asks the IDE's Context Packer,
and hands Claude the ranked files as context, so nothing has to be chosen. Put this in a project's
`.claude/settings.local.json`:

```json
{"hooks": {"UserPromptSubmit": [{"hooks": [{"type": "command", "timeout": 60,
  "command": "cd <repo>/plugins/context-packer/eval && uv run --project . python ../agent/pack_hook.py"}]}]}}
```

The hook gives up after 25 s and never blocks a prompt. The tool's description tells the agent to call it before searching. Whatever an agent asks for
also appears in the tool window, marked as asked by an agent, so you can see the context it was
given. If the IDE runs on Windows and the agent in WSL, localhost only reaches the IDE with WSL's
mirrored networking turned on.

## Demo script

About two minutes, on a Koog checkout:

1. Open the tool window. Type *"Support reasoning_content in Delta for OpenAI"* and pack. The
   status line shows files scored, seconds, Jev calls and cost. `OpenAILLMClient.kt` comes first.
2. Hover the status line for the stage timings, and a pick for its Jev score and keyword rank.
3. Press **Ask LLM**. The model answers from the picked files in one shot, with no exploring.
4. In a terminal, ask Claude Code to make the same change. It calls `pack_context` first and goes
   straight to the right files.
5. Close with the table above. It's measured on commits from JetBrains' own agent framework.

## Against an LLM, and inside an agent

Full numbers are in the [eval report](https://taanviir.github.io/hackathon-jetbrains-202609/feat-context-packer/context-packer-eval/).

**Against an LLM re-ranker.** GLM-5.3 Flash re-ranked BM25's top 30 on full source, on the same 70 tasks. It picks
the top five better (recall@5 0.61 against 0.52 for Jev + BM25, a significant gap), and at ten they tie (0.67 against
0.65). Jev does it in about 1 s for about $0.002, where the LLM takes 29 s and $0.007. Jev's edge is speed and cost,
not judgement. Stage 3 narrows the top-five gap but doesn't close it.

**Inside an agent.** The same GLM agent ran 10 held-out tasks with and without `pack_context`, twice: once with commit
subjects, once with identifier-free rewrites. Final recall was identical in both. With the packer the agent used 7-22%
fewer tokens and fewer calls, but it was not faster to the first right file, because a grep-first agent gets there in
about 4 s on these tasks. Ten tasks can't separate any of it from noise.

## Reproduce the numbers

Everything runs from this directory with [uv](https://docs.astral.sh/uv/). Put keys in the repo
root's `.env`. Jev spend is recorded in `.cache/jev_ledger.json` across runs, and calls are refused
once it reaches `JEV_TOKEN_BUDGET` input tokens (default 24M, about $1). The full 136-task run is
about 75M tokens (about $3), so raise the budget deliberately for it.

```
git clone https://github.com/JetBrains/koog.git .cache/koog
cd spike
uv run python jev_spike.py limits
uv run python jev_spike.py hybrid --tasks 136 --top 60      # saves every score once
uv run python fusion.py ../.cache/spike_runs/<that file>.json --dev 40
cd ../eval && uv run python agent_ab.py --tasks 10 --offset 40 [--vague]
uv run python rerank_llm.py --pool 30          # LLM baseline, stops at RERANK_MAX_COST_USD
uv run python stage3.py --split dev --k 10     # choose stage 3 on dev, then --split test once
uv run python make_report.py                   # rebuilds reports/context-packer-eval/
```

Plugin tests are `./gradlew test`, 23 of them, headless, and CI runs them on every PR. For a licence-free sandbox IDE with the
MCP server on, run `OPEN_PROJECT=/path/to/project ./gradlew runIdeCommunity`.

## Limits

- Claims are measured on Kotlin only, in one repository. Sketching works for other languages but
  isn't evaluated there.
- Tasks that mostly add new files are out of scope. There's nothing yet to find.
- Sketches are built in the background when a project opens, so the first pack is as fast as the
  rest. After that, only changed files are re-read.
