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

## [1.0.15] - 2026-08-04

### Fixed

- **Point cloud, gates and links were shared by every single-player world (1.0.2 – 1.0.14).** The
  world key used to separate saved data collapsed to the constant `sp:.` for *all* single-player
  worlds, so whatever was observed in the first world you played kept showing up in every other
  world. The cause was in 1.0.2's own fix: `LevelResource.ROOT` carries the id `"."`, not an empty
  string, so `getWorldPath(ROOT).getFileName()` always returned `"."` (and, not being blank, never
  fell through to the display-name fallback). The path is now normalized before the folder name is
  taken, so the key is the actual save-folder name, which Minecraft keeps unique. Point cloud
  terrain, gates and links are all fixed by this single change, on both the full-screen point cloud
  and the dock radar.
- **The previous world's point cloud could linger for a few frames after switching worlds.** The
  stores were already keyed per world, but the finished point-cloud snapshots (full screen and dock)
  were held in singletons and were not discarded on disconnect. They are now cleared when you leave
  a world.

### Migration — please read if you used 1.0.2 – 1.0.14

Nothing is deleted or moved automatically; old data is simply no longer read.

1. **Anything recorded between 1.0.2 and 1.0.14 (stored under the `sp:.` key) is not carried over.**
   That bucket is a merge of every world you played in that period, so it cannot be attributed to any
   single world without inventing an answer. It is left on disk, untouched, but unused.
2. **Data recorded before 1.0.2 is still read**, because it was keyed by the world's *display* name
   and, for the usual case where the save folder matches the display name, that key is now produced
   again. Note that display names are not unique: if you had several worlds with the same name
   (Minecraft only disambiguates the *folder*, e.g. `New World` and `New World (1)`, both shown as
   "New World"), their pre-1.0.2 records may be mixed together in that one key. This is inherited
   from old data and is not re-created going forward.
3. **To start clean**, delete `config/visualizegate-portals.json` and the `config/visualizegate/`
   directory by hand. Both are re-created empty.

## [1.0.14] - 2026-06-25

### Fixed

- **Conflict reasons displayed in Japanese under non-Japanese locales.** In the point cloud's Links /
  Conflicts rows, the reason text for a conflicting gate (crossing, asymmetric, offset, will-create,
  orphan) was always shown in Japanese, even when the game language was English, German, Russian, or
  Simplified Chinese. The reason strings were hard-coded in the domain layer; they have been removed and
  replaced with localized keys (`visualizegate.conflict.*`), with the gate name supplied as a
  placeholder. Conflict reasons now follow the selected language across all five locales (281 keys, full
  parity). German, Russian, and Chinese are seed translations (not yet natively reviewed).

## [1.0.13] - 2026-06-25

### Changed

- **Point cloud finalized on full volumetric display (moiré decimation reverted).** The two decimation
  approaches tried for moiré — projected-space cells (1.0.11) and 3D world-space cells (1.0.12) — were
  both reverted because they made the cloud look sparse and unevenly dense. Both the Overworld and the
  Nether now draw their full volume again (every sampled point, no thinning), so underground and cave
  points and the positions of underground gates stay readable, and the cloud no longer gains or loses
  points while rotating or zooming. A faint moiré remains on flat regular terrain, but it flows past as
  you rotate and is considered acceptable; this is the intended final behavior.

## [1.0.12] - 2026-06-25

### Fixed

- **GPU3D point cloud looked sparse and shifted while rotating / zooming (regression from 1.0.11).**
  1.0.11's decimation for the GPU3D path was keyed to the current screen projection, so rotating the
  view revealed density that had been baked for a different angle (off-screen areas came in dense, areas
  thinned for the old angle stayed sparse). It was replaced with camera-independent decimation in
  pre-rotation 3D space, so the density is uniform at every angle. The texbatch path was unaffected and
  unchanged. (Superseded by 1.0.13, which removed decimation entirely.)

## [1.0.11] - 2026-06-25

