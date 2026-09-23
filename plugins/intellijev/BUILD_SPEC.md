# IntelliJev — build specification

Status: planning baseline, plus a Milestone 1-and-beyond implementation now present in the
repository. The Gradle build, the unit tests and plugin packaging are verified; live
runtime integration with Jev and with a chat-completions model has not been. Divergences
between this document and the code are recorded in §2 and §12.
Date: 22 September 2026 (implementation status added the same day).
Source: the team's nine-page IntelliJev deck, presented as [the pitch deck](../../reports/intellijev-pitch/index.html).

## 1. Product and scope

Build an IntelliJ IDEA plugin that uses Jev for structured decisions about code, context, tools, models, and ongoing agent work. Use a generative model when prose, explanations, summaries, or code are needed.

The product has six features sharing a decision client and repository context layer:

1. **File Finder:** assemble task-relevant context with a balance of edit targets, examples, constraints, and definitions.
2. **Bug Twins:** find other implementations of a business rule that might share a bug the developer just fixed.
3. **Tool Finder:** shortlist relevant tools for an agent step.
4. **Model Router:** select an appropriate available model for a request.
5. **Context Guard:** suggest a separate conversation for an unrelated question.
6. **Run Watch:** show agent progress and preserve a usable handoff when work stops.

The deck marks File Finder and Bug Twins as the features to ship first. Preserve both as the initial scope; the final presentation's flagship remains undecided. This document adds implementation proposals and acceptance criteria, not evidence that the proposed performance has been achieved.

Success means a working developer workflow with measurable benefit, understandable results, and reliable recovery. A shared Jev client alone does not make all six integrations equivalent.

## 2. Delivery boundaries

| Boundary | Requirement |
| --- | --- |
| Host | IntelliJ IDEA; pin and record one compatible IDE, JDK, Kotlin, and plugin SDK combination before development. |
| Initial language | Proposed implementation choice: Java first for semantic extraction and the demo repository; add Kotlin only after Java works. Other languages need explicit support or a labelled text-only fallback. **As built:** the plugin is entirely Kotlin, and there is no semantic extraction at all — discovery is a virtual-file-system walk scored on filename tokens, so this row's premise is unmet. |
| Stack | Kotlin and IntelliJ Platform SDK; Jev through OpenRouter as proposed in the deck. Evaluate Koog for the controlled agent loop rather than making every feature depend on it. **As built:** Kotlin and the IntelliJ Platform SDK as proposed, but Jev is called directly at `POST https://api.typesafe.ai/v1/systemone` with a dedicated TypeSafe key rather than through OpenRouter; OpenRouter or OpenAI is used only for generation. Koog, the ACP bridge and the controlled agent loop are not adopted. |
| Main chat | Reuse AI Assistant through documented ACP custom-agent integration where possible. |
| Plugin UI | Own the result panels, context preview, run dashboard, settings, and side-question interface. |
| Agent access | Observe and control sessions launched through our integration. Do not promise interception of all existing AI Assistant conversations. |
| Authentication | Delegate agent login to the selected adapter's supported flow. Do not assume the existing AI Assistant login transfers to every adapter. |
| Mutations | Present proposed changes as reviewable diffs; preserve the agent's permission handling. Initial Bug Twins discovery does not automatically edit every match. |

Out of scope for the first working slice: forking the official AI Assistant implementation, arbitrary modifications of its message renderer, support for every agent/provider, automatic cross-provider session migration, and guaranteed detection of all related bugs.

## 3. Architecture to build

### A. IntelliJ plugin

- Actions: Find task context; Find related bugs from a selected fix; open IntelliJev panel.
- Tool window with Context, Related Bugs, and Runs views; a separate side-question view for Context Guard.
- Project context service using IntelliJ's Program Structure Interface (PSI), its structured representation of source code.
- Source navigation, context preview, diff display, cancellation, progress indicators, and settings.
- Local storage of repository snapshots, cached decisions, run events, and checkpoints.

### B. Decision and retrieval layer

