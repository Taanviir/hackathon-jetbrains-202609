# Help the Developer: JetBrains hackathon entry

**IntelliJev** is one IntelliJ IDEA plugin built for the JetBrains "Help the Developer"
challenge. It helps developers and coding agents find the right files, then lets the
developer review proposed edits before applying them. The goal is **agent efficiency**:
spend less time searching and more time on the code that matters.

The [IntelliJev plugin](./plugins/intellijev/) includes a context-finding workspace
with Jev, local Laya, and keyword providers, plus an MCP tool and a review-first coding
workspace. The Jev + BM25 pipeline reached recall@10 of 0.69 versus 0.53 for keyword
search on 70 held-out Koog tasks; these are Jev retrieval results, not Laya results.
See [context engine details](./plugins/context-packer/) and [Laya setup](./plugins/context-packer/LAYA.md).

**[Reports site](https://taanviir.github.io/hackathon-jetbrains-202609/)**: eval results,
explainers, and the pitch deck. To add a report, see [reports/README.md](./reports/README.md).

## Layout

```
plugins/context-packer/   Context engine source, eval harness, agent hook, Laya server
plugins/intellijev/       Unified IntelliJev plugin build and reviewed-edit source
reports/                  HTML reports published to GitHub Pages
docs/                     ideation notes and diagrams from the start of the hackathon
```

Build the installable IntelliJev ZIP with `./gradlew test buildPlugin` in
`plugins/intellijev/`. Its build includes the context engine source and tests from
`plugins/context-packer/`.

## Team

[@Taanviir](https://github.com/Taanviir), [@aikram42](https://github.com/aikram42),
[@mahahahad](https://github.com/mahahahad). Licensed under [MIT](./LICENSE).
