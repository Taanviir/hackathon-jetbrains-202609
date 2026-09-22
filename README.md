# Help the Developer (JetBrains challenge)

Hackathon entry for the JetBrains "Help the Developer" challenge. Build an app or
IntelliJ plugin, driven by AI, that makes developers' lives easier. The brief is in
[Help the Developer.pdf](./Help%20the%20Developer.pdf).

We are still choosing the idea. This README is the shared context for that choice.

## What we built

- **[Context Packer](./plugins/context-packer/)**, an IntelliJ plugin plus an MCP tool that uses Jev to
  find the files a coding task needs. On 70 held-out Koog commits it reaches recall@10 of 0.69,
  against 0.53 for keyword search. Start with its [README](./plugins/context-packer/README.md).
- **[Reports](https://taanviir.github.io/hackathon-jetbrains-202609/)**, eval benchmarks and other HTML
  outputs from every branch. To add yours, see [reports/README.md](./reports/README.md).

## Team

| Handle | Role |
| --- | --- |
| [@Taanviir](https://github.com/Taanviir) | lead |
| [@aikram42](https://github.com/aikram42) | |
| [@mahahahad](https://github.com/mahahahad) | |

## Links

- Ideation board (Excalidraw, live): https://excalidraw.com/#room=8bad828d43f9f20fb6c6,qZL7qGpemsGiex94Yf4R5g
  The room key is in that URL. Anyone holding the link can edit the board, so keep it in this repo.
- Jev on OpenRouter: https://openrouter.ai/typesafe/jev-1.13
- Jev Lab, for trying rules without writing code: https://openrouter.ai/labs/jev
- IntelliJ Platform Plugin Template: https://lp.jetbrains.com/intellij-platform-plugin-template/
- IntelliJ Platform SDK docs: https://plugins.jetbrains.com/docs/intellij/welcome.html
- LLM plugin template from JetBrains Research: https://github.com/JetBrains-Research/llm-integration-plugin-template

## What Jev is, and why it shapes the idea

We want to build on Jev, TypeSafe's first "System One" model. Read this before ideating,
because it rules out most of the obvious hackathon ideas.

**Jev cannot write text.** It takes unstructured `state` plus a map of typed `questions`,
and returns calibrated probabilities. A `noul` is P(the statement is true). It cannot
produce a string, so it cannot write a test, a refactor, or a comment. What it can do is
answer a fuzzy question about code in 70 to 500ms for roughly nothing.

- Endpoint is `POST https://openrouter.ai/api/alpha/decisions`, not chat completions.
- Model id `jev-latest`. Context 32k. Up to 255 options per choice.
- $0.042 per million input tokens. Output is free.
- We have an OpenRouter key. It goes in `.env`, which is gitignored. Never commit it.

Request and response look like this:

```json
{
  "model": "jev-latest",
  "state": "<the code and its surrounding context>",
  "questions": {
    "stale_doc": {
      "type": "noul",
      "instructions": "The doc comment no longer describes what this function does"
    }
  }
}
```

```json
{ "stale_doc": { "type": "noul", "noul": 0.94 } }
```

So Jev is the part that decides, thousands of times over, and an LLM is the part that
writes, called only where Jev says it is worth it. Any idea where Jev is doing the
generating is a dead end.

## The bar we are aiming at

The brief ranks judging criteria in this order, and two of them are doing real work here:

1. Implementation and proof of concept. It has to run end to end.
2. User experience. It has to fit an existing workflow.
3. Innovation. The brief explicitly calls out "wrap a prompt around a common IDE action"
   as the thing to avoid.
4. Technical features. Real codebase context, not just the selected snippet.
5. Presentation.

A useful test for any candidate: take Jev out and swap in an LLM. If the idea still works,
just slower, it is probably not innovative enough to win on criterion 3.

## Diagrams

Scene files are in [docs/diagrams](./docs/diagrams). Drag one onto the Excalidraw board to
drop it in, or use File then Open to look at it on its own. They are editable shapes, not
images, so pull them apart during the session.

- [ideas-board](./docs/diagrams/ideas-board.excalidraw) is 22 ideas as sticky notes, green
  where Jev is load-bearing and amber where an LLM could do the same job. Drop this one in
  first and dot-vote three each.
- [00-two-tier-principle](./docs/diagrams/00-two-tier-principle.excalidraw) is the shape all
  three candidates share. Read it before voting.
- [a-semantic-inspections](./docs/diagrams/a-semantic-inspections.excalidraw)
- [b-codebase-triage](./docs/diagrams/b-codebase-triage.excalidraw)
- [c-llm-rubric](./docs/diagrams/c-llm-rubric.excalidraw)

## Candidate ideas

Each of these has one objection it has to survive. That is the thing to bring an answer to.

### A. Semantic inspections

Fuzzy IDE inspections that run as you type. IntelliJ inspections are AST and regex based,
so they cannot express "this doc comment no longer matches the code" or "this name is
misleading". An LLM can express those but is far too slow to run per keystroke. Jev is not.
Demo is an edit to a function body that lights up the stale docstring above it, with an
LLM quick fix behind Alt+Enter.

Objection to answer: a 70 to 500ms network call on a PSI listener, firing while someone
types. Does debouncing and cancellation keep the IDE responsive, or does this feel laggy
and lose criterion 2?

### B. Codebase-wide triage

Sweep every function in the repo through Jev to score bug risk, missing tests, dead code,
and drift from local conventions. Ranks a whole codebase in seconds for cents, which no
LLM can do. An LLM then writes fixes for the top few.

Objection to answer: the output is a ranked list in a tool window. Is that a demo anyone
remembers, and how is it different from the code health dashboards that already exist?

### C. LLM-written rubric

An LLM reads the repo once and writes the typed question set that encodes this project's
own conventions. Jev then enforces that set continuously. Most agentic of the three, and
the strongest answer to criterion 4.

Objection to answer: this is a component, not a product. What does it attach to, and can
it be demoed on its own at all?

## Ideation items

Timebox: 20 minutes alone, then 10 minutes together at the board.

Everyone, before the session:

- [ ] Read the Jev section above and the brief PDF.
- [ ] Open [Jev Lab](https://openrouter.ai/labs/jev) and run one rule of your own against a
      snippet of real code. Bring the number it gave you. This is the fastest way to build
      intuition for what Jev is and is not good at.
- [ ] Claim your [free educational license](https://www.jetbrains.com/community/education/#students)
      if you do not have one.

Then take one candidate each and come back with a two minute pitch plus the one reason it
fails:

- [ ] @Taanviir owns candidate A, semantic inspections. Also answer the latency objection,
      since it is the biggest risk on the board. A throwaway script that times 20 real
      calls to the decisions endpoint settles it.
- [ ] @aikram42 owns candidate B, codebase-wide triage. Sketch what the panel actually
      shows, and find the existing tool that already does the boring version of this.
- [ ] @mahahahad owns candidate C, LLM-written rubric. Write five real questions you would
      want asked about our own code, and check in Jev Lab whether Jev answers them well.

Whoever gets there first:

- [ ] Get the plugin template cloned and running with an empty inspection, so we are not
      fighting Gradle after the idea is picked.
- [ ] Find the vault link in the GitLab repo and note which keys it actually gives us.

## Decision checkpoint

Pick one candidate, then write the scope here. Cut lines:

- If we are short on time, candidate A ships as one inspection with three rules.
- Candidate C folds into A as an onboarding step if there is time left over.

Deadline for the decision: TBD, fill in once we know the submission time.

## Getting set up

Nothing is scaffolded yet. That happens once the idea is picked. Recommended stack is
Kotlin, per the brief.
