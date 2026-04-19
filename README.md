# pi-agent-kotlin

`pi-agent-kotlin` is a Kotlin port of selected [`pi-mono`](https://github.com/badlogic/pi-mono) packages.

This repo is a scoped port:
- it is not the original implementation
- it focuses on the slice needed for direct Anthropic API-key usage
- it checks behavior against pinned upstream TypeScript snapshots

## What is here today

Modules:
- `pi-ai-core`: the `pi-ai` slice needed for direct Anthropic API-key usage
- `pi-agent-core`: the `pi-agent` slice built on top of `pi-ai-core`
- `pi-coding-agent-core`: the `coding-agent` session/runtime slice built on top of `pi-agent-core`
- `android-consumer`: an Android API 31+ verification module

What works:
- direct Anthropic Messages API calls with API key auth
- streaming text
- thinking blocks
- tool calls and tool results
- custom agent message conversion hooks
- prompt caching
- image input
- the stateful agent loop and tool execution path
- coding-agent session persistence, branch trees, context rebuilding, compaction, and branch summarization
- coding-agent SDK/runtime basics: `createAgentSession`, `AgentSession`, `createAgentSessionRuntime`, runtime session switching, forking, and tree navigation

What is still out of scope:
- non-Anthropic providers
- OAuth-based provider flows
- the upstream extension runtime, built-in coding tools, bash executor, and HTML export surface
- the full upstream `pi-ai` surface
- the full upstream CLI/app surface built around `packages/coding-agent`
- the full upstream test matrix for `pi-ai`, `pi-agent`, and `coding-agent`

## Coding-agent status

The repo now includes the main `packages/coding-agent` SDK/runtime pieces behind durable sessions:

- `src/core/session-manager.ts`
- `src/core/messages.ts`
- `src/core/compaction/compaction.ts`
- `src/core/compaction/utils.ts`
- `src/core/compaction/branch-summarization.ts`
- `src/core/sdk.ts`
- `src/core/agent-session.ts`
- `src/core/agent-session-services.ts`
- `src/core/agent-session-runtime.ts`

That work ships as `pi-coding-agent-core`, with deterministic parity coverage for the supported Kotlin slice.

## How parity works

The goal is semantic parity with the TypeScript packages for the supported slice.

Behavior is checked against pinned upstream snapshots that stay in the repo on purpose:
- [reference/upstream/pi-mono/e3f6912](reference/upstream/pi-mono/e3f6912) for `packages/ai` and `packages/agent`
- [reference/upstream/pi-mono/9b28e18](reference/upstream/pi-mono/9b28e18) for `packages/coding-agent`

We keep those snapshots in the repo because they drive:
- behavior checks
- regression work
- parity decisions
- deterministic TS vs Kotlin fixture generation

The coding-agent parity suite currently covers the supported Kotlin slice only:
- session-manager persistence, trees, and context rebuilding
- compaction and branch summarization
- `createAgentSession`, `AgentSession`, and `createAgentSessionRuntime`
- runtime session switching, forking, and JSONL import

This does **not** mean the repo is byte-for-byte identical to the TypeScript code, or that every public API matches one for one. It means the Kotlin port tries to behave the same way across the supported slice.

One known divergence:
- `AgentOptions.cacheRetention` is first-class in this Kotlin port. Upstream TypeScript supports `cacheRetention` in lower layers, but does not currently expose it on `AgentOptions`.

## Compatibility

Verified baseline:
- Kotlin `2.3.20`
- Gradle `9.3.1`
- Android Gradle Plugin `9.1.0`
- JDK `17`
- JVM target `11`
- Android `minSdk 31`

The runtime modules do not expose Android framework types. Android support is checked through `android-consumer`, so the published libraries stay usable in Android 12+ apps.

## Installation

This project is not published yet. For now, install it locally with `publishToMavenLocal`.

Planned coordinates:

```kotlin
implementation("io.github.divyanshgolyan:pi-ai-core:<version>")
implementation("io.github.divyanshgolyan:pi-agent-core:<version>")
implementation("io.github.divyanshgolyan:pi-coding-agent-core:<version>")
```

Local install:

```bash
./gradlew publishToMavenLocal
```

## Quick start: `pi-ai-core`

```kotlin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import pi.ai.core.ANTHROPIC_PROVIDER
import pi.ai.core.Context
import pi.ai.core.SimpleStreamOptions
import pi.ai.core.TextContent
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent
import pi.ai.core.getModel
import pi.ai.core.streamSimple

fun main() = runBlocking {
    val model = requireNotNull(getModel(ANTHROPIC_PROVIDER, "claude-haiku-4-5"))

    val context = Context(
        systemPrompt = "You are concise.",
        messages = listOf(
            UserMessage(
                content = UserMessageContent.Text("Say hello from Kotlin."),
                timestamp = System.currentTimeMillis(),
            ),
        ),
    )

    val stream = streamSimple(
        model = model,
        context = context,
        options = SimpleStreamOptions(apiKey = System.getenv("ANTHROPIC_API_KEY")),
    )

    stream.asFlow().collect { event ->
        println(event)
    }

    val finalMessage = stream.result()
    println(finalMessage)
}
```

## Quick start: `pi-agent-core`

```kotlin
import kotlinx.coroutines.runBlocking
import pi.agent.core.Agent
import pi.agent.core.AgentOptions
import pi.agent.core.InitialAgentState
import pi.ai.core.ANTHROPIC_PROVIDER
import pi.ai.core.CacheRetention
import pi.ai.core.getModel

fun main() = runBlocking {
    val model = requireNotNull(getModel(ANTHROPIC_PROVIDER, "claude-haiku-4-5"))

    val agent = Agent(
        AgentOptions(
            initialState = InitialAgentState(
                systemPrompt = "You are concise and practical.",
                model = model,
            ),
            getApiKey = { System.getenv("ANTHROPIC_API_KEY") },
            cacheRetention = CacheRetention.SHORT,
        ),
    )

    agent.subscribe { event, _ ->
        println(event)
    }

    agent.prompt("Give me three bullet points about Android library design.")
}
```

Custom agent messages:

- custom messages can implement `pi.agent.core.CustomAgentMessage`
- use `AgentOptions.customMessageToLlm` to convert them into LLM-compatible
  `user`/`assistant`/`toolResult` messages or filter them out before provider calls

## Android notes

- The repository verifies Android compatibility through `android-consumer`.
- The core libraries are published as JVM libraries and are intended to be consumed from Android applications.
- The canonical toolchain for this project is JDK 17, not JDK 21.

## Development

Main verification command:

```bash
./gradlew --no-configuration-cache \
  apiCheck \
  parityTest \
  dokkaGenerate \
  :pi-ai-core:ktlintCheck \
  :pi-agent-core:ktlintCheck \
  :pi-coding-agent-core:ktlintCheck \
  :android-consumer:ktlintCheck \
  :pi-ai-core:detekt \
  :pi-agent-core:detekt \
  :pi-coding-agent-core:detekt \
  :android-consumer:detekt \
  :pi-ai-core:test \
  :pi-agent-core:test \
  :pi-coding-agent-core:test \
  :android-consumer:testDebugUnitTest
```

Coverage reports are still available, but they do not block the main verification gate:

```bash
./gradlew coverageReport
```

Useful local tasks:

```bash
./gradlew publishToMavenLocal
./gradlew parityTest
./gradlew refreshParityFixtures
./gradlew :pi-ai-core:dokkaGeneratePublicationHtml
./gradlew :pi-agent-core:dokkaGeneratePublicationHtml
./gradlew :pi-coding-agent-core:dokkaGeneratePublicationHtml
```

Parity workflow:

```bash
npm ci
./gradlew refreshParityFixtures
./gradlew parityTest
```

The fixtures under `parity/fixtures` are generated from the pinned TypeScript snapshots and compared against normalized Kotlin outputs for deterministic scenarios only. Live Anthropic tests are separate.

## Live tests

The repo also contains live Anthropic integration tests. They are opt-in and require `ANTHROPIC_API_KEY`.

The regular verification command does not depend on them. Run them when you want to check the direct provider path against the real API.

## Versioning

The project uses `0.x` versions while the API and parity story are still settling.

What that means in practice:
- public APIs may still change
- unsupported upstream areas may be added incrementally
- parity gaps are documented rather than hidden

## Attribution

- Upstream reference: [`badlogic/pi-mono`](https://github.com/badlogic/pi-mono)
- Pinned snapshots used here:
  `e3f6912d49d14b6e6ffe96bb053644922004ecf3` for `pi-ai` and `pi-agent`
  `9b28e18` for `coding-agent`
- Upstream snapshots and licenses are preserved under
  [reference/upstream/pi-mono/e3f6912](reference/upstream/pi-mono/e3f6912) and
  [reference/upstream/pi-mono/9b28e18](reference/upstream/pi-mono/9b28e18)

This project is not affiliated with the upstream maintainers.
