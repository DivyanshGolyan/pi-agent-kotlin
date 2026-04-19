# pi-agent-kotlin

`pi-agent-kotlin` is a Kotlin port of selected [`pi-mono`](https://github.com/badlogic/pi-mono) packages.

This repo is explicit about what it is:
- a port, not the original implementation
- scoped to the slice needed for direct Anthropic API-key usage
- checked against a pinned upstream TypeScript snapshot

## What is here today

Modules:
- `pi-ai-core`: the `pi-ai` slice needed for direct Anthropic API-key usage
- `pi-agent-core`: the `pi-agent` slice built on top of `pi-ai-core`
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

What is still out of scope:
- non-Anthropic providers
- OAuth-based provider flows
- the full upstream `pi-ai` surface
- the full upstream `pi-agent` and `pi-ai` test matrix

## How parity works

The goal is semantic parity with the TypeScript packages for the supported slice.

Behavior is checked against the pinned snapshot in [reference/upstream/pi-mono/e3f6912](reference/upstream/pi-mono/e3f6912). That snapshot stays in the repo on purpose. It is the source we use for:
- behavior checks
- regression work
- parity decisions
- deterministic TS vs Kotlin fixture generation

That does **not** mean this repo is byte-for-byte identical to the TypeScript code, or that every public API matches one for one. It means the Kotlin port is trying to behave the same way where it matters for the supported scope.

Known intentional divergence:
- `AgentOptions.cacheRetention` is first-class in this Kotlin port. Upstream TypeScript supports `cacheRetention` in lower layers, but does not currently expose it on `AgentOptions`.

## Compatibility

Verified baseline:
- Kotlin `2.3.20`
- Gradle `9.3.1`
- Android Gradle Plugin `9.1.0`
- JDK `17`
- JVM target `11`
- Android `minSdk 31`

The runtime modules do not expose Android framework types. Android support is checked through the `android-consumer` module so the published libraries stay usable in Android 12+ apps.

## Installation

This project is not published yet. For now, install it locally with `publishToMavenLocal`.

Planned coordinates:

```kotlin
implementation("io.github.divyanshgolyan:pi-ai-core:<version>")
implementation("io.github.divyanshgolyan:pi-agent-core:<version>")
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

The fixtures under `parity/fixtures` are generated from the pinned TypeScript snapshot and compared against normalized Kotlin outputs for deterministic scenarios only. Live Anthropic tests are separate.

## Live tests

The repo also contains live Anthropic integration tests. They are opt-in and require `ANTHROPIC_API_KEY`.

The regular verification command does not depend on them. Run them only when you want to check the direct provider path against the real API.

## Versioning

The project uses `0.x` versions while the API and parity story are still settling.

What that means in practice:
- public APIs may still change
- unsupported upstream areas may be added incrementally
- parity gaps are documented rather than hidden

## Attribution

- Upstream reference: [`badlogic/pi-mono`](https://github.com/badlogic/pi-mono)
- Pinned snapshot used here: `e3f6912d49d14b6e6ffe96bb053644922004ecf3`
- Upstream snapshot and license are preserved under [reference/upstream/pi-mono/e3f6912](reference/upstream/pi-mono/e3f6912)

This project is not affiliated with the upstream maintainers.
