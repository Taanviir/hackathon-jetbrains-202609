# IntelliJev

An IntelliJ IDEA plugin proof of concept for context-aware, review-first coding
assistance. Jev (TypeSafe's System One model) does the fast structured ranking and
risk calls; a generative model is called only where prose or a code replacement is
actually needed.

- Build specification and acceptance criteria: [INTELLIJEV_BUILD_SPEC.md](./INTELLIJEV_BUILD_SPEC.md)
- Ideation history, team, links, candidate ideas, diagrams index: [docs/IDEATION.md](./docs/IDEATION.md)
- Brief: [Help the Developer.pdf](./Help%20the%20Developer.pdf)

## Build status

Verified on 22 September 2026:

- `gradlew test` succeeds — `compileKotlin`, `instrumentCode`, `jar` and both unit
  tests in `JevClientTest` pass.
- `gradlew buildPlugin` produces `build/distributions/intellijev-0.1.0.zip`.
- The plugin loads in the sandbox IDE (`Loaded custom plugins: IntelliJev (0.1.0)`).

**Not yet verified:** no Jev or chat-completions request from this build has been run
against a live API, and the fixed plugin has not been launched interactively since the
last action-registration error described under *Known issues*. Treat every feature below
as implemented-but-unproven until that run happens.

## Run it in IntelliJ IDEA

1. Open this folder in IntelliJ IDEA 2025.1 or newer as a Gradle project.
2. Make sure a JVM is available to Gradle. Either open the project in IDEA and let its
   Gradle integration drive the build, or set `JAVA_HOME` to a JDK 21 install. On a
   machine with no `java` on `PATH` and no `JAVA_HOME`, the wrapper cannot start — Gradle
   will provision a JDK 21 for the *compile* toolchain but it needs a JVM to launch itself.
3. Allow Gradle to download JDK 21 if it asks (the build is configured to provision it
   automatically).
4. Run the `runIde` Gradle task (or the generated **Run Plugin** configuration).
5. In the sandbox IDE, open a project and select **View → Tool Windows → IntelliJev**.

## Configure the keys

**Two separate keys are required.** They are stored in two different IntelliJ Password
Safe slots and are not interchangeable:

| Field in **Settings** tab | Stored as | Used for |
| --- | --- | --- |
| TypeSafe Jev key | `IntelliJev.TypeSafe.ApiKey` | `POST https://api.typesafe.ai/v1/systemone` — context ranking and the pre-proposal risk gate |
| Coding model API key | `IntelliJev.OpenRouter.ApiKey` or `IntelliJev.OpenAI.ApiKey` | Chat completions — explanations, review plan, and code replacements |

Pick **OpenRouter** or **OpenAI** as the coding model provider, enter a model ID
(OpenRouter example: `openai/gpt-4.1-mini`), fill both key fields, and press
**Save AI configuration**. Leave a key field blank to keep the one already stored.

The Jev key is read only when a scan runs. If it is missing, the Context tab silently
falls back to local keyword ranking and records `Jev is not configured; showing local
candidates` in the **Runs** tab — check Runs if Jev results never appear.

## Using it

Enter a task in the **Context** tab and press **Scan task context**. Then open
**Coding Agent**, press **Propose reviewed changes**, inspect the side-by-side
before/after, and press **Apply selected change** only when you approve it. Applied
changes are ordinary IDE document edits and can be undone with the standard Undo.

The model is not autonomous: it cannot run shell commands, install dependencies, create
arbitrary files, or apply an edit without your click. Proposed replacements are restricted
to existing files already in the scanned context, capped at 12 KB each, and validated
against the file content at proposal time — if the file changed underneath you, the apply
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
- Candidate navigation, a local in-memory run-event log, and an editable isolated side
  question.
- Two unit tests covering `JevClient.score` parsing and answer-type rejection.

## Known issues

Recorded so the next session does not have to rediscover them. None are fixed.

1. **The `Open IntelliJev` action is not placed in any menu or toolbar.** It has no
   `<add-to-group>` in `plugin.xml`. The earlier version targeted `ViewToolBar` and failed
   at startup with `SEVERE … group with id "ViewToolBar" should be instance of
   DefaultActionGroup but was class ActionStub`; that group was removed rather than
   retargeted, so the action is now only reachable through Find Action or
   **View → Tool Windows → IntelliJev**. The current build has not been launched
   interactively to confirm the error is gone.
2. **The editor-popup actions do not do what they say.** `Find task context` and
   `Find related bugs from selected fix` are wired to subclasses of `OpenPanelAction` that
   just open the tool window — they do not start a scan or consume the selection.
3. **Bug Twins does not navigate to the line.** `IntelliJevPanel.open(file, line)` ignores
   its `line` argument, so selecting a candidate opens the file at the top.
4. **`plugin.xml` declares `<depends>com.intellij.java</depends>` and the build pulls in
   `bundledPlugin("com.intellij.java")`, but no code uses Java PSI.** Source discovery is a
   plain VFS walk with filename-token scoring. The unused dependency restricts which IDEs
   the plugin will load in and can be dropped.
5. **The build specification is out of step with the code** on two points: it proposes
   "Java first" and PSI-based extraction (the implementation is Kotlin over VFS), and it
   proposes routing Jev through OpenRouter (the implementation calls TypeSafe directly
   with its own key). §2 of the spec now records both divergences.
6. **No end-to-end API run has been performed**, so Jev latency, answer shape, and model
   output quality are all still open. The spec's Jev transport gate in §7 is the thing to
   run first.

## Evaluation and demo evidence

Still entirely outstanding. The measurement plan, baselines, and the caveat that the
deck's 24→2 calls, 48→6 seconds, and $0.42→$0.05 figures are projections rather than
results are in [§9 of the build specification](./INTELLIJEV_BUILD_SPEC.md).