- Provider-neutral `DecisionClient`; initial OpenRouter implementation.
- Typed request/response validation, bounded batching and concurrency, deadlines, retry policy, and cancellation.
- Candidate extraction, optional deterministic prefiltering, Jev ranking, context packing, and evidence retrieval.
- Explicit separation between probabilities/rankings and verified outcomes.
- Per-request accounting and cache invalidation.

### C. Agent integration

Use two distinct integration levels:

1. **ACP bridge around an existing adapter:** forward prompts, supported context, responses, tool events, permissions, and cancellation. Supply File Finder context and record Run Watch events. Add Context Guard before forwarding a request. This preserves the existing agent's internal implementation.
2. **A controlled agent loop, potentially using Koog:** own which tool definitions the model sees at each step and which model receives each call. This is required for the full Tool Finder and per-step Model Router experience unless the selected adapter exposes equivalent supported controls.

A bridge must not claim to filter an existing agent's internal tools just because it can see tool events. Start with one adapter and verify the protocol capabilities it actually supports. Keep the bridge independent of IntelliJ-specific code so it can be exercised with recorded protocol traffic.

### D. Generative model gateway

- Provide the code-writing, explanation, side-question, and summary calls used by our own workflows.
- Keep generation separate from Jev decisions: Jev is not the summary writer.
- Record the actual model, provider, usage, and execution outcome.
- Do not assume an agent subscription also authorizes arbitrary direct model API calls. Configure those separately when required.

## 4. Shared components and contracts

These are proposed internal contracts, not claims about provider API field names.

| Object/service | Required information or behavior |
| --- | --- |
| `RepositorySnapshot` | Project identity, revision, dirty-file content hashes, extraction version, supported languages. |
| `CodeCandidate` | Stable ID, relative path, symbol, source range, signature, surrounding type/imports, available relationships, content hash, optional body. |
| `DecisionRequest` | Feature and schema version, task state, candidate IDs, typed questions, request budget. |
| `DecisionResult` | Validated typed answers, model identity, latency, usage if available, cache status, errors. Never silently replace a failed decision with score zero. |
| `ContextBundle` | Included snippets, source references, assigned roles, token estimates, exclusions, snapshot identity. |
| `BugCandidate` | Source reference, relevance scores, inspected evidence, verification status, developer dismissal/confirmation. |
| `RunEvent` | Run/session ID, sequence, timestamp, event type, payload, provenance. |
| `Checkpoint` | Task, known completed work, remaining work, changed files, checks with results, blockers, source revision, evidence references. |
| `UsageRecord` | Operation, model, input/output tokens when available, actual or estimated cost, latency, retries, cache status. |

Repository extraction must run without blocking the UI and respect IntelliJ read access and indexing availability. Exclude generated output, binaries, dependencies, ignored paths, and configured sensitive paths by default. Inspect the unsaved editor state where supported and identify it in the snapshot.

Invalidate affected candidates and decisions after source edits. Include task, candidate content, question schema, model, and extraction version in cache keys. Keep all displayed source locations tied to a snapshot; refresh stale results before using them for changes.

## 5. Feature specifications

### F1. File Finder

**User flow:** enter a task → select scope → scan/rank → inspect the context bundle → add/remove items → send to the selected agent or export it.

Build:

- PSI extraction of compact file/symbol sketches and available relationships.
- Token-aware request packing with candidate IDs preserved across batches.
- Four relevance questions corresponding to edit target, example, constraint, and definition.
- A deterministic packer with configurable total and per-role budgets, deduplication, and actual source retrieval for selected candidates.
- A second-pass option to inspect bodies where sketches provide insufficient evidence.
- A result view showing role, path/symbol, snippet, inclusion status, and decision score where useful.
- A fallback that allows manual context selection when ranking fails.

**Acceptance:** a developer can obtain and revise a bundle, navigate to its sources, and pass it to one working agent integration. The bundle fits the configured budget, includes real source content, and refreshes changed files. Empty, cancelled, partial, and failed scans are distinguishable.

