# Working on Discord Relay

## Engineering

- Keep the mod small.
- SRP, DRY, AHA, return-first and return-early.
- If there is a well known slim established library for what is requested - use it.
- If there is a standard library for what is requested - use it.
- Keep code consistent - it currently uses direct constructors with method checks instead of factory methods.
- Use Java `lowerCamelCase` fields and `this.field` when a local shadows an instance field.
- When a proposed change makes the existing code worse, explain the concrete tradeoff before editing.
- Ask for a decision when requirements conflict or the choice changes supported behavior.

## GTNH and Discord

- Target GTNH 2.9.0. Verify Forge lifecycle and mod integration behavior against that pack and its dependency versions. Recheck prior research against the owning source before relying on it.
- Keep Discord I/O and account file I/O off the Minecraft server thread. Send all outbound messages through one FIFO queue, including hang alerts and shutdown status. Keep retry and failure handling small. Isolate failures to the affected post so the queue can continue.
- Use one shaded, relocated JDA instance for chat, slash commands, and webhook posting. Let JDA handle rate limits, its own retries, and request and Gateway diagnostics. Log relay-specific decisions with Minecraft's Log4j and the mod ID.
- Run the repository formatter and tests for changed code. Cover concrete regressions and report which behavior remains untested on a live server. Leave CI changes for an explicit request.

## Git and releases

- Use `master` as the default branch. Commit coherent batches early and often, once the approach has settled. During an active back-and-forth, collect the changes into a batch before committing or pushing.
- Follow "why, not how" for commit message. Use the body for the problem, tradeoff, or user impact. Keep the appropriate `Co-authored-by` trailer for agent-authored changes.
- Push settled commits normally. Force-push only with explicit user confirmation. Use an explicit `--force-with-lease` against the verified remote tip when rewriting published history.
- Use GitHub CLI for releases. Keep README installation instructions pointed at GitHub Releases and publish the built mod JAR with the matching source tag.
