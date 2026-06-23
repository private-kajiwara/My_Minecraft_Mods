# Changelog

All notable changes to VisualizeGate are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> **Versioning note:** Builds up to `0.131.7` were internal development versions.
> `1.0.0` is the first public release; the jump in version numbers reflects that promotion,
> not a large set of changes between `0.131.7` and `1.0.0`.
>
> **採番の注記:** `0.131.7` までは内部開発版です。`1.0.0` が初の公開リリースで、
> 採番のジャンプは開発版から公開版への昇格を表すもので、`0.131.7` と `1.0.0` の間に
> 大きな変更があるわけではありません。

## [1.0.0] - 2026-06-23

First public release. A fully client-side Fabric mod for visualizing and reasoning about Nether portals.

### Added

- **Gate highlighting / scanning** of nearby Nether portals.
- **Link prediction** with 5 states (Normal / One-sided / Offset / Unlinked / Conflict), color-coded
  per gate.
- **In-world annotations**: link lines, recommended build positions, existing-portal search radii, and
  conflict zones.
- **Back-calculate** (`/vg back-calculate`): compute the paired gate position from a target coordinate,
  with existing-portal pull-in warnings.
- **Conflict resolution** (`/vg resolving-conflict`): search and display a safe build position for a
  conflicting gate.
- **3D point-cloud view** (GPU3D) with View / Gates / Links tabs and a live dock radar; rotate / zoom / pan.
- **Dock HUD** showing the current dimension, FPS, gate state (5 colors), and conflict / offset counts.
- **Config screen** via ModMenu.
- **Guide** screen explaining the features.
- **Persistent memory** of observed gates and terrain, saved per world
  (`config/visualizegate-portals.json`, `config/visualizegate-terrain.json`).
- Client commands: `/vg`, `help`, `visualize`, `dock`, `names`, `gate-label`, `point-cloud`
  (`show` / `only` / `capture`), `detail`, `back-calculate`, `resolving-conflict`, `clean`.
- **Multi-version support**: Minecraft 1.21.10, 1.21.11, 26.1, 26.1.1, 26.1.2, and 26.2
  (single source via Stonecutter).
- **Translations**: English, Japanese, German, Russian, and Simplified Chinese.
  German, Russian, and Chinese are seed translations (not yet natively reviewed).

[1.0.0]: https://github.com/private-kajiwara/My_Minecraft_Mods/releases/tag/visualizegate-1.0.0