**Evaluation:** compare required-file recall and downstream task success against baseline retrieval. The claim that signatures suffice is an experiment, not a dependency we can assume away.

### F2. Bug Twins

**User flow:** select a before/after fix → confirm the business rule being checked → scan candidates → inspect ranked locations → reproduce or dismiss each suspected bug.

Build:

- Input from a working-tree diff or a selected commit with accessible before/after code.
- Editable rule description supplied by the developer or proposed by a generative model.
- Broad candidate retrieval followed by inspection of shortlisted function bodies and relevant context.
- Separate questions for “same business rule” and “potentially same defect”; similarity alone is insufficient.
- Results with the original fix, candidate implementation, source navigation, and evidence status.
- Optional generation of a focused reproduction/test, previewed before execution or application.
- Dismissal controls for intentional differences and confirmed unrelated results.

**Acceptance:** the demo finds a semantically related, differently named buggy implementation, excludes or clearly ranks down a deliberately different implementation, and permits reproduction of the suspected defect. Display “candidate” until evidence confirms the bug. Failed or unrun verification remains visible.

**Demo fixture:** coupon expiry fixed in one checkout path; another checkout or renewal path retains the defect; a gift-card path uses a legitimately different rule. Keep additional held-out examples to avoid validating only the rehearsed fixture.

### F3. Tool Finder

**User flow:** during a controlled agent run, relevant tools are selected for the next model call; the developer can inspect the selection in the run trace.

Build a tool catalogue, Jev selection questions, capability and permission checks, a configurable shortlist, and a fallback to a broader permitted catalogue. Never rank away cancellation or other host-level controls. If omitted tools become necessary, allow reselection rather than trapping the agent.

**Acceptance:** in a loop we control, the recorded model request actually contains the selected tool definitions, and a task needing an initially omitted tool can recover. Demonstrating a ranked list beside an unchanged agent is a partial feature, not completion.

### F4. Model Router

**User flow:** the system recommends or selects a model for a request; the developer can lock a model and inspect routing decisions.

Build an available-model registry with credentials/capabilities, configurable routing classes, user override, low-confidence handling, and bounded escalation after inadequate results. Account for context size and required tools, not just apparent question difficulty. Transfer necessary context explicitly when changing models or agents.

**Acceptance:** routing selects only configured, usable models; a manual lock wins; failures do not create infinite escalation loops; the trace shows the model actually called. Compare quality as well as cost. Starting a new provider session with a handoff is not native resumption of the old session.

### F5. Context Guard

**User flow:** a possibly unrelated prompt triggers a suggestion to continue here or ask separately. The developer chooses; a side answer can later be brought back into the main task.

Build a task-intent summary, a belongs-in-this-thread decision, an editable context preview, a separate conversation store, and an explicit “bring answer back” action. Allow suppression of repeated suggestions. Keep the original prompt intact.

**Acceptance:** the developer can override every suggestion; a side chat receives only the selected context; no side-chat content enters the main agent history unless requested. A selection bubble inside AI Assistant's native message renderer remains an optional integration experiment. The deliverable can use the plugin's own panel.

### F6. Run Watch

**User flow:** view active and recent runs → inspect status and changes → open a saved checkpoint → continue with the same agent where supported, or start a new run using a handoff.

Build an event collector, per-run timeline, changed-file/evidence references, status classification, periodic checkpointing, and optional generative summaries. Keep raw events so summaries are auditable. Distinguish running, waiting for permission/user, completed, failed, cancelled, and interrupted states using observable events before inferred classification.

Save structured checkpoints at meaningful boundaries; do not wait for an exhausted model to write a final summary. If summary generation is unavailable, render a deterministic handoff from recorded events. Do not promise advance rate-limit prediction without supported usage data.

**Acceptance:** an interrupted demo run retains its latest checkpoint after restart and can produce a usable handoff. The dashboard does not claim tests passed merely because the agent said so. Changes made concurrently by a human or another agent are not attributed without evidence.

## 6. UX and operational requirements

