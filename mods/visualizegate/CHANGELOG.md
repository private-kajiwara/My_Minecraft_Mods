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

## [1.0.2] - 2026-06-24

### Fixed

- **Per-world data isolation (point cloud / gates / links).** Saved gate, link, and terrain data is
  keyed per world, but the single-player world key was derived from the world's **display name**
  (`getLevelName()`). Because newly created worlds all share the same default name ("New World"), their
  keys collided — so data captured in the first world appeared in every later same-named world. The key
  now uses the **save-folder name** (`getWorldPath(ROOT)`), which Minecraft makes unique per world
  ("New World", "New World (1)", …). Each single-player world now shows only its own observed point
  cloud, gates, and links; switching worlds (including without restarting) no longer carries data over.
  Multiplayer remains keyed per server address.
- **Migration is non-destructive.** No files are deleted or moved. Worlds whose folder name matches the
  old display name (typically the first "New World") keep their existing data; other previously-collided
  worlds start fresh, and the old display-name entries simply remain unread in the JSON (harmless
  orphans). Move them manually if you want to reassign old data.

## [1.0.1] - 2026-06-23

### Fixed

- **Point-cloud View tab layout at large GUI scales / fullscreen.** On high-resolution displays
  (e.g. 4K with GUI Scale "Auto", effective scale 8–9 → GUI height ≈ 240), the bottom **Point size**
  slider overlapped the footer **Done / Re-analyze** buttons. The View tab now adaptively compresses
  the toggle and slider row pitch to fit the available height, and — at extreme heights where even the
  minimum pitch cannot fit — makes the tab vertically scrollable (mouse wheel), using the same
  scroll + scissor approach as the Gates / Links tabs. All controls (OW/Nether scale, Dimension
  spacing, GPU detail, Point size) stay clear of the footer and operable at every GUI scale; normal
  window sizes are visually unchanged.

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
