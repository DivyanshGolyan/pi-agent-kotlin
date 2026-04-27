# pi-agent-kotlin

`pi-agent-kotlin` is a Kotlin port of selected [`pi-mono`](https://github.com/badlogic/pi-mono) packages.

This repo is a scoped port:
- it is not the original implementation
- it focuses on the slice needed for direct Anthropic/Gemini API-key usage and ChatGPT subscription-backed Codex access
- it checks behavior against pinned upstream TypeScript snapshots

## Current state

Modules:
- `pi-ai-core`: the `pi-ai` slice needed for direct Anthropic/Gemini API-key usage and OpenAI Codex subscription access
- `pi-agent-core`: the `pi-agent` slice built on top of `pi-ai-core`
- `pi-coding-agent-core`: the `coding-agent` session/runtime slice built on top of `pi-agent-core`

Supported today:
- direct Anthropic Messages API calls with API key auth
- direct Google Gemini API calls with API key auth
- OpenAI Codex Responses calls with ChatGPT OAuth credentials
- streaming text
- SSE and WebSocket provider transports for Codex
- thinking blocks
- tool calls and tool results
- custom agent message conversion hooks
- prompt caching
- image input
- the stateful agent loop and tool execution path
- coding-agent session persistence, branch trees, context rebuilding, compaction, and branch summarization
- coding-agent SDK/runtime basics: `createAgentSession`, `AgentSession`, `createAgentSessionRuntime`, runtime session switching, forking, and tree navigation

Still out of scope:
- providers beyond direct Anthropic, direct Google Gemini, and OpenAI Codex
- OAuth-based provider flows beyond the OpenAI Codex slice
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

This ships as `pi-coding-agent-core`. The supported slice has deterministic parity coverage.

## How parity works

The goal is semantic parity with the TypeScript packages for the slice this repo actually ports.

Behavior is checked against pinned upstream snapshots. They stay in the repo on purpose:
- [reference/upstream/pi-mono/e3f6912](reference/upstream/pi-mono/e3f6912) for `packages/ai` and `packages/agent`
- [reference/upstream/pi-mono/9b28e18](reference/upstream/pi-mono/9b28e18) for `packages/coding-agent`

Those snapshots drive:
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
- JDK `17`
- JVM target `11`
- Android `minSdk 31`

The runtime modules do not expose Android framework types, so the libraries stay usable in Android 12+ apps while the default build stays composite-friendly for downstream Android projects.

## Installation

The first public release is on Maven Central.

Use these coordinates:

```kotlin
implementation("io.github.divyanshgolyan:pi-ai-core:0.1.0")
implementation("io.github.divyanshgolyan:pi-agent-core:0.1.0")
implementation("io.github.divyanshgolyan:pi-coding-agent-core:0.1.0")
```

For local development:

```bash
./gradlew publishToMavenLocal
```

## Publishing

The publishable modules use Maven Central through the Sonatype Central Portal:

- `io.github.divyanshgolyan:pi-ai-core`
- `io.github.divyanshgolyan:pi-agent-core`
- `io.github.divyanshgolyan:pi-coding-agent-core`

Before publishing from a machine or CI:

1. Sign in to <https://central.sonatype.com> and verify the
   `io.github.divyanshgolyan` namespace.
2. Generate a Central Portal publishing token.
3. Create a GPG signing key and publish the public key.
4. Keep credentials in user Gradle properties or environment variables. Do not put them in this repo.

Environment variables:

```bash
export ORG_GRADLE_PROJECT_mavenCentralUsername="<central-portal-token-username>"
export ORG_GRADLE_PROJECT_mavenCentralPassword="<central-portal-token-password>"
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --export-secret-keys --armor <key-id>)"
export ORG_GRADLE_PROJECT_signingInMemoryKeyId="<key-id>"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="<key-password>"
```

For a snapshot build, keep `VERSION_NAME` ending in `-SNAPSHOT`. Snapshots also need to be enabled for the namespace in Central Portal:

```bash
./gradlew publishToMavenCentral
```

For a release, use a non-snapshot version and run the full verification gate first. To upload and publish manually from Central Portal:

```bash
./gradlew -PVERSION_NAME=0.1.0 publishToMavenCentral
```

To upload and publish automatically:

```bash
./gradlew -PVERSION_NAME=0.1.0 publishAndReleaseToMavenCentral
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
  :pi-ai-core:detekt \
  :pi-agent-core:detekt \
  :pi-coding-agent-core:detekt \
  :pi-ai-core:test \
  :pi-agent-core:test \
  :pi-coding-agent-core:test
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

The fixtures under `parity/fixtures` are generated from the pinned TypeScript snapshots and compared against normalized Kotlin output for deterministic scenarios only. Live Anthropic tests are separate.

## Live tests

The repo also contains live Anthropic integration tests. They are opt-in and require `ANTHROPIC_API_KEY`.

The regular verification command does not depend on them. Run them when you want to check the direct provider path against the live API.

Direct Gemini usage is covered by the same `pi-ai-core` APIs. Use `getModel(GOOGLE_PROVIDER, "...")` and pass `GEMINI_API_KEY` through `SimpleStreamOptions.apiKey` or the environment.

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