### Fixed

- **Point-cloud moiré (shimmer) addressed via adaptive decimation.** A regular grid of fixed-size points
  drawn under perspective aliases against the screen pixel grid (distant points pack to ~1px apart),
  producing a shimmering moiré. As a draw-stage fix, projected points were quantized into ~2px screen
  cells, keeping one point per cell so drawn points stay at least ~2px apart (above the aliasing limit) —
  thinner in the distance, full density up close. The volume data itself was left intact (underground,
  cave, and gate positions still readable); only display density was reduced. (Superseded by 1.0.13,
  which reverted decimation in favor of full volumetric display.)

## [1.0.10] - 2026-06-25

### Changed

- **Surface reduction and moiré jitter reverted to restore volumetric point cloud.** Reducing each
  dimension to a single top surface (Nether in 1.0.6, Overworld in 1.0.9) hid caves, sub-surface terrain,
  and the position of underground gates — too costly in practice. That reduction and the moiré jitter
  (1.0.7 / 1.0.8) were both removed: both dimensions render their full volume again (several stacked
  points per column), so underground and cave structure and gate positions are readable. The moiré
  workaround was dropped pending a root-cause fix (addressed differently in later versions).

## [1.0.9] - 2026-06-25

### Changed

- **Overworld reduced to a surface to match the Nether.** Overworld terrain is volumetric (the surface
  plus caves and overhangs scanned down ~96 blocks), so drawing it as a volume with the 1.0.8 jitter made
  the sub-surface points read as a "haze." The Overworld was reduced to the topmost point per (x,z) column
  — the same surface treatment the Nether already had (1.0.6) — so both dimensions show a clean top-down
  silhouette. Display-only: saved terrain tiles were unchanged; sub-surface and cave points were no longer
  drawn. (Reverted in 1.0.10.)

## [1.0.8] - 2026-06-25

### Fixed

- **Overworld moiré jitter had no visible effect.** The world-fixed ±0.4-block jitter added in 1.0.7
  became sub-pixel (~0.16px) at typical fit zoom and did not break up the moiré. The jitter was moved to
  the draw stage and its amplitude scaled by screen-pixels-per-world-unit, so the apparent offset stays
  about 1px regardless of zoom level or world size — breaking the grid alignment consistently while
  preserving structure. Applied to Overworld terrain points only; the Nether, gates, links, marker, and
  labels are unchanged. (Superseded by the 1.0.10 revert.)

## [1.0.7] - 2026-06-24

### Fixed

