Controlled live profiling
=========================

This development-only Fabric module launches a disposable world, moves the camera
along a fixed wall-clock route, records frame intervals and JFR, and closes
Minecraft. It is not included in the normal mcopt release jar. Requires macOS,
Prism Launcher, Java 25 and a configured Minecraft 26.3/Fabric 0.19.5 instance.

The route is repeatable; the game is not bitwise deterministic. Frames may sample
different points along the route because generation, rendering, GC, filesystem
caches, driver caches and CPU/GPU scheduling remain asynchronous. This measures
camera/chunk-streaming workloads, not elytra physics or keyboard playback.

Setup (from repository root)
----------------------------

1. Build the harness and create a NEW vanilla fixture directory. The fixture
   generator starts a temporary local vanilla server, generates FULL chunks near
   the origin, saves and stops. It refuses an existing fixture destination.

   ./gradlew :replay:build
   ./gradlew :replay:createFixture -PfixtureOutput=/tmp/mcopt-fixture-v1 -PfixtureSeed=12345

   Match the route's seed to fixture.json. Retain the immutable fixture for every
   baseline/candidate run: regenerating the same seed does not reproduce all save
   bytes. The runner hashes the complete fixture world and checks each reset.

2. Prepare a separate Prism instance. Source metadata/Java location are read;
   personal accounts, options, configs, worlds and server lists are not copied.
   Prism uses its existing account selection to launch the game normally.

   python3 tools/replay-profile.py prepare \
     --source '/path/to/PrismLauncher/instances/your-existing-instance' \
     --fixture /tmp/mcopt-fixture-v1 \
     --metal /path/to/mcopt.jar

   The default instance ID is mcopt-replay-profile. An existing instance without
   the matching ownership marker is rejected. The default mod set contains only
   mcopt and the replay harness. --modset c2me additionally copies C2ME, Lithium,
   FerriteCore and Fabric API from the source instance. It does not copy their
   personal configuration; each starts with its defaults. Atmospherics is absent.

   Defaults: 4 GiB heap, ZGC, VSync and 60 FPS cap, render distance 12, simulation
   distance 8, LOD/scout radius 512, balanced workload, mipmaps 2, explicit 512 MiB
   initial terrain arena with history disabled. Window 2560 x 1380 logical pixels
   means 5120 x 2760 framebuffer pixels on a 2x Retina display. Use --width/--height
   at prepare time to change this. Actual framebuffer/setup are in report.json.

   To reuse the source pack's graphics, add --graphics-from-source. This copies
   only a whitelist of graphics options plus RenderScale, Cloth Config, Fabric
   API and config/renderscale.json5. Keybinds, chat, servers and other personal
   settings are excluded. Both comparison jars must use the same option. The
   extra config is restored before each launch and its input hash is compared.
   Use --width 1920 --height 1080 for a 3840 x 2160 framebuffer on a 2x display.
   report.json also records main-target dimensions and worldTargetWidth/Height
   sampled inside renderLevel, separately from the display framebuffer, to verify
   that scaling took effect before the UI target is restored.

3. Run the short smoke test first. Output must be a new absolute path without
   whitespace. No mouse/keyboard camera control is needed. Keep the game focused;
   each phase checks focus. The route advances by wall clock, so pauses or window
   changes invalidate a run. Avoid unrelated CPU/memory-heavy activity when
   measuring performance. Profile tools run after Minecraft exits.

   python3 tools/replay-profile.py run --plan tools/replay/smoke.json \
     --case cold --output /tmp/mcopt-smoke-01 --analyze

   python3 tools/replay-profile.py run --plan tools/replay/overworld.json \
     --case cold --output /tmp/mcopt-cold-01 --analyze

   Repeat with a NEW output path, then run --case warm-lod. Each launch opens the
   world, waits for a player/server, warms up, records the phases, writes reports
   and exits. The normal route includes floor/far/zoom views, 32 blocks/s flight,
   a climb from Y=180 to Y=320, descent, and stopping with zoom. It is currently an
   Overworld route; the server hook does not replay Nether/End dimension changes.

Cache cases
-----------

