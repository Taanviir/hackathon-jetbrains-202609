# Help the Developer: JetBrains hackathon entry

**IntelliJev** is one IntelliJ IDEA plugin built for the JetBrains "Help the Developer"
challenge. It helps developers and coding agents find the right files, then lets the
developer review proposed edits before applying them. The goal is **agent efficiency**:
spend less time searching and more time on the code that matters.

The [IntelliJev plugin](./plugins/intellijev/) has two connected workspaces in one tool
window. **Find context** ranks relevant files with Jev, local Laya, or fast keywords;
its provider dropdown restores separate Jev and Laya settings. **Review changes**
passes selected context to a coding model, shows its proposed diff, and applies an
edit only after the developer approves it. Agents can also request ranked context
through IntelliJ's MCP server or a Claude Code hook. The source for the context
engine, its evaluation harness, and local Laya adapter lives in
[`plugins/context-packer/`](./plugins/context-packer/); it is included in the single
IntelliJev build.

On **70 held-out Koog tasks**, the Jev + BM25 context pipeline reached **0.69
recall@10**, compared with **0.53** for BM25 keywords. Those tasks rank Kotlin files
only; the installed plugin ranks every supported language unless
`CONTEXT_PACKER_EXTENSIONS=kt` is set. In a separate eight-task Claude Code pilot, the
Jev hook used **25% fewer agent turns** and **38% fewer searches**, with **15% lower
observed agent spend**. These results measure Jev file
retrieval and agent exploration, not Laya performance or generated-code quality.
Read the [published evaluation](https://taanviir.github.io/hackathon-jetbrains-202609/main/context-packer-eval/) and
[Laya setup](./plugins/context-packer/LAYA.md).

**[Pitch deck](https://taanviir.github.io/hackathon-jetbrains-202609/pitch/)** ·
**[Reports site](https://taanviir.github.io/hackathon-jetbrains-202609/)** ·
[How to add a report](./reports/README.md)

## Try IntelliJev

1. Build the plugin from `plugins/intellijev/` with `./gradlew test buildPlugin`.
   Install `build/distributions/intellijev-0.1.0.zip` into IntelliJ IDEA 2025.2+.
2. Open **View → Tool Windows → IntelliJev**. In **Find context**, choose Jev,
   Laya, or Fast keywords, enter a task, and select **Pack context**. Jev needs a
   TypeSafe key; Laya uses a local server; keywords needs neither.
3. Select the files you want and press **Review changes** to transfer the task and
   context. Configure a coding model, request a proposal, inspect the diff, and
   apply only a change you approve.

See the [plugin guide](./plugins/intellijev/) for configuration and the
[context-engine guide](./plugins/context-packer/) for provider details.

## Layout

```
plugins/context-packer/   Context engine source, eval harness, agent hook, Laya server
plugins/intellijev/       Single installable IntelliJev plugin and reviewed-edit source
reports/                  HTML reports published to GitHub Pages
docs/                     ideation notes and diagrams from the start of the hackathon
```

Build the installable IntelliJev ZIP with `./gradlew test buildPlugin` in
`plugins/intellijev/`. Its build includes the context engine source and tests from
`plugins/context-packer/`.

## Team

[@Taanviir](https://github.com/Taanviir), [@aikram42](https://github.com/aikram42),
[@mahahahad](https://github.com/mahahahad). Licensed under [MIT](./LICENSE).
