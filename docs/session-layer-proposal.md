# `packages/coding-agent` port proposal

This note records a stricter proposal for what `pi-agent-kotlin` should port next.

The governing rule is:

- we may choose to port only parts of an upstream package
- but any part we do port must be a faithful, closed upstream slice
- no invented replacement abstractions inside that slice
- no widened or narrowed contract for that slice

## Current position

`pi-agent-kotlin` currently ports:

- upstream `packages/ai` as `pi-ai-core`
- upstream `packages/agent` as `pi-agent-core`

The next upstream package relevant to long-lived agent products is:

- upstream `packages/coding-agent`

The question is not whether `packages/coding-agent` is useful. It is.
The question is which parts of that package form closed slices that can be ported
faithfully without redesigning the contract.

## Investigation summary

I inspected the pinned upstream files under:

- `reference/upstream/pi-mono/9b28e18/packages/coding-agent/src/core/session-manager.ts`
- `reference/upstream/pi-mono/9b28e18/packages/coding-agent/src/core/messages.ts`
- `reference/upstream/pi-mono/9b28e18/packages/coding-agent/src/core/compaction/*`
- `reference/upstream/pi-mono/9b28e18/packages/coding-agent/src/core/agent-session.ts`
- `reference/upstream/pi-mono/9b28e18/packages/coding-agent/src/core/agent-session-runtime.ts`
- `reference/upstream/pi-mono/9b28e18/packages/coding-agent/src/core/agent-session-services.ts`
- `reference/upstream/pi-mono/9b28e18/packages/coding-agent/src/core/sdk.ts`
- `reference/upstream/pi-mono/9b28e18/packages/coding-agent/docs/session.md`
- `reference/upstream/pi-mono/9b28e18/packages/coding-agent/docs/compaction.md`
- `reference/upstream/pi-mono/9b28e18/packages/coding-agent/docs/sdk.md`
- `reference/upstream/pi-mono/e3f6912/packages/agent/src/types.ts`

The key result is:

- `session-manager` + `messages` + `compaction` is a relatively clean upstream slice
- this repository needed an explicit Kotlin custom-message extension point in
  `pi-agent-core` before that slice could be treated as closed enough
- `agent-session` is not a small isolated slice; it pulls in a much larger chunk of `packages/coding-agent`

## Existing-port alignment prerequisite

If better upstream alignment requires changing the existing Kotlin ports, that is the
right thing to do.

The current Kotlin `pi-agent-core` needed one alignment step against upstream
`packages/agent` before `packages/coding-agent` could land cleanly:

- upstream `packages/agent` defines `AgentMessage` as `Message | CustomAgentMessages[...]`
- upstream `packages/coding-agent/src/core/messages.ts` extends that union with
  `bashExecution`, `custom`, `branchSummary`, and `compactionSummary`
- Kotlin cannot mirror TypeScript declaration merging directly, so the equivalent
  contract is:
  custom agent messages implement an explicit marker interface and the default
  AgentMessage-to-LLM path delegates those messages through an explicit conversion hook

That was the main contract mismatch that mattered directly for `packages/coding-agent`.

That shape is close enough in spirit to upstream `CustomAgentMessages` for the
`coding-agent` message layer to be portable without inventing app-local session
abstractions.

So the stricter rule for this repository should be:

- we may change an existing Kotlin port when that change is required to match the
  upstream contract more closely
- we should prefer that over freezing an earlier divergence and then inventing
  `coding-agent`-specific workaround abstractions on top

With that alignment in place, the remaining question is still the same:

- which `packages/coding-agent` files form a faithful, closed upstream slice

That is why this document still treats the session/context slice as the first
useful next port.

This is not app-local invention. It is correcting an existing divergence so the next
upstream slice can land faithfully.

## Closed slice 1: session persistence and context rebuilding

This is the cleanest next slice to port.

### Upstream files in scope

- `src/core/session-manager.ts`
- `src/core/messages.ts`

### What this slice provides upstream

