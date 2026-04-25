# Changelog

All notable changes to this project will be documented in this file.

The format is intentionally simple while the port is still pre-`1.0`.

## Unreleased

- Added direct Google Gemini provider support in `pi-ai-core`, including the upstream `google` model catalog, `GEMINI_API_KEY` lookup, REST SSE streaming, tool calls, thinking blocks, and usage mapping.
- Improved repository documentation for open-source use.
- Added Maven publication metadata and signing-ready build scaffolding for `pi-ai-core` and `pi-agent-core`.
- Added fixture-based TS vs Kotlin parity tests and the supporting refresh workflow.

## 0.1.0-SNAPSHOT

Initial public bootstrap of the Kotlin port:
- `pi-ai-core` for direct Anthropic and Gemini API-key usage
- `pi-agent-core` on top of that scoped `pi-ai` port
- Android API 31+ consumer verification module
- tracked upstream TypeScript reference snapshot for parity work
