mcopt @VERSION@

A Minecraft performance mod for Apple Silicon Macs. No Sodium needed. VERY MUCH ALPHA: expect bugs, and back up your
worlds.

You need
- An Apple Silicon Mac (M1 or newer) on macOS 15 or later
- Java 25 (the official launcher and Prism download it for 26.3; nothing to install)
- Minecraft: Java Edition 26.3
- Fabric Loader 0.19.5 or newer for 26.3

Install
1. Install Fabric for Minecraft 26.3: https://fabricmc.net/use/installer/
   (or in Prism Launcher: new instance, version 26.3, loader Fabric)
2. Put mcopt-@VERSION@.jar in your mods folder.
   Official launcher: ~/Library/Application Support/minecraft/mods
   (in Finder: Go > Go to Folder, paste that path; create the mods folder if it's missing)
   In Prism: Edit the instance > Mods > Add file.
3. Start Minecraft with the Fabric profile.

No Sodium needed: mcopt won't start alongside it (Fabric says so), so take Sodium out of the mods folder.
Fabric API isn't required; keep it if your other mods need it.
When MoreCulling 1.9.0 and the supported Fabric Indigo renderer are installed, its leaf culling modes are honored by
default. Your MoreCulling and leaf-transparency settings are preserved. Unsupported versions retain their original path.
Mods that require Sodium (for example Better Block Entities or Iris) can't be used with this build.
Distant Horizons runs on the Metal renderer next to mcopt's own terrain. With DH set to its OpenGL renderer, mcopt
switches its renderer off for it.

The jar is a mod, not an app: double-clicking it only shows these install steps.

For the most fps: Options > Video Settings, VSync off and Max Framerate Unlimited.
The release no longer includes the separate top-left FPS overlay. Vanilla F3 still shows FPS.

On a computer the mod can't run on (not an Apple Silicon Mac, or macOS older than 15), the game stops at launch
and says what's missing; remove the mcopt jar from the mods folder to play there.
To check it's working: press F3 (fn + F3 on a Mac keyboard); the bottom right says Metal 3 (mcopt).

The first launch writes config/mcopt.properties in your game folder (in Prism, the instance's minecraft/config
folder); profile=none there turns the perf profile off.
To switch mcopt's renderer off, add mcopt.metal=false to that file (or launch with -Dmcopt.metal=false).
With Mod Menu installed, select mcopt > Configure to change LOD rendering, scouting, import, and performance settings.
Save and restart Minecraft to apply them. JVM arguments still override saved settings.
F3 now shows LOD tile and mesh queues, structure probes, confirmed structures, queued footprint chunks, and populated
chunk/import counts. Population status explains waits for frame time, server ticks, heap headroom, indexing, and unloading.
These are session counters for the active dimension, not a percentage of the world explored.
The optional Mod Menu integration adds no required configuration-library dependency.
Terrain buffer startup settings are also available there: remember measured capacity (on by default), a startup
reservation cap (512 MiB by default), and an optional explicit initial size (0 uses the remembered hint).
Capacity hints are saved to cache/mcopt/terrain-arena.properties when the terrain dispatcher is disposed, and read
on the next renderer creation. They are shared between worlds using the same render distance, vertex format and
sorted-copy mode; no terrain, seed or server address is stored. The cache is safe to delete. Changing render distance
during a session disables saving that session's hint to avoid attributing old geometry to the new distance.
Growth within the reservation keeps the same Metal buffer and avoids copying its existing contents. The startup
cap does not limit runtime terrain growth; first writes can still fault in pages, and this does not prevent heap,
GPU or system paging stalls. Extra startup capacity is not proactively touched. Unmeasured settings retain the
original initial size, and failed larger allocations fall back to it. The hint records a capacity high-water mark,
not a guarantee of steady live usage; reduce the cap or delete the cache to discard an oversized reservation.
Optional estimated-terrain generation and cached tile decoding now share a heap-headroom gate with population,
structure probes and saved imports. At 88% heap occupancy they stop starting work, and resume below 78% with sufficient
free headroom. Existing visited captures and already-started work continue draining. No collection is requested and
no post-recovery cooldown is added. Mod Menu exposes the LOD heap-headroom switch (mcopt.lod.memoryBudget, default true).
The gate does not control vanilla/C2ME generation, other mods, OS paging or GPU work; it can delay distant coverage.
During ordinary chunk streaming, visible estimated LODs ahead can share the existing fallback-generation allowance
(one running job total by default). Fine work no longer requires every outer render-distance chunk to have arrived.
A missing chunk in the inner 5 x 5 chunk area, or roughly one third of the render-distance disk missing, stops new
automatic fine work; it resumes at one quarter missing with no nearby holes. Already-running jobs finish normally.
The visible streaming corridor reaches from the render-distance boundary to at least 256 blocks beyond it (or eight
seconds of measured travel). This does not increase LOD reach, worker count, population or structure-scanning budgets.
mcopt.lod.yield.streaming=false, also exposed in Mod Menu, disables this automatic allowance. Explicit CPU budgets
remain available. The allowance applies to missing/spare admission modes with the default take-time gate.
Separate cache-check, fallback, visible and background queues keep denied jobs from hiding eligible cached/coarse work.
Scans discard queued coordinates no longer requested by the LOD rings, outside their windows or already filled. No cached terrain is
deleted. JFR reports severeChunkPressure, streamingAllowed/Starts, per-lane queue counts and lodStaleJobsDropped.
Completed valid captures also retire older settled edit notifications without making another chunk copy. Subsequent
block/light changes still invalidate capture reuse. JFR includes reusedEdits, memoryPressure and heap used/max fields.
JFR scheduling samples include the approximate vanilla mesh-task queue, LOD tile
coverage/queues and camera coordinates. These measurements do not change generation admission. The mesh queue
includes rebuild/resort requests and excludes running tasks/uploads; it is not a count of invisible chunks.
Mipmap/resource reloads now refresh LOD sprite coordinates without changing material IDs in resident tiles.
Mip sampling is inset within each sprite to avoid bleeding neighboring atlas textures.
Estimated shadowed terrain now uses the same changing lightmap as captured terrain, rather than scaling the final
sky RGB (which incorrectly scales ambient light). This improves colour consistency during End flashes and other
environment changes while keeping captured skylight shadows. Sheltered faces can still receive less skylight.

High flight can gradually shrink the fine LOD rings, by up to two tiers, without changing horizon distance or
discarding cached terrain. Clearance comes from nearby loaded heightmaps, including trees and structures, rather
than absolute Y. The actual world rendering resolution and projection protect pixel detail when zooming; the
full-terrain handoff is also protected. Unknown ground and ceiling dimensions retain normal detail. Render-list
ownership, temporal history and background priority scans are refreshed together when detail changes. Mod Menu's
Height-aware LOD detail (mcopt.lod.altitudeDetail, default true) can toggle this behavior while playing.
Mod Menu also applies the strength controls live, without rebuilding the world:
- Height reduction starts (mcopt.lod.altitudeStart): default 32 blocks of clearance, range 0..1024.
- Height reduction span (mcopt.lod.altitudeRange): default 160 additional blocks, range 1..1024.
- Minimum detail radius (mcopt.lod.altitudeMinPercent): default 25 percent, range 25..100.
- Height detail pixel tolerance (mcopt.lod.altitudePixels): default 6 pixels, range 1..16; higher allows coarser cells.
With defaults, full reduction is available 192 blocks above nearby terrain. Zoom, render resolution and the
full-terrain boundary may require more detail than the chosen minimum. Clearance uses the upper quartile of up to 25 nearby loaded samples, ignoring isolated roof/tower outliers
while protecting broad ridges and canopies;
the reduction does not track how much terrain a downward view exposes. The previous conservative strength limits are available with start 64, span 192, minimum 50 and pixel tolerance 3. Settings are parsed only when edited, not per frame.

Stationary LOD views reuse GPU visibility lists only when the exact camera, projection, geometry, LOD tiers and
full-terrain coverage are unchanged. Camera movement rebuilds them normally. Reuse stationary LOD visibility
(mcopt.lod.reuseVisibility, default true) applies live; alternate PK/fence/debug-seam paths retain ordinary culling.
Ready full-terrain interiors exclude LODs. The outgoing blend band is 16 blocks wide; the 200 ms arrival fade and
missing-chunk fallback remain, so some boundary overlap is intentional. No cache reset is needed.
LOD AO cutoff (pixels) (mcopt.lod.aoPixels, default 2, range 0..8) applies live. Fine AO fades between twice the
cutoff and the cutoff, then skips neighborhood probes. Textures and captured sky/torch lighting remain unchanged.
Set it to 0 for full AO at every distance.

Mod Menu > mcopt > Configure > Apply and close now applies these controls without restarting:
LOD enablement/distance, population and scouting controls, saved-import controls, population workload and start
spacing, estimated-terrain worker count, height-aware detail, haze and F3 statistics. Disabling LODs detaches the
view immediately, stops background work, drains accepted captures off the render thread and retires world GPU
buffers after in-flight frames. The disk cache is retained. Distance/workload changes rebuild the view from cache;
terrain can briefly disappear and refill. Repeated edits share one retirement and use the latest settings.
Controls marked * still require a restart, including renderer/profile switches, buffer formats and Litematica
compatibility. JVM arguments win at startup; explicit live edits override the running values until the next launch.
To reduce load, lower LOD distance, select low workload/one terrain worker, or disable population and scouting.
Turning Distant terrain off stops LOD work; vanilla chunk generation still runs. Java may retain committed heap.

Sky platforms can now keep exterior air gaps taller than 128 blocks, bounded by the dimension's floor. Roof layers
remain finite even near build height. Water below the structure stays in the lower surface instead of entering the
roof's vertical intervals. Continuous scaffold/stone supports retain their close-detail column approximation.
Coarse captures now omit sparse supports only where at least 75 percent of the footprint agrees on a roof and
exterior gap, the gap is at least eight blocks tall, and supports cover at most 12.5 percent of the cell. Supports
spanning either axis keep the existing wall approximation. This prevents a thin connection from filling a whole
coarse cell underneath the roof; qualifying thin supports disappear at distance. Broad walls and isolated towers
retain their existing geometry. Land surfaces below the upper vertical encoding band remain separate from roofs.
The bounded reduction uses existing fine capture data on workers, with no new generation, flood fill, GPU planes or
cache format. Restarting into the new build recomputes coarse capture levels; caches do not need to be cleared.
Compatible old captures stay visible while the existing budgeted saved-import worker refreshes their geometry;
this refresh does not generate missing/pruned chunks or wipe the cache. With population/imports disabled, load the
affected chunks up close to refresh them. Already bounded End captures retain their previous interpretation.
Underside detection now continues past unlit internal rooms to an exterior air gap, instead of extending a farm's
trapdoor/slab deck down as solid terrain. Fully sealed cavities remain closed; the upper-body budget still applies.
The lower-ground search is dimension-bounded, separately from the 252-block upper interval range, so a tall build's
lower deck does not become its foundation when the ocean lies beyond that range. Older partial-block deck captures
stay visible during saved-only refresh. Capture version 7 retains the existing nine-plane layout.

Litematica 0.29.1 colored overlays now combine consecutive compatible chunk draws into fewer render passes.
Transparent draw order, model geometry, colors, depth behavior and per-chunk transforms are preserved; buffer
storage is bounded and released after each scope. Unknown versions or modified renderer bodies retain the original
path. Mod Menu exposes Batch Litematica overlays (mcopt.compat.litematicaOverlayBatch, default true).
JFR includes mcopt.LodDetail and mcopt.LitematicaOverlayBatch when mcopt.metal.jfr is enabled.

With mcopt's LOD terrain enabled, singleplayer now progressively replaces estimated terrain with the world's actual
populated chunks and lighting. Existing saved chunks are imported within 128 chunks; unexplored chunks are generated
through vanilla FULL within 32 chunks, prioritizing the view ahead. Structure placement candidates are also scouted
within 256 chunks: a partial STRUCTURE_STARTS request validates each candidate before FULL generation covers its real
pieces and a one-chunk halo. Most underground-only structure sets are excluded. This can reveal populated structures
beyond the near refinement radius; the remaining terrain keeps its estimate until it is imported or refined.
Confirmed pieces are generated before their halo. Partial saved region entries no longer suppress their FULL generation;
the loading ticket stays owned until the actual chunk and lighting are ready for capture.
Population uses independent bounded FULL/probe lanes with loading-only tickets. Tickets remain owned until a request
is captured or cancelled; they do not extend mob/block simulation. Vanilla saves generated chunks normally, so
exploration refinement can increase the world's size on disk. Imports decode existing terrain instead of generating it.
Their configured radius is capped to visible LOD reach, including the Nether's 64-chunk reach; the default remains 128.

Population workload is separate from the Optimization profile:
- low: up to 1 FULL + 1 probe; up to 1 saved import per server tick.
- balanced (default): up to 2 FULL + 2 probes; up to 2 imports per tick.
- high: up to 4 FULL + 3 probes; up to 3 imports per tick.
These are ceilings, not guaranteed rates. The window starts at one per lane and grows after healthy intervals. Frame,
server-tick, heap, loaded-chunk and capture-queue pressure reduce or pause new work. Probes pause while confirmed
footprints accumulate, so cheap discovery does not outrun population. Server-side capture/import work has a soft time
budget of 1/2/3 milliseconds per tick; an individual snapshot cannot be interrupted midway through its capture.
Temporary raw heap occupancy above 80% no longer causes a five-second pause. Memory retained after the latest GC is
sampled without forcing collection; retained occupancy >=85% or raw heap near its limit stops admission. Transient
high occupancy reduces concurrency. Brief stalls cause 150-500ms recovery, then admission resumes at a reduced window;
normal game pauses do not trigger stall recovery. Imports can continue under mild frame/tick pressure.

Optional JVM arguments: -Dmcopt.lod.refine=false disables background population/importing; -Dmcopt.lod.refineRadius=0
keeps saved importing without generating unexplored terrain. The population radius accepts 0..64 chunks;
-Dmcopt.lod.importRadius=128 sets the saved-import radius (0..512, capped to visible reach).
-Dmcopt.lod.workload=low|balanced|high selects workload limits. All settings apply on restart.
The structure scout radius is -Dmcopt.lod.scoutRadius=256 (0..512 chunks); 0 disables scouting alone. At most 64 placement
regions are examined in a server tick, with bounded candidate/footprint queues. Placement regions are scanned outward
from the player, then candidates favor the view ahead. Confirmed pieces precede their surrounding halo.
-Dmcopt.lod.refineIntervalMs=0 (default) removes the former one-second completion delay. Explicit values up to 60000
set optional spacing between FULL starts; probes are independently paced. Existing explicit values are retained.

Captured columns are reduced across each coarse cell's full footprint, combining their solid vertical spans and preserving
the strongest known block light, except for the sparse-support simplification described above. A high roof edge no longer replaces neighboring walls with empty space. Water retains its
existing shoreline approximation. Every detail level retains light and floating surfaces;
nearby level-0 surfaces retain their existing directional lighting. Coarse light is approximate and has no per-floor
shadow samples. Captured structures and trees retain three exact upper solid intervals within a 63-block band, or up to
four sampled intervals within 252 blocks. Sampling uses 1–9 block vertical steps according to the span; wider air gaps
remain open and smaller gaps may merge. Upper intervals share materials and lighting. Thick exterior shelves no longer
become an infinite lower surface: capture continues down through exterior gaps while retaining sealed-cave protection.
End captures retain their lowest solid body and the floors/walls above it, including stair geometry. This is a compact
column renderer, not a full voxel representation of every room or cave. Shapes outside that vertical budget still use a
roof approximation; untouched estimated terrain remains limited by its original height samples.
With default 2048-cell windows and five levels, extending interval data by two Overworld levels costs 64 MiB.
End and Nether retain interval data at every level. Vertical detail still has a bounded representation per cell.
-Dmcopt.lod.structureDetail=false restores the configured crown-level count and disables additional floor extraction.
Older tree/overhang captures refresh progressively from saved, generated or revisited chunks. Ordinary fine captures
remain readable. No cache deletion is required.
Small End islands placed during feature decoration now appear in LODs too. Only the game's End-island feature is replayed,
with its actual placement seed and biome filter; no FULL chunk generation is needed. Neighboring feature chunks are
included at tile boundaries and retained columns have a finite underside. Feature results use a bounded cache. Older
End tiles rebuild automatically to include these islands, with saved populated captures applied afterward.

Nether LODs use a floor-and-ceiling approximation selected near the camera's height, with a maximum reach of 64 chunks.
Ceilings remain separate from floors at every LOD level. Vertical flight waits for a settled altitude before rebuilding
the cavern field; crossing the bedrock roof still changes geometry mode immediately. Nether and End atmospheric fog
follow their LOD reach with dimension-aware fog; water/lava fog and blindness/darkness effects retain their normal
ranges. Periodic tile saves copy one tile at a time, with at most two outstanding copies, and tile compression resources
are released explicitly. Refinement progress and detected server stalls now appear in latest.log. Dimension switches reject
chunks from the previous or incoming world until their field matches, and reset temporal history and fog reach.
This build uses a fresh LOD cache identity to avoid reusing previously contaminated dimension captures. Its cache
rebuilds automatically; existing world saves are retained. Older saved chunk formats are upgraded in memory before
LOD importing, without rewriting those chunk files.

End islands use finite upper and lower surfaces at every LOD level; known void stays empty. Their density sampler uses
the End's native 8x4 grid without expanding coarse requests into dense block volumes. Completed tile uploads also have
backpressure. End LODs use a fresh islands-v1 cache subdirectory so older column approximations rebuild automatically;
world saves, Overworld caches and Nether caches are retained.
The visibility culler also respects finite island undersides: they cannot hide farther terrain through the open space
below them. The same conservative interval test applies to generated LODs and captured terrain.

Chorus plants use vanilla's vegetation feature for distant prediction. Captured stalks and branches retain their plant
material above the End island, with the island's finite underside and exposed air beneath branches kept separate.
Farther End highlands use seed-stable chorus silhouettes on the coarser levels, independently of the structure scouting
radius. Cells 16 blocks wide or larger blend vegetation coverage into the surface color instead of widening stalks into
solid walls. These are vegetation estimates; actual saved/generated captures replace them. The approximation uses the
existing column buffers and bounded work per cell, without additional FULL chunk generation.
Old level-0 End estimate tiles rebuild for chorus prediction; old captures containing chorus are refreshed from saved
chunks as the importer reaches them. Older coarse End estimate tiles also rebuild for the new silhouettes and coverage;
other captures and world saves are retained.
GPU tile copies coalesce multiple versions of the same tile within a frame. CPU reads and neighbor meshes use immutable
publication snapshots while the GPU copy is in flight; those snapshots retire after four frames. Identical captured cells
skip tile refreshes, and color/light-only changes reuse unchanged mesh allocations. A render summary every 30 seconds in
latest.log reports FPS, resident quads, mesh backlog, arena memory, skipped updates and pending GPU snapshots.

Licence: Apache-2.0 (LICENSE, NOTICE).