- session JSONL file format
- session entry types
- tree-structured session history
- migration logic for session file versions
- context rebuilding from session entries
- coding-agent-specific message extensions used in session context

### Why this slice is closed enough

`session-manager.ts` depends mainly on:

- `messages.ts`
- `config.ts` path helpers
- standard filesystem/path utilities
- `@mariozechner/pi-agent-core`
- `@mariozechner/pi-ai`

That is a real upstream slice.

In this repository it became closed enough only after aligning `pi-agent-core`
with the upstream extensible `AgentMessage` contract in a Kotlin-appropriate way.

### Recommendation

Port this slice on top of the now-aligned `pi-agent-core` message contract.

## Closed slice 2: compaction

This is also a valid upstream slice, but it depends on slice 1.

### Upstream files in scope

- `src/core/compaction/compaction.ts`
- `src/core/compaction/utils.ts`

Optional later addition from the same family:

- `src/core/compaction/branch-summarization.ts`

### What this slice provides upstream

- compaction trigger logic
- cut-point selection
- summary generation
- compaction entry creation
- message serialization for summarization
- file-operation extraction used in summaries

### Why this slice is closed enough

The compaction code depends on:

- `messages.ts`
- `session-manager.ts`
- `@mariozechner/pi-agent-core`
- `@mariozechner/pi-ai`

That is still a coherent upstream slice.

### Recommendation

Port this after the session-manager/messages slice.

## Not a small closed slice: `AgentSession`

`src/core/agent-session.ts` is important, but it is not a narrow next step.

It imports a large cross-section of `packages/coding-agent`, including:

- `bash-executor`
- `defaults`
- `export-html/*`
- `extensions/*`
- `model-registry`
- `prompt-templates`
- `resource-loader`
- `settings-manager`
- `slash-commands`
- `source-info`
- `system-prompt`
- `tools/*`
- interactive theme/config helpers

This means:

- if we decide to port the `AgentSession` slice, we should treat it as a broader
  `coding-agent` runtime slice
- we should not pretend it is just “sessions”
- we should not rewrite it into a narrower Kotlin-only abstraction and still call
  it a faithful port

## Consequence for the proposal

The earlier proposal was too loose because it described invented concepts such as:

- `SessionStore`
- `CompactionStrategy`
- `CompactionExecutor`
- `SessionSnapshot`
- `SessionState`

Those may be reasonable design ideas, but they are not the right basis for a
port proposal under the stricter contract rule.

The proposal should instead name exact upstream files and slices.

## Recommended port order

### Step 1

Port the session persistence/context slice from `packages/coding-agent`:

- `src/core/session-manager.ts`
- `src/core/messages.ts`

### Step 2

Port the compaction slice that depends on it:

- `src/core/compaction/compaction.ts`
- `src/core/compaction/utils.ts`

Optional later addition from the same compaction family:

- `src/core/compaction/branch-summarization.ts`

### Step 3

Only after that, decide whether to port the larger `AgentSession` runtime slice.

If yes, treat it as a broader faithful slice that includes at least:

- `src/core/agent-session.ts`
- `src/core/sdk.ts`
- `src/core/agent-session-runtime.ts`
- `src/core/agent-session-services.ts`
- their direct transitive dependencies

That is no longer a tiny slice. It is a substantial chunk of `packages/coding-agent`.

## What this means for Claune

For Claune’s needs, the first useful faithful slices are:

1. session persistence / session tree / context rebuilding
2. compaction

Those directly support:

- durable sessions
- continuing later
- compacted model context

They do not yet provide the whole upstream `AgentSession` runtime contract.

That distinction should stay explicit in `pi-agent-kotlin` docs and naming.

## Documentation rule going forward

When this repository proposes a future port, the proposal should:

- name the upstream package
- name the exact upstream files in scope
- state whether the slice is closed enough to port faithfully
- avoid inventing replacement abstractions unless they are clearly marked as app-local work, not part of the upstream port
