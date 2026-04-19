# Upstream snapshot

This directory contains a pinned upstream reference snapshot from `badlogic/pi-mono`.

Source:
- Repository: `https://github.com/badlogic/pi-mono`
- Commit: `9b28e185dbb36f1e60ed267c5401937ebab99cb3`

Tracked packages:
- `packages/coding-agent`

Why this snapshot is tracked:
- It is the reference snapshot for the proposed Kotlin session layer work.
- It keeps the `packages/coding-agent` port reproducible against a fixed upstream state.
- It lets contributors inspect upstream session-layer behavior locally without depending on a moving branch.

The upstream project is MIT licensed. The upstream license text is copied into the sibling `LICENSE` file in this directory.
