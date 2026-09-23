# Help the Developer: JetBrains hackathon entry

Two IntelliJ IDEA plugins built for the JetBrains "Help the Developer" challenge (build an
AI-driven app or plugin that makes developers' lives easier). Our theme is **agent
efficiency**: coding agents spend most of their time finding the right files.

| Plugin | What it does |
| --- | --- |
| **[Context Packer](./plugins/context-packer/)** | Ranks the files a coding task needs with TypeSafe's Jev model plus BM25, in a tool window, an MCP tool, and a Claude Code hook. On 70 held-out Koog commits it reaches recall@10 of 0.69 against 0.53 for keyword search. Also offers local Laya and keyword providers ([Laya setup](./plugins/context-packer/LAYA.md)). |
| **[IntelliJev](./plugins/intellijev/)** | Review-first coding workflow: scans task context, proposes edits through a coding model, and applies only the changes you approve. |

**[Reports site](https://taanviir.github.io/hackathon-jetbrains-202609/)**: eval results,
explainers, and the pitch deck. To add a report, see [reports/README.md](./reports/README.md).

## Layout

```
plugins/context-packer/   Context Packer plugin, eval harness, agent hook, Laya server
plugins/intellijev/       IntelliJev plugin and its build specification
reports/                  HTML reports published to GitHub Pages
docs/                     ideation notes and diagrams from the start of the hackathon
```

Each plugin has its own Gradle build: run `./gradlew test buildPlugin` inside its folder.

## Team

[@Taanviir](https://github.com/Taanviir), [@aikram42](https://github.com/aikram42),
[@mahahahad](https://github.com/mahahahad). Licensed under [MIT](./LICENSE).