- One discoverable IntelliJev tool window; task entry and relevant editor/VCS actions.
- Clear progress, cancellation, partial results, retry, and error states throughout.
- Show why context was included through roles and source evidence; use generation only if an explanation is requested.
- Review source contents before external submission, honour project exclusions, store credentials using the IDE's credential facilities, and keep secrets out of diagnostic logs.
- Keep code, source comments, and agent output as data, not privileged instructions to the application.
- Bound concurrent requests, request size, scan cost, and retries; show when a scan reaches its configured budget.
- No silent fallback to an expensive model. Make the fallback policy configurable and visible.
- Store run history locally by default; provide deletion and redact exported diagnostics.
- Package a reproducible plugin build, setup instructions, a demo project, and documented supported versions.

## 7. Validation gates before broad implementation

| Gate | Experiment | Evidence needed / consequence |
| --- | --- | --- |
| Jev transport | Authenticate, send a small typed decision, inspect valid and invalid responses. | Capture the actual model ID, endpoint, schema, limits, error behavior, and usage fields. Do not copy unverified SDK-shaped examples into Kotlin. |
| Batching | Vary candidates, question count, and input size with bounded parallelism. | Measure accepted requests, p50/p95 latency, rate limits, and token usage. Select batch sizes from results. |
| Extraction quality | Compare sketches against bodies on representative Java tasks. | Choose a second-pass strategy when sketches lose necessary evidence. |
| ACP integration | Run one prompt through the bridge and selected adapter in IDEA. | Verify authentication, context transfer, tool events, permissions, cancellation, and capability negotiation end to end. |
| Agent-loop control | Inspect the chosen adapter's supported controls. | If it cannot accept per-step tool/model choices, use a controlled loop for F3/F4 or mark those features incomplete. |
| UI integration | Build plugin-owned result panels and editor navigation. | Treat undocumented AI Assistant UI hooks as optional; pin versions if any are used. |

## 8. Dependency-based build sequence

This sequence assumes rapid implementation with coding agents. It is ordered around evidence and integration dependencies rather than speculative hour estimates.

**Milestone 0 — feasibility:** complete the Jev, extraction, and ACP gates; pin versions and record findings. Create the demo repository and evaluation task set.

**Milestone 1 — shared foundation:** plugin shell, settings, PSI extraction, typed decision client, cache, metrics, navigation, and cancellation.

**Milestone 2 — first end-to-end feature:** File Finder produces a previewable bundle and supplies it to one agent. Record a real baseline and treatment run.

**Milestone 3 — second flagship:** Bug Twins consumes a fix, finds related implementations, and supports evidence-backed review and reproduction.

**Milestone 4 — session features:** Run Watch event storage/checkpoints and Context Guard's separate conversation flow.

**Milestone 5 — controlled loop:** Tool Finder and Model Router, with the actual per-call inputs and chosen model observable.

**Milestone 6 — presentation readiness:** failure recovery, packaging, repeatable demonstration, benchmark report, and setup from a clean IDE profile.

Suggested workstreams, with owners to be assigned by the team: (A) IntelliJ extraction/UI, (B) Jev retrieval/evaluation, (C) agent bridge/run persistence. Agree on the contracts in section 4 before concurrent implementation. This is a division of responsibilities, not an assignment to particular teammates.

## 9. Evaluation and demo evidence

### File Finder

Use historical changes as one evaluation source. For each case, retrieve from the **parent revision** and supply a task description that does not reveal the answer files. Exclude the answer diff from retrieval. Commit messages can be incomplete or leak filenames, and changed files are only an approximate relevance label; manually inspect the dataset. Follow the deck's target of 50 real commits when a suitable dataset is available, and report the actual evaluated sample count.

Compare keyword retrieval, an embedding baseline if implemented, and IntelliJev. Report recall at ten, retrieval latency, input tokens, costs, and downstream task success. Separate warm-cache and cold-cache results. Tune thresholds on different cases from those used for the final report.

### Bug Twins and secondary features

