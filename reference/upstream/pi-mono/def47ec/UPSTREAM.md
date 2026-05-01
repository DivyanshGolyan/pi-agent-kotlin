# Upstream snapshot

This directory contains a pinned upstream reference snapshot from `badlogic/pi-mono`.

Source:
- Repository: `https://github.com/badlogic/pi-mono`
- Default branch: `main`
- Commit: `def47ece9242295a928f9f62a51522ec80843aa6`

Tracked packages:
- `packages/agent`
- `packages/ai`
- `packages/coding-agent`

Why this snapshot is tracked:
- It is the behavioral oracle for the Kotlin port.
- It keeps parity work reproducible against a fixed upstream state.
- It lets contributors debug behavior locally without depending on a moving upstream branch.

The upstream project is MIT licensed. The upstream license text is copied into the sibling `LICENSE` file in this directory.
