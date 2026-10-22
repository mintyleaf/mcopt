# mcopt

> [!NOTE]
> mcopt no longer requires Sodium 🎉

A Minecraft performance mod for Apple Silicon Macs. Since 0.3.0-alpha.1 it draws the world with its own Metal renderer.
**Very much alpha:** expect bugs, and back up your worlds.

## Requirements

- An Apple Silicon Mac on macOS 15 or later
- Java 25 (the official launcher and Prism download it for 26.3; nothing to install)
- Minecraft 26.3 with Fabric Loader 0.19.5 or newer

## Install

1. Install Fabric for Minecraft 26.3: https://fabricmc.net/use/installer/ (or in Prism Launcher: new instance, 26.3,
   loader Fabric).
2. Download the mcopt jar from [Releases](../../releases).
3. Put it in `~/Library/Application Support/minecraft/mods`. Create the `mods` folder if it's missing
   (Finder: Go > Go to Folder). In Prism: Edit the instance > Mods > Add file.
4. Start Minecraft with the Fabric profile.

Sodium isn't needed anymore: mcopt won't start alongside it, so take Sodium out of that folder if it's there. Fabric API
isn't required; keep it if your other mods need it.

To check it's working: press F3 (fn + F3 on a Mac keyboard); the bottom right says `Metal 3 (mcopt)`.

F3 shows FPS and LOD generation/scouting statistics. The release does not include a separate FPS overlay.

High flight gradually uses coarser LOD tiers, based on clearance above loaded terrain and projected size. Horizon distance stays the same; projected cell size and the full-terrain boundary protect detail. Mod Menu exposes **Height-aware LOD detail** (`mcopt.lod.altitudeDetail`, default true) and these live controls:

| Control | Property | Default | Effect |
| --- | --- | --- | --- |
| Height reduction starts (blocks) | `mcopt.lod.altitudeStart` | 32 | Clearance above nearby terrain before reduction starts. |
| Height reduction span (blocks) | `mcopt.lod.altitudeRange` | 160 | Additional clearance to reach full reduction: 192 blocks with defaults. |
| Minimum detail radius (%) | `mcopt.lod.altitudeMinPercent` | 25 | Smallest permitted detail-tier radius, relative to normal. Lower values allow stronger reduction; the range is 25–100. |
| Height detail pixel tolerance | `mcopt.lod.altitudePixels` | 6 | Larger values allow coarser cells on screen; the range is 1–16. |

These edits apply without restarting or rebuilding the world. Clearance uses the highest sampled nearby loaded surface, including trees and structures; it is not absolute Y and does not react to camera pitch. Default limits permit two tiers of reduction, but zoom, rendering resolution and the full-terrain handoff can require larger radii. Unknown ground and ceiling dimensions retain normal detail. To reproduce the earlier conservative limits, use start 64, span 192, minimum 50 and pixel tolerance 3.

Captured sky platforms preserve exterior air gaps beyond 128 blocks, including roofs near build height over land or ocean. Water stays beneath the structure rather than entering its roof layers. At coarse levels, sparse continuous supports no longer fill an otherwise matching roof's whole footprint: at least 75% must share the roof/gap, supports may cover at most 12.5%, and the gap must be at least eight blocks tall. Supports spanning either axis retain the existing wall approximation. Close detail, broad walls and isolated towers retain their existing geometry; qualifying narrow supports can disappear at distance. This bounded capture reduction uses geometry, not special block types or structure generation. Coarse levels rebuild from the existing fine captures without a cache reset. Older captures stay visible during budgeted refresh from existing saved chunks; loading a structure up close also refreshes its capture.

Enclosed rooms inside floating builds no longer stop underside detection: it continues through internal gaps until it finds an exterior gap, while sealed underground cavities remain closed. Lower terrain searches use the dimension floor independently of the upper-layer encoding range. Older slab, stair and trapdoor deck captures refresh from saved terrain without deleting the cache.

Litematica 0.29.1 colored schematic overlays combine consecutive compatible draws into fewer render passes while preserving their order. **Batch Litematica overlays** (`mcopt.compat.litematicaOverlayBatch`, default true) can disable the compatibility patch. Other versions or modified renderer bodies retain their original path.

## Settings