- Bug Twins: report confirmed related bugs, false positives, intentional differences, missed labelled candidates, and verification outcomes.
- Tool Finder: test omitted-tool recovery and task completion versus the full permitted tool catalogue.
- Model Router: compare quality and total cost, including retries and escalations.
- Context Guard: measure false interruptions and test explicit context separation.
- Run Watch: interrupt at several boundaries, restart, inspect the checkpoint, and resume or hand off.

### Measurement rules

- Count all Jev requests, source extraction time, generative calls, retries, and verification work.
- Keep client tool calls, provider calls, and Jev decisions as separate counters; reducing one does not prove total work fell.
- Label costs as billed, token-based estimates, or unavailable. Subscription usage is not automatically an exact dollar cost per task.
- Compare equivalent tasks, repository state, permissions, and model settings. Repeat runs and report variation and failures.
- The deck's 24→2 calls, 48→6 seconds, and $0.42→$0.05 are projections, not acceptance thresholds or measured results.
- Reconcile the page 6 approximate $0.14 retrieval cost with the page 8 $0.05 whole-task projection before reusing either. At 8,000 files and 60 sketches per request, a single pass requires at least 134 requests before overhead, not approximately 120. At 400 tokens per file, sketches alone total about 3.2 million input tokens; questions and repeated state add more.

## 10. Definition of done and handover

Every delivered feature needs a real interactive path, error/cancellation behavior, evidence for its acceptance criteria, and an explicit status in the release notes. Mocked events and projected counters must be identified as such.

Team handover must include:

- Installable plugin and reproducible source/build instructions.
- Agent bridge or controlled-loop launcher, with tested setup instructions.
- Demo repository, baseline procedure, and repeatable demo script.
- Tests for decision parsing/failure handling, context budgets/cache invalidation, bridge forwarding/permissions/cancellation, and checkpoint recovery.
- A benchmark report containing actual results and limitations.
- A feature matrix stating implemented, partial, experimental, or unimplemented for all six features.
- A short architecture note listing pinned versions, credentials required, data sent externally, and unsupported integrations.

No feature is complete merely because its panel exists or Jev returns a plausible score.

## 11. Sources and certainty

