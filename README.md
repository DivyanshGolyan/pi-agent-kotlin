# pi-agent-kotlin

`pi-agent-kotlin` is an open-source Kotlin port of selected [`pi-mono`](https://github.com/badlogic/pi-mono) packages.

This repository is intentionally honest about what it is:
- a port, not the original implementation
- scoped to the subset needed for direct Anthropic API-key usage
- verified against a pinned upstream TypeScript snapshot

## Status

Current modules:
- `pi-ai-core`: scoped `pi-ai` port for direct Anthropic API-key usage
- `pi-agent-core`: scoped `pi-agent` port on top of `pi-ai-core`
- `android-consumer`: Android API 31+ verification module

Current support:
- direct Anthropic Messages API with API key auth
- streaming text responses
- thinking blocks
- tool calls and tool results
- prompt caching
- image input
- stateful agent loop and tool execution

Not implemented yet:
- non-Anthropic providers
- OAuth-based provider flows
- the full upstream `pi-ai` surface
- the full upstream `pi-agent` / `pi-ai` test matrix

## Parity Model

This project targets the TypeScript packages semantically.

Behavior is checked against a pinned upstream snapshot stored in [reference/upstream/pi-mono/e3f6912](reference/upstream/pi-mono/e3f6912). That snapshot is tracked intentionally as the porting oracle for:
- behavior inspection
- regression checks
- parity decisions
- fixture generation for deterministic TS vs Kotlin parity tests

This does **not** mean the Kotlin implementation is byte-for-byte or API-for-API identical. It means the goal is faithful behavior for the supported slice.

Known intentional divergence:
- `AgentOptions.cacheRetention` is first-class in this Kotlin port. Upstream TypeScript supports `cacheRetention` in lower layers but currently does not surface it on `AgentOptions`.

## Compatibility

Verified baseline:
- Kotlin `2.3.20`
- Gradle `9.3.1`
- Android Gradle Plugin `9.1.0`
- JDK `17`
- JVM target `11`
- Android `minSdk 31`

The runtime modules do not expose Android framework types, but they are verified through the Android consumer module so the published libraries stay usable in Android 12+ apps.

## Installation

Publication is being prepared for Maven Central. Until then, use `publishToMavenLocal`.

Coordinates planned for release:

```kotlin
implementation("io.github.divyanshgolyan:pi-ai-core:<version>")
implementation("io.github.divyanshgolyan:pi-agent-core:<version>")
```

Local install:

```bash
./gradlew publishToMavenLocal
```

## Quick Start: `pi-ai-core`

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

## Quick Start: `pi-agent-core`

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

## Android Notes

- The repository verifies Android compatibility through `android-consumer`.
- The core libraries are published as JVM libraries and are intended to be consumed from Android applications.
- The canonical toolchain for this project is JDK 17, not JDK 21.

## Development

Primary verification command:

```bash
./gradlew --no-configuration-cache \
  apiCheck \
  koverVerify \
  dokkaGenerate \
  :pi-ai-core:ktlintCheck \
  :pi-agent-core:ktlintCheck \
  :android-consumer:ktlintCheck \
  :pi-ai-core:detekt \
  :pi-agent-core:detekt \
  :android-consumer:detekt \
  :pi-ai-core:test \
  :pi-agent-core:test \
  :android-consumer:testDebugUnitTest
```

Useful local tasks:

```bash
./gradlew publishToMavenLocal
./gradlew parityTest
./gradlew refreshParityFixtures
./gradlew :pi-ai-core:dokkaGeneratePublicationHtml
./gradlew :pi-agent-core:dokkaGeneratePublicationHtml
```

Parity workflow:

```bash
npm ci
./gradlew refreshParityFixtures
./gradlew parityTest
```

The committed fixtures under `parity/fixtures` are generated from the pinned TypeScript snapshot and compared against normalized Kotlin outputs for deterministic scenarios only. Live Anthropic tests remain separate.

## Versioning

The project uses `0.x` versions while parity and API stability are still settling.

Expectation during `0.x`:
- public APIs may still change
- unsupported upstream areas may be added incrementally
- parity gaps are documented rather than hidden

## Attribution

- Upstream reference: [`badlogic/pi-mono`](https://github.com/badlogic/pi-mono)
- Pinned snapshot used here: `e3f6912d49d14b6e6ffe96bb053644922004ecf3`
- Upstream snapshot and license are preserved under [reference/upstream/pi-mono/e3f6912](reference/upstream/pi-mono/e3f6912)

This project is not affiliated with or endorsed by the upstream maintainers.
