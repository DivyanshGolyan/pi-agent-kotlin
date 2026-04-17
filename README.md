# pi-agent-kotlin

Kotlin port of selected `pi-mono` packages for Android-targeted use.

Current scope:
- `pi-ai-core`: minimal `pi-ai` subset required for direct Anthropic API-key usage
- `pi-agent-core`: agent runtime and stateful wrapper on top of that subset
- `android-consumer`: Android API 31+ consumer verification module

The Kotlin implementation is verified against a pinned upstream TypeScript snapshot stored under [reference/upstream/pi-mono/e3f6912](/Users/divyanshgolyan/code/personal/pi-agent-kotlin/reference/upstream/pi-mono/e3f6912). That snapshot is tracked intentionally as the behavioral oracle for parity checks and porting decisions.

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