- **Team deck:** product scope, six features, proposed Kotlin/PSI/Koog/OpenRouter stack, and projected demonstration. Local file cited at the top of this document.
- [JetBrains ACP documentation](https://www.jetbrains.com/help/ai-assistant/acp.html): custom agents can run inside AI Assistant via configured commands; this establishes a supported integration route, not access to every agent's private loop or chat renderer.
- [IntelliJ PSI documentation](https://plugins.jetbrains.com/docs/intellij/psi.html): PSI supplies structured source-code access; relevance ranking and defect inference remain our work.
- [TypeSafe's Jev introduction](https://typesafe.ai/blog/introducing-system-one-models-and-jev): structured probabilistic decisions are the model's intended role; task accuracy must be evaluated.
- [OpenRouter TypeSafe catalogue](https://openrouter.ai/typesafe) and [OpenRouter typed-decision example](https://openrouter.ai/labs/jev/compile): inspected 22 September 2026. The catalogue lists 32K context and Jev 1.13 at $0.042/million input tokens and $0 output. The example uses multiple records/questions; neither establishes the deck's proposed batch size or latency under our load. Verify the exact transport/model identifier in the feasibility gate and pin the working configuration.
- [TypeSafe API reference](https://docs.typesafe.ai/api), [TypeSafe models page](https://docs.typesafe.ai/models) and [TypeSafe quickstart](https://docs.typesafe.ai/introduction/quickstart): inspected 22 September 2026 while checking the §7 Jev transport gate. They confirm `POST https://api.typesafe.ai/v1/systemone`, the `jev-latest` alias, and the three question types (choice, score, noul), with score returning a probability-weighted value across the ordered levels supplied. This is documentation evidence only: no request from this build has been sent, so the response envelope `JevClient` assumes — a top-level `answers` object carrying a `score` per question — remains unconfirmed at runtime.

All acceptance criteria, internal contracts, milestones, and fallback policies above are proposed engineering requirements derived from the deck. No claim of runtime verification of the integrated features is made; §12 records the narrower build-level verification that does exist.

## 12. Implementation status (22 September 2026)

What is in the repository now, separated into what has actually been verified, what exists
but has not been proven, and what is known to be wrong.

### Verified

- `gradlew test` succeeds. `compileKotlin`, `instrumentCode`, `jar` and both
  `JevClientTest` cases pass against IntelliJ IDEA Community 2025.1.1, Kotlin 2.1.10,
  IntelliJ Platform Gradle plugin 2.3.0, and a JDK 21 toolchain.
- `gradlew buildPlugin` produces `build/distributions/intellijev-0.1.0.zip`.
- The plugin loads in the sandbox IDE: `Loaded custom plugins: IntelliJev (0.1.0)`.
- The `JevClient` request shape was checked against the published TypeSafe API reference
  (see §11). Endpoint, model alias and question types match the documentation. The
  response envelope has not been exercised.

### Built but not verified

- **Milestone 0 (feasibility): not done.** No Jev request and no chat-completions request
  from this build has reached a live API. The transport, batching and ACP gates in §7 are
  all still open, as are extraction quality and the UI integration gate.
- **Milestone 1 (shared foundation): partial.** Plugin shell, settings with Password Safe
  storage, source discovery, a typed decision client, file navigation and a local run-event
  log exist. Missing against this milestone: PSI extraction (a VFS walk with filename-token
  scoring stands in), any cache or cache invalidation, usage/metrics accounting, and
  cancellation.
- **Milestone 2 (File Finder): partial.** Ranking, token-aware selection and a previewable
  bundle exist in the Context tab, and the Coding Agent tab consumes that selection as
  its source context. There is no ACP bridge or second agent integration to pass it to, and
  no baseline or treatment run has been recorded, so §5 F1's acceptance criteria are unmet.
- **Milestone 3 (Bug Twins): placeholder.** The scan is a whole-file keyword regex, not the
  §5 F2 retrieval-then-inspection design. It takes no diff or commit input, has no editable
  rule description, offers no reproduction step and has no dismissal controls. It does
  correctly label results as candidates rather than confirmed defects.
- **Context Guard and Run Watch: stubs.** A free-text side-question tab and an in-memory
  event list respectively. There is no separate conversation store, no persistence and no
  checkpoints, so §5 F5 and F6 acceptance criteria are unmet and an interrupted run loses
  everything.
- **Tool Finder and Model Router (Milestone 5): absent**, as expected at this stage.

### Known defects, unfixed

1. The `Open IntelliJev` action has no `<add-to-group>` and therefore appears in no menu
   or toolbar. An earlier version targeted `ViewToolBar` and failed at startup with
   `SEVERE … group with id "ViewToolBar" should be instance of DefaultActionGroup but was
   class ActionStub`. That group was removed rather than retargeted, and the corrected
   build has not been launched interactively since, so the fix is unconfirmed.
2. `FindContextAction` and `FindBugTwinsAction` only open the tool window. They do not run
   the scans their labels promise and they ignore the editor selection that invoked them.
3. `IntelliJevPanel.open(file, line)` discards `line`, so Bug Twins results open the file
   at the top instead of at the reported line.
4. `plugin.xml` declares `<depends>com.intellij.java</depends>` and the build pulls in
   `bundledPlugin("com.intellij.java")` although nothing uses Java PSI. This needlessly
   narrows the set of IDEs the plugin will load in.
5. The generation request in `askModel` sets no timeout, and neither it nor `JevClient`
   has bounded concurrency, a retry policy or cancellation propagation, all of which §3.B
   requires. Requests run directly on pooled threads.
6. Run events live in a list on a project service and are lost on restart, which is why the
   §5 F6 checkpoint-recovery criterion cannot currently be met.
7. **Transport decision is still open.** §2's Stack row and the deck both propose routing
   Jev through OpenRouter; the code instead calls TypeSafe directly with a dedicated key,
   which also means two keys rather than one. Either move `JevClient` onto OpenRouter or
   amend the specification to adopt direct access. The document currently proposes one
   thing while the code does another.