- **Overworld point cloud showed a moiré (ripple) pattern.** Overworld terrain points sit on a regular
  grid; drawn 1:1 against the screen pixel grid they interfere, so even flat surfaces such as water
  appeared to ripple. A deterministic per-point offset (derived from a coordinate hash, so the same point
  always shifts the same way and static frames don't flicker) was added to break up the grid alignment.
  Applied to Overworld terrain points only; gates, links, the player marker, ID labels, and the Nether
  are unchanged. (Refined in 1.0.8, then reverted in 1.0.10.)

## [1.0.6] - 2026-06-24

### Fixed

- **Point cloud: Nether terrain showed as vertical streaks (root fix).** Nether terrain is volumetric —
  the sampler records every air→solid surface down a vertical band, so each (x,z) column holds several
  stacked points. Rendered as a point cloud with the horizontal 1:8 compression, each column became a
  tall thin vertical sliver, reading as radial vertical streaks. This affected the **full-screen** view
  (both the GPU3D and texbatch paths) and the dock radar alike — the earlier assumption that the
  full-screen view was streak-free was incorrect. The analyzer now reduces the Nether to a **surface**:
  the topmost point per (x,z) column (matching how the Overworld is already a single surface point),
  done once at the snapshot/aggregation stage so every consumer (full screen + dock, GPU3D + texbatch)
  is consistent. Streaks are gone; the Nether reads as a clean top-down silhouette. Saved terrain tiles
  are unchanged (the reduction is display-only); sub-surface, cave, and ceiling points below the topmost
  surface are no longer drawn, and the Nether point count is correspondingly lower. The Overworld,
  colors, numbering, 5-state coloring, gates/links (world-anchored, 1.0.3), marker, scales, and defaults
  are unchanged.

## [1.0.5] - 2026-06-24

### Fixed

- **Dock point-cloud radar: Nether points showed as vertical streaks (regression from 1.0.4).** 1.0.4
  undid the Nether's 1:8 horizontal compression in the dock to widen it — but that revealed a side effect:
  Nether terrain is volumetric (the sampler records every air→solid surface down a vertical band, so each
  column has many stacked points), and spreading the columns horizontally separated them into visible
  vertical streaks fanning out and downward. The horizontal compression had been hiding this by
  overlapping the columns (the full-screen view relies on the same compression). Because the streaks are
  the real volumetric structure, no uniform scale removes them; "no streaks" and "Overworld-width" cannot
  both hold for this data. Per the streaks-first priority, the dock now renders the Nether with the same
  transform as the full-screen view again (horizontal 1:8, identical to 1.0.3) — streaks gone. The Nether
  appears horizontally compact again (the trade-off). The Overworld, the 1.0.3 gate-follow fix, the
  full-screen view, the shared analyzer, colors, numbering, 5-state coloring, and defaults are unchanged.

## [1.0.4] - 2026-06-24

### Fixed

- **Dock point-cloud radar: Nether drawn much narrower than the Overworld.** In the Nether, the radar
  HUD showed only a thin vertical clump in the center, while the Overworld filled the panel. The points
  were all there (e.g. ~7.5k sampled, same ±64-block local window as the Overworld) — they were just
  drawn at 1/8 footprint. The analyzer bakes a 1:8 horizontal compression into the Nether layer so the
  full-screen view can stack Overworld + Nether at their true coordinate ratio; but the dock radar shows
  only the **current** dimension (a single layer), so that compression had nothing to scale against and
  just made the Nether look small. The pcDistance floor then compounded it (a small cloud viewed from
  too far). The dock now undoes the 1:8 compression for the Nether (renders it 1:1, the same as the
  Overworld) — applied only in the dock renderer to the Nether's horizontal coordinates, camera, clamp,
  and fit radius (vertical spacing is unchanged, as it was never compressed). The Overworld is unchanged
  (×1), and the full-screen point-cloud view and the shared analyzer keep the 1:8 ratio. Gates/links
  stay world-anchored (the 1.0.3 follow fix is preserved); points, colors, numbering, 5-state coloring,
  marker, and defaults are unchanged.

## [1.0.3] - 2026-06-24

### Fixed

- **Dock point-cloud radar: Nether gates followed the player.** In the Nether, gate frames in the
  bottom-right radar HUD stuck to a tiny ring around the player marker and moved with the player,
  instead of staying fixed at their world positions (the Overworld was correct). Terrain points were
  unaffected because only gates pass through the off-radar **edge-clamp**. The clamp radius was derived
  from the local terrain spread, which in the Nether collapses (horizontal 1:8 compression, and ~0 when
  no Nether terrain is captured — the default, since capture is off) — so the clamp radius fell to ~1
  and pinned every gate onto the player. The clamp radius is now floored to the radar's local window
  (64 blocks) expressed in the current dimension's view scale, so gates within the window stay
  world-anchored in both dimensions and only genuinely off-radar gates are edge-clamped. The Overworld
  is unchanged (its terrain-derived radius already exceeds the floor); the camera, terrain points,
  player marker, colors, numbering, and 5-state coloring are untouched. Dock radar only — the full
  point-cloud screen never had this behavior.

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

> **Correction (see 1.0.15):** this change did not work. `getWorldPath(ROOT)` ends in a `"."`
> component, so the derived key was the constant `sp:.` for every single-player world — worse
> isolation than before, not better. Fixed in 1.0.15.

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