- The perf profile is on by default.
- `profile=none` in `~/Library/Application Support/minecraft/config/mcopt.properties` (in Prism, the instance's
  `minecraft/config` folder) turns it off. The first launch writes that file.
- With Mod Menu installed, use **mcopt > Configure > Apply and close**. LOD enablement, distance, population/scouting limits and workload, saved-import controls, terrain-worker count, altitude detail, haze and F3 statistics apply while playing. Disabling LODs stops their background work, drains accepted captures and releases world buffers; their disk cache is retained. Distance and workload edits rebuild the LOD view, so terrain can briefly disappear and refill from cache.
- Options marked `*` require restarting Minecraft, including buffer formats, renderer/profile switches and Litematica compatibility. JVM arguments take precedence at startup; explicit live edits override their current values until the next launch. Population workload (`low`/`balanced`/`high`) controls background work independently of the renderer optimization profile.
- For lower resource use, reduce **LOD distance**, set **Population workload** to `low` and **LOD worker threads** to `1`, or disable **Population and scouting**. Turn **Distant terrain** off to stop LOD work and free its world buffers. This does not stop Minecraft's own terrain generation or force Java to return committed heap immediately.
- Far terrain is an experimental opt-in: `mcopt.lod=true` in the same file, for Macs with 10 or more GPU cores.

Opt-in settings (in the same file, or as `-D` flags):

- `mcopt.renderScale=0.5` - render the game at a fraction of the window size and upscale (not pixel-exact: blurrier).
- `mcopt.own.rgssTri=true` - atlas sampling at the exact mip level with linear filtering (not pixel-exact).
- `mcopt.own.rgssOne=true` - with rgssTri: one far sample instead of four (not pixel-exact: distant shimmer).
- `mcopt.metal.presentQueue=true` - present frames from a second command queue; `=acquire` also acquires there.
- `mcopt.own.frag.tOcc=false` - turns the translucent-occlusion default back off.

## Historical upstream benchmarks

These upstream results predate this fork series and do not measure its LOD changes.

fps spinning / flying, same test world, 1920x1080, render distance 16, VSync off, at least two runs each:

| Mac | vanilla | Sodium 0.9.3 | 0.2.0-alpha.3 | 0.3.0-alpha.1 |
|---|---|---|---|---|
| Mac mini M4 (10-core GPU, 16 GB) | 156 / 146 | 275-281 / 253-267 | 1202-1223 / 1068-1074 | 1209-1211 / 1068-1069 |
| MacBook Neo (A18 Pro, 5-core GPU, 8 GB) | 79-81 / 69-72 | 133-146 / 85-91 | 514-579 / 422-449 | 591-638 / 477-504 |
| Mac mini M6 (12-core GPU, 16 GB) | 297-328 / 283-291 | 454-460 / 406-413 | 1988-1991 / 1738-1742 | 2020-2022 / 1690-1704 |

The mini and Neo cells ran on 6c133ecc, whose only difference from 9865a0a3 is vGroup, which is gated off on those GPUs. The Neo's vanilla and Sodium cells come from the da566d00 run. The benchmarked upstream build differs from 9865a0a3 only in code that runs when Distant Horizons is installed.

For the most fps: Options > Video Settings, VSync off and Max Framerate Unlimited. The numbers above are with VSync off.

## Known issues

- Mods that require Sodium (for example Better Block Entities or Iris) can't be used with this build.
- Mods that call OpenGL directly can't draw on the Metal backend. To switch mcopt's renderer off, add
  `mcopt.metal=false` to `config/mcopt.properties` (or launch with `-Dmcopt.metal=false`).
- Distant Horizons runs on the Metal renderer next to mcopt's own terrain. With DH set to its OpenGL renderer, mcopt
  switches its renderer off for it.
- Far terrain is experimental. On Macs with fewer than 10 GPU cores, it costs most of the fps.

## Plans

- Far terrain on by default, with tiers for smaller Macs
- Our own shaders (BSL-level) as an option
- Faster world generation (native noise, bit-exact)
- More exact server-tick and chunk optimizations, held for later alphas
- Faster startup
- Wider hardware testing

## Licence and credits

[Apache License 2.0](LICENSE). Keep the [NOTICE](NOTICE) file with any copy, and credit mcopt.
