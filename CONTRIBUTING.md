# Contributing

## Scope

This repository is a Kotlin port of selected `pi-mono` packages. Contributions should preserve that framing:
- prefer semantic parity with the scoped upstream TypeScript behavior
- do not generalize beyond the supported slice without a clear need
- document intentional divergences explicitly

## Before opening a PR

Run the verification gate:

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

Coverage reports are still useful for local investigation, but they are not the blocking correctness gate for the upstream-ported modules:

```bash
./gradlew coverageReport
```

If a change touches parity-sensitive behavior, start with the pinned upstream reference that matches the module:
- `reference/upstream/pi-mono/e3f6912` for `pi-ai-core` and `pi-agent-core`
- `reference/upstream/pi-mono/9b28e18` for `pi-coding-agent-core`

If you change behavior covered by the deterministic parity harness, refresh and verify the fixtures:

```bash
npm ci
./gradlew refreshParityFixtures
./gradlew parityTest
```

Live provider integration tests are optional. Anthropic tests require `ANTHROPIC_API_KEY`; Gemini direct-provider checks require `GEMINI_API_KEY`. They are meant for direct provider verification, not the default contributor workflow.

## Contribution guidelines

- Keep Android API 31+ compatibility intact.
- Keep JDK 17 as the canonical development toolchain.
- Add tests with behavior changes.
- Prefer additive changes over API churn while the project is still `0.x`.
- Be explicit when a change is a deliberate divergence from upstream semantics.

## Reporting gaps

A useful issue includes:
- the upstream file or behavior being targeted
- the Kotlin file or API involved
- whether the issue is a parity bug, unsupported scope, or intentional divergence
