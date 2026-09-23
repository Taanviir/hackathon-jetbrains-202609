# IntelliJev

One IntelliJ plugin for context and reviewed edits. **Find context** ranks files with Jev,
local Laya, or local keywords and exposes `pack_context` to agents through IntelliJ's MCP
server. **Review changes** scans context, gets a coding-model proposal, and applies only
the edits you approve. The context engine source and evaluation live in
[`../context-packer/`](../context-packer/); the installable plugin is built here.
Design and acceptance criteria are in [BUILD_SPEC.md](./BUILD_SPEC.md); the pitch deck is
[on the reports site](https://taanviir.github.io/hackathon-jetbrains-202609/pitch/).

## Build and verification

Run `./gradlew test buildPlugin` here to compile both workspaces, run their tests, and
produce one `intellijev-0.1.0.zip`. The previous standalone workspaces each passed their
own CI suites; the unified build must pass CI before distribution. No live Jev or
chat-completions request has been run from the combined build.

## Run it in IntelliJ IDEA

1. Open `plugins/intellijev/` in IntelliJ IDEA 2025.2 or newer as a Gradle project.
2. Make sure a JVM is available to Gradle. Either open the project in IDEA and let its
   Gradle integration drive the build, or set `JAVA_HOME` to a JDK 21 install. On a
   machine with no `java` on `PATH` and no `JAVA_HOME`, the wrapper cannot start — Gradle
   will provision a JDK 21 for the *compile* toolchain but it needs a JVM to launch itself.
3. Allow Gradle to download JDK 21 if it asks (the build is configured to provision it
   automatically).
4. Run the `runIde` Gradle task (or the generated **Run Plugin** configuration).
5. In the sandbox IDE, open a project and select **View → Tool Windows → IntelliJev**.
   The one tool window has **Find context** and **Review changes** workspaces.

## Configure the keys

Jev and the coding model use **separate optional keys**, stored in different IntelliJ
Password Safe slots. Local candidate scans need neither key; enable each cloud feature
with its own key:

| Field in **Settings** tab | Stored as | Used for |
| --- | --- | --- |
| TypeSafe Jev key | `IntelliJev.TypeSafe.ApiKey` | `POST https://api.typesafe.ai/v1/systemone` — context ranking and the pre-proposal risk gate |
| Coding model API key | `IntelliJev.OpenRouter.ApiKey` or `IntelliJev.OpenAI.ApiKey` | Chat completions — explanations, review plan, and code replacements |

Pick **OpenRouter** or **OpenAI** as the coding model provider, enter a model ID
(OpenRouter example: `openai/gpt-4.1-mini`), fill the keys for the features you want, and press
**Save AI configuration**. Leave a key field blank to keep the one already stored.

The Jev key is read only when a scan runs. If it is missing, the Context tab silently
falls back to local keyword ranking and records `Jev is not configured; showing local
candidates` in the **Runs** tab — check Runs if Jev results never appear.

## Using it

Enter a task in the **Context** tab and press **Scan task context**. Then open
**Coding Agent**, press **Propose reviewed changes**, inspect the side-by-side
before/after (or **Open diff** for IntelliJ's native read-only viewer), and press
**Apply selected change** only when you approve it. Applied
changes are ordinary IDE document edits and can be undone with the standard Undo.

The model is not autonomous: it cannot run shell commands, install dependencies, create
arbitrary files, or apply an edit without your click. Proposed replacements are restricted
to existing small files already in the scanned context. Source snapshots are capped at
12,000 characters per file, replacements at 100,000 characters, and application is validated
against the source snapshot captured before the model request — if the file changed underneath you, the apply
is refused.

## What is implemented

The tool window has six tabs: **Context**, **Related Bugs**, **Coding Agent**, **Runs**,
**Side Question**, and **Settings**.

- Cross-language local source discovery (Kotlin, Java, TypeScript, Python, Go, Rust, C#,
  C/C++, web, config, and Markdown) with generated output, dependencies and
  credential-looking paths excluded.
- Jev-driven context ranking and a typed pre-proposal risk gate over
  `https://api.typesafe.ai/v1/systemone` using `score` questions on a three-level rubric.
- A review-first coding agent that sends curated source context to the configured model,
  accepts strict JSON whole-file replacements for existing files only, previews
  before/after, and applies only a selected approval.
- Candidate navigation, a thread-safe local in-memory run-event log, and an isolated
  side question saved only when you click **Save isolated note**. The note is kept in
  this project's local `.idea/workspace.xml` settings, separate from model prompts;
  saving rejects text over 20,000 characters with an error.
- Unit and IntelliJ platform tests cover `JevClient.score` parsing and answer-type rejection,
  selected-fix matching, completed-response validation, edit validation, apply/undo and stale or unwritable source rejection.

## Known issues

The following source fixes still need a visual interactive check: **Open IntelliJev**
is registered under **Tools**; the editor-popup actions start a scan using the current
selection; related-code candidates derive from the selected fix and navigate to their
line; and native **Open diff** renders the captured original and proposed replacement.
Superseded requests are cancelled, and Apply rechecks the file inside the write command
(covered by platform tests). Related-code matches are search leads, not verified bugs.

Still pending:

1. **`plugin.xml` declares `<depends>com.intellij.java</depends>` and the build pulls in
   `bundledPlugin("com.intellij.java")`, but no code uses Java PSI.** Source discovery is a
   plain VFS walk with filename-token scoring. The unused dependency restricts which IDEs
   the plugin will load in and can be dropped.
2. **The build specification is out of step with the code** on two points: it proposes
   "Java first" and PSI-based extraction (the implementation is Kotlin over VFS), and it
   proposes routing Jev through OpenRouter (the implementation calls TypeSafe directly
   with its own key). §2 of the spec now records both divergences.
3. **No end-to-end API run has been performed**, so Jev latency, answer shape, and model
   output quality are all still open. The spec's Jev transport gate in §7 is the thing to
   run first.

## Evaluation and demo evidence

The root reviewed-edit workflow has no live model quality benchmark. Context Packer's
separate [Jev evidence](../context-packer/spike/RESULTS.md) and
[local Laya evidence](../context-packer/eval/LAYA_RESULTS.md) measure source retrieval;
they do not establish generated-code correctness. The deck's 24→2 calls, 48→6 seconds,
and $0.42→$0.05 figures remain projections, as recorded in
[§9 of the build specification](./BUILD_SPEC.md).
