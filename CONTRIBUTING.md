# Contributing

## Scope

This repository is a Kotlin port of selected `pi-mono` packages. Contributions should preserve that framing:
- prefer semantic parity with the scoped upstream TypeScript behavior
- do not generalize beyond the supported slice without a clear need
- document intentional divergences explicitly

## Before Opening A PR

Run the verification gate:

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

If a change affects parity, inspect the pinned upstream reference under `reference/upstream/pi-mono/e3f6912`.

If you change behavior covered by the deterministic parity harness, refresh and verify the fixtures:

```bash
npm ci
./gradlew refreshParityFixtures
./gradlew parityTest
```

## Contribution Guidelines

- Keep Android API 31+ compatibility intact.
- Keep JDK 17 as the canonical development toolchain.
- Add tests with behavior changes.
- Prefer additive changes over API churn while the project is still `0.x`.
- Be explicit when a change is a deliberate divergence from upstream semantics.

## Reporting Gaps

Good issues include:
- the upstream file or behavior being targeted
- the Kotlin file or API involved
- whether the issue is a parity bug, unsupported scope, or intentional divergence