cold:       reset world from fixture + empty mcopt LOD cache
warm-lod:   reset world from fixture + retain LOD cache from previous run
warm-world: retain saved world and LOD cache from previous run

Warm cases require an existing nonempty LOD cache. The save directory itself is
retained on reset because mcopt includes its path and creation time in cache
identity. Its contents alone are replaced. Each cold reset verifies that cloned
world bytes match the fixture before launch. Original fixtures are never edited.

These names describe world and LOD cache state only. GPU shader caches, the
filesystem page cache, OS swap, JIT warmup and thermal history are not reset.
Warm-lod caches contain whatever the prior run managed to generate; compare the
cache file counts/bytes and readiness telemetry, not just the case label. To
reproduce a specific warm state, retain/restore a separate cache snapshot while
Minecraft is closed. The tool does not yet automate cache snapshot restoration.

Measurements and comparison
---------------------------

   python3 tools/replay-profile.py compare \
     /tmp/mcopt-cold-01 /tmp/mcopt-cold-02 /tmp/mcopt-warm-01 \
     --output /tmp/mcopt-comparison.json

CSV output includes per-phase p50/p95/p99/max frame intervals, gaps over 50 ms,
GPU command-buffer span p95, render-thread allocation stalls and arena growths.
Comparison checks fixture/route/settings hashes, supporting mod versions and
actual display/backend/heap setup. Renderer jar hashes are experiment variables;
run.json records every installed jar hash. Mismatches are reported. Setup is sampled after warmup; runs with lost focus, route-position errors or
changes to render distance/framebuffer/VSync/FPS cap during measurement are flagged invalid. This flag only validates the
replay lifecycle: external load and thermal conditions still need assessment.

Use several repeats and alternate baseline/candidate order. A single run, or
comparing cold against warm caches, does not establish an optimization speedup.
The default route deliberately includes new-world generation, so a renderer-only
comparison should use a saved, pre-generated fixture and an equivalent LOD cache.

Artifacts per run:
  run.json            case, hashes, world identity, cache counts, OS memory state
  route.json          exact route input
  report.json         actual setup, per-phase frame percentiles, validity details
  frames.csv          runTick start-to-start intervals and sampled camera positions
  recording.jfr       JVM CPU/GC/allocations and existing mcopt Metal/LOD telemetry
  summary.json        existing tools/frame-profile.py analysis (with --analyze)
  phase-summary.json  JFR phase markers with CPU, GPU and scheduling summaries
  game.log            private local log copy; review before sharing

Frames are CPU loop start-to-start intervals, not display scanout FPS. Command
buffer GPU spans include dependencies and can overlap; do not add main/pre spans
or interpret them as GPU utilization. Phase attribution maps GPU start time from
the native clock beside each JFR event, rather than using late retirement time.
LOD scheduling/client capture events retain their existing sampling thresholds.
Diagnostic GPU pass splitting and the native CPU sampler are disabled. Standard
JFR profile recording still adds overhead and should match across comparisons.

To analyze existing replay JFR phases again:
   python3 tools/replay-profile.py analyze /tmp/mcopt-cold-01

Cleanup and failure handling
----------------------------

The runner only resets marker-owned saves/mcopt-replay and mcopt-replay-cache.
It refuses symlinked layout paths and an active replay. An ambiguous launch that
never wrote live.json stays locked even after a timeout; elapsed time cannot prove
Minecraft is absent. Inspect the owned instance/process, stop only its Minecraft,
then remove that instance's .minecraft/.replay-active.json if necessary. Never
remove session locks or reset world/cache directories while Minecraft is running.
Timeout requests termination of the PID reported by the harness, never another
Minecraft JVM. Logs remain in the disposable instance for debugging.

Re-running prepare changes only the owned instance, resets its controlled input
settings/mods, and keeps fixture world/LOD cache until the requested run case.
Minecraft writes its options on exit; the runner restores the exact prepared
options/config before every launch. Leave the normal game instance closed during
profiling to avoid contention; no normal installation is modified or replaced.

Safety checks:
   python3 -m unittest discover -s tools/replay -p 'test_*.py' -v
