#!/usr/bin/env python3
"""Labelled Minecraft JFR + existing Metal diagnostic captures; no benchmark mod required.

  python3 tools/frame-profile.py capture --label floor
  python3 tools/frame-profile.py capture --label far
  python3 tools/frame-profile.py capture --label zoom
  python3 tools/frame-profile.py capture --label flight --seconds 30
  python3 tools/frame-profile.py analyze /tmp/mcopt-frames-floor-...

Capture auto-detects a Prism Minecraft JVM. After the countdown, switch back to
Minecraft and hold the named view for the recording. Keep the same position,
resolution and FPS cap for floor/far/zoom. A bell signals completion. CPU and
native diagnostics add some overhead: compare these captures with each other.
"""

import argparse
import bisect
from collections import Counter, defaultdict
import csv
from datetime import datetime
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET


def run(argv, timeout=30):
    result = subprocess.run([str(v) for v in argv], capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout + result.stderr


def java_properties(jcmd, pid):
    output = run([jcmd, pid, "VM.system_properties"])
    return dict(line.split("=", 1) for line in output.splitlines() if "=" in line)


def find_game(jcmd, pid):
    if pid:
        candidates = [pid]
    else:
        candidates = []
        for line in run(["/bin/ps", "-axo", "pid=,comm="]).splitlines():
            fields = line.split(maxsplit=1)
            if len(fields) == 2 and Path(fields[1]).name == "java":
                candidates.append(int(fields[0]))
    games = []
    for candidate in candidates:
        try:
            props = java_properties(jcmd, candidate)
        except (RuntimeError, subprocess.TimeoutExpired):
            continue
        main = props.get("sun.java.command", "").split(" ")[0]
        if "prismlauncher" in main.lower() or "knotclient" in main.lower() or main.startswith("net.minecraft.client"):
            games.append((candidate, props))
    if len(games) != 1:
        raise RuntimeError(f"Expected one Minecraft JVM, found {len(games)}; pass --pid explicitly if needed")
    return games[0]


def snapshot_log(path):
    try:
        stat = path.stat()
        return stat.st_ino, stat.st_size
    except FileNotFoundError:
        return None, 0


def read_log(path, before):
    try:
        stat = path.stat()
        with path.open("rb") as stream:
            if stat.st_ino == before[0] and stat.st_size >= before[1]:
                stream.seek(before[1])
            return stream.read().decode("utf-8", errors="replace")
    except FileNotFoundError:
        return ""


def option(key, value):
    return key + '=\"' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '\"'


def macos_memory_snapshot():
    if sys.platform != "darwin":
        return None
    result = {"at": datetime.now().astimezone().isoformat()}
    for label, argv in (("vmStat", ["/usr/bin/vm_stat"]), ("swapUsage", ["/usr/sbin/sysctl", "-n", "vm.swapusage"])):
        try:
            result[label] = run(argv, timeout=3).strip()
        except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
            result[label + "Error"] = str(error)
    return result


def capture(args):
    pid, props = find_game(args.jcmd, args.pid)
    game = Path(props["user.dir"])
    label = re.sub(r"[^A-Za-z0-9_-]", "_", args.label)
    output = args.output.expanduser().resolve() if args.output else Path(tempfile.mkdtemp(prefix=f"mcopt-frames-{label}-"))
    if args.output:
        output.mkdir(parents=True, exist_ok=False)
    recording = "mcopt_" + uuid.uuid4().hex[:12]
    jfr = output / "recording.jfr"
    configuration = ET.parse(Path(props["java.home"]) / "lib/jfr/profile.jfc")
    event = ET.SubElement(configuration.getroot(), "event", {"name": "mcopt.MetalOperation"})
    for key, value in {"enabled": "true", "threshold": "0 ns", "stackTrace": "false"}.items():
        ET.SubElement(event, "setting", {"name": key}).text = value
    event = ET.SubElement(configuration.getroot(), "event", {"name": "mcopt.MetalGpuPass"})
    ET.SubElement(event, "setting", {"name": "enabled"}).text = "false" if args.no_gpu_passes else "true"
    event = ET.SubElement(configuration.getroot(), "event", {"name": "mcopt.OwnArenaGrowth"})
    ET.SubElement(event, "setting", {"name": "enabled"}).text = "true"
    for name in ("mcopt.MetalCommandBuffer", "mcopt.MetalPresentation", "mcopt.MetalDrawable", "mcopt.MetalPipelineCompile", "mcopt.LodScheduling", "mcopt.LodCaptureQueue", "mcopt.LodClientCapture", "mcopt.OwnArenaAllocation"):
        event = ET.SubElement(configuration.getroot(), "event", {"name": name})
        ET.SubElement(event, "setting", {"name": "enabled"}).text = "true"
    settings = output / "profile.jfc"
    configuration.write(settings, encoding="utf-8", xml_declaration=True)
    print(f"Minecraft PID {pid}; {args.label}: {args.seconds}s; output {output}", flush=True)
    print(f"Recording starts in {args.delay}s. Switch to the game and hold the view.\a", flush=True)
    # This is a user-run CLI countdown, not an agent wait or a pause inside Minecraft.
    time.sleep(args.delay)
    paths = [game / "logs/latest.log", game / "logs/stderr_stream.log"]
    logs = {path: snapshot_log(path) for path in paths}
    metadata = {
        "label": args.label, "pid": pid, "seconds": args.seconds, "gameDirectory": str(game),
        "start": datetime.now().astimezone().isoformat(), "startEpoch": time.time(),
        "javaHome": props.get("java.home"), "javaVersion": props.get("java.version"),
        "mcoptProperties": {key: value for key, value in props.items() if key.startswith("mcopt.")},
        "macosMemoryBefore": macos_memory_snapshot(),
        "note": "Main/pre GPU spans can overlap; do not sum them. JFR stack samples are not GPU timings. Log excerpts include attach/drain margins.",
    }
    started = False
    try:
        output_text = run([args.jcmd, pid, "JFR.start", "name=" + recording, option("settings", settings),
                           f"duration={args.seconds}s", "maxsize=64m", "dumponexit=true", option("filename", jfr)])
        (output / "attach.txt").write_text(output_text)
        if "Started recording" not in output_text:
            raise RuntimeError("JFR did not confirm recording startup: " + output_text)
        started = True
        time.sleep(args.seconds + 1)
        # The JVM owns the recording timer; allow it to finish the bounded on-disk dump.
        for _ in range(20):
            if jfr.exists() and jfr.stat().st_size:
                break
            time.sleep(0.25)
    except KeyboardInterrupt:
        metadata["interrupted"] = True
        if started:
            try:
                run([args.jcmd, pid, "JFR.stop", "name=" + recording, option("filename", jfr)])
            except (RuntimeError, subprocess.TimeoutExpired) as error:
                metadata["stopError"] = str(error)
    finally:
        metadata["end"] = datetime.now().astimezone().isoformat()
        metadata["endEpoch"] = time.time()
        metadata["macosMemoryAfter"] = macos_memory_snapshot()
        for path, before in logs.items():
            (output / path.name).write_text(read_log(path, before))
        (output / "capture.json").write_text(json.dumps(metadata, indent=2) + "\n")
    if not jfr.exists() or not jfr.stat().st_size:
        raise RuntimeError(f"Recording was not dumped; diagnostics saved at {output}")
    print(f"\aCapture complete: {output}", flush=True)
    jfr_tool = Path(props.get("java.home", "")) / "bin/jfr"
    summarize(output, str(jfr_tool) if jfr_tool.exists() else args.jfr)
    print("Report:", output / "summary.json")


DIAG = re.compile(r"mcopt-metal diag: (\w+) submit (\d+) at ([\d.]+) kernel ([+-]?[\d.]+)\.\.([+-]?[\d.]+) gpu ([+-]?[\d.]+)\.\.([+-]?[\d.]+) ms \(gpu ([\d.]+)\) copied (\d+) big (\d+)")


def stats(values):
    if not values:
        return {"count": 0}
    ordered = sorted(values)
    def percentile(p):
        return ordered[max(0, math.ceil(len(ordered) * p) - 1)]
    return {"count": len(ordered), "mean": sum(ordered) / len(ordered), "p50": percentile(.5),
            "p95": percentile(.95), "p99": percentile(.99), "max": ordered[-1]}


def duration_ms(value):
    if not isinstance(value, str):
        return 0.0
    match = re.fullmatch(r"PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?", value)
    return sum(float(n or 0) * factor for n, factor in zip(match.groups(), [3600000, 60000, 1000])) if match else 0.0


def parse_events(events):
    samples = defaultdict(Counter)
    inclusive_samples = defaultdict(Counter)
    thread_load = defaultdict(list)
    cpu_timeline = []
    gpu_passes = defaultdict(lambda: defaultdict(list))
    operations = defaultdict(list)
    gc_pauses = []
    allocation_stalls = []
    arena_growth = []
    lod_arena_growth = []
    command_buffers, presentations, pipeline_compiles, drawable_waits, lod_scheduling = [], [], [], [], []
    drawable_lifetimes = []
    capture_queues, client_captures, arena_allocations = [], [], []
    gpu_completed = []
    submits = {}
    spans = []
    count = Counter()
    for event in events:
        kind, value = event["type"], event["values"]
        count[kind] += 1
        if kind in ("jdk.ExecutionSample", "jdk.NativeMethodSample"):
            thread = value.get("sampledThread") or {}
            name = thread.get("javaName") or thread.get("osName") or "unknown"
            frames = (value.get("stackTrace") or {}).get("frames") or []
            names = []
            for frame in frames:
                method = frame.get("method") or {}
                owner = (method.get("type") or {}).get("name", "?").replace("/", ".")
                names.append(owner + "." + method.get("name", "?"))
            top = next((n for n in names if not n.startswith(("java.lang.invoke.", "jdk.internal.foreign."))), names[0] if names else "no stack")
            samples[(name, kind)][top] += 1
            inclusive_samples[name].update(set(names))
        elif kind == "jdk.CPULoad":
            cpu_timeline.append({"at": value["startTime"],
                                 "epoch": datetime.fromisoformat(value["startTime"].replace("Z", "+00:00")).timestamp(),
                                 "machinePercent": 100 * value["machineTotal"],
                                 "jvmPercent": 100 * (value["jvmUser"] + value["jvmSystem"])})
        elif kind == "jdk.ThreadCPULoad":
            thread = value.get("eventThread") or {}
            thread_load[thread.get("javaName", "unknown")].append(100 * (value.get("user", 0) + value.get("system", 0)))
        elif kind == "jdk.ZAllocationStall":
            thread = (value.get("eventThread") or {}).get("javaName", "unknown")
            length = duration_ms(value.get("duration"))
            allocation_stalls.append({"thread": thread, "start": value["startTime"], "durationMs": length})
            if thread == "Render thread":
                spans.append((value["startTime"], length, "allocationStall"))
        elif kind == "jdk.GCPhasePause":
            gc_pauses.append(duration_ms(value.get("duration")))
            spans.append((value["startTime"], duration_ms(value.get("duration")), "gcPause"))
        elif kind == "mcopt.MetalGpuPass":
            label = value.get("label", "unknown")
            for key in ("vertexMicros", "fragmentMicros"):
                if value.get(key, -1) >= 0:
                    gpu_passes[label][key.replace("Micros", "Ms")].append(value[key] / 1000)
            if value.get("startMicros", -1) >= 0 and value.get("endMicros", -1) >= value["startMicros"]:
                gpu_passes[label]["spanMs"].append((value["endMicros"] - value["startMicros"]) / 1000)
        elif kind == "mcopt.OwnArenaGrowth":
            arena_growth.append({key: value.get(key) for key in ("startTime", "kind", "oldBytes", "newBytes",
                                                                "preparationMs", "copyMs", "writerHoldMs", "writerWaitMs")})
        elif kind == "mcopt.LodArenaGrowth":
            lod_arena_growth.append({"startTime": value["startTime"], "durationMs": duration_ms(value.get("duration")),
                                     **{key: value.get(key) for key in ("phase", "oldBytes", "newBytes", "copiedBytes", "success")}})
        elif kind == "mcopt.LodScheduling":
            lod_scheduling.append({key: value.get(key) for key in ("startTime", "dimension", "missingChunks", "totalChunks", "nearMissingChunks",
                                                                  "coarseRunning", "fineRunning", "waitingWorkers", "generationPressure", "populationPressure",
                                                                  "memoryPressure", "heapUsedBytes", "heapMaxBytes", "meshCompileQueued",
                                                                  "lodTilesMissing", "lodTilesNeeded", "lodJobsPending", "lodJobsQueued", "lodResultsReady",
                                                                  "lodTilesGenerated", "lodTilesLoaded", "cameraX", "cameraY", "cameraZ",
                                                                  "severeChunkPressure", "streamingAllowed", "streamingStarts", "lodStaleJobsDropped",
                                                                  "lodCacheChecksQueued", "lodFallbackQueued", "lodVisibleQueued", "lodDeferredQueued")})
        elif kind == "mcopt.LodCaptureQueue":
            capture_queues.append({key: value.get(key) for key in ("startTime", "rawQueued", "writesQueued", "snapshots", "snapshotNanos", "fallbacks", "reusedUnloads", "reusedEdits")})
        elif kind == "mcopt.LodClientCapture":
            client_captures.append({"startTime": value.get("startTime"), "durationMs": duration_ms(value.get("duration")),
                                    **{key: value.get(key) for key in ("chunkX", "chunkZ", "reason", "freezeMs", "fallback")}})
        elif kind == "mcopt.OwnArenaAllocation":
            arena_allocations.append({key: value.get(key) for key in ("startTime", "reserveBytes", "reserveUsedBytes", "reserveHits", "sharedFallbacks", "filterDeferrals")})
        elif kind == "mcopt.MetalCommandBuffer":
            row = {key: value.get(key) for key in ("submit", "kind", "status", "committedAt", "kernelStart", "kernelEnd", "gpuStart", "gpuEnd", "observedAt", "releasedAt", "released")}
            for label, start, end in (("kernelMs", "kernelStart", "kernelEnd"), ("gpuMs", "gpuStart", "gpuEnd"),
                                      ("gpuStartAfterCommitMs", "committedAt", "gpuStart"), ("completionAfterCommitMs", "committedAt", "gpuEnd"),
                                      ("cpuReleaseAfterGpuMs", "gpuEnd", "releasedAt")):
                row[label] = (row[end] - row[start]) * 1000 if row.get(start, 0) and row.get(end, 0) else None
            command_buffers.append(row)
        elif kind == "mcopt.MetalPresentation":
            presentations.append({key: value.get(key) for key in ("submit", "presentedAt", "callbackAt", "observedAt", "missing")})
        elif kind == "mcopt.MetalDrawable":
            row = {key: value.get(key) for key in ("startTime", "submit", "layer", "surfaceGeneration", "drawableId", "acquiredAt", "releasedAt",
                                                  "requestedWidth", "requestedHeight", "vsync", "presentScheduled", "released")}
            row["cpuHoldMs"] = ((row["releasedAt"] - row["acquiredAt"]) * 1000
                                if row.get("released") and row.get("acquiredAt", 0) > 0 and row.get("releasedAt", 0) >= row["acquiredAt"] else None)
            drawable_lifetimes.append(row)
        elif kind == "mcopt.MetalPipelineCompile":
            pipeline_compiles.append({"start": value["startTime"], "durationMs": duration_ms(value.get("duration")),
                                      **{key: value.get(key) for key in ("pipeline", "variant", "success", "translationMs", "libraryMs", "stateMs")}})
        elif kind == "mcopt.MetalOperation":
            operations[value.get("operation", "unknown")].append(duration_ms(value.get("duration")))
            spans.append((value["startTime"], duration_ms(value.get("duration")), value.get("operation", "unknown")))
            if value.get("operation") == "commit" and value.get("startTime"):
                submits[value["submit"]] = datetime.fromisoformat(value["startTime"].replace("Z", "+00:00"))
            if value.get("operation") == "wait" and value.get("gpuMicros", 0) > 0:
                gpu_completed.append({"submit": value.get("submit"), "gpuMs": value["gpuMicros"] / 1000})
            if value.get("operation") == "nextDrawable":
                drawable_waits.append({"submit": value.get("submit"), "start": value["startTime"],
                                       "durationMs": duration_ms(value.get("duration")), "pendingFrames": value.get("pendingFrames"),
                                       "nativeStart": value.get("nativeStart"), "nativeEnd": value.get("nativeEnd"),
                                       "nativeDurationMs": ((value["nativeEnd"] - value["nativeStart"]) * 1000
                                                            if value.get("nativeStart", 0) > 0 and value.get("nativeEnd", 0) >= value["nativeStart"] else None),
                                       "nativeThreadCpuMs": value.get("nativeThreadCpuMs"),
                                       **{key: value.get(key) for key in ("surfaceLayer", "surfaceGeneration", "surfaceWidth", "surfaceHeight")},
                                       "drawableStatus": value.get("drawableStatus"), "boundedDrawable": value.get("boundedDrawable"),
                                       "drawableWorkerStart": value.get("drawableWorkerStart"), "drawableWorkerEnd": value.get("drawableWorkerEnd"),
                                       "drawableWorkerCpuMs": value.get("drawableWorkerCpuMs"),
                                       "drawableWorkerMs": ((value["drawableWorkerEnd"] - value["drawableWorkerStart"]) * 1000
                                                            if value.get("drawableWorkerStart", 0) > 0 and value.get("drawableWorkerEnd", 0) >= value["drawableWorkerStart"] else None)})
    main_buffers = {row["submit"]: row for row in command_buffers if row["kind"] == "main"}
    for row in drawable_lifetimes:
        main = main_buffers.get(row["submit"])
        if main and row.get("released"):
            row["releasedBeforeCommitMs"] = (main["committedAt"] - row["releasedAt"]) * 1000 if main.get("committedAt", 0) > 0 else None
            row["gpuEndAfterReleaseMs"] = (main["gpuEnd"] - row["releasedAt"]) * 1000 if main.get("gpuEnd", 0) > 0 else None
            row["commandBufferReleaseAfterCpuReleaseMs"] = ((main["releasedAt"] - row["releasedAt"]) * 1000
                                                            if main.get("released") and main.get("releasedAt", 0) > 0 else None)
    for row in presentations:
        main = main_buffers.get(row["submit"])
        if main and row.get("presentedAt", 0) and not row.get("missing"):
            row["presentedAfterCommitMs"] = (row["presentedAt"] - main["committedAt"]) * 1000
            if main.get("gpuEnd", 0):
                row["presentedAfterGpuEndMs"] = (row["presentedAt"] - main["gpuEnd"]) * 1000
    presentation_times = sorted({row["presentedAt"] for row in presentations if row.get("presentedAt", 0) and not row.get("missing")})
    ordered_submits = sorted(submits.items())
    cadence = stats([(b-a).total_seconds()*1000 for (ai,a),(bi,b) in zip(ordered_submits, ordered_submits[1:]) if bi == ai+1])
    if cadence.get("mean", 0) > 0:
        cadence["submitsPerSecond"] = 1000/cadence["mean"]
    gpu_by_submit = {v["submit"]: v["gpuMs"] for v in gpu_completed}
    timeline = []
    for (ai, a), (bi, b) in zip(ordered_submits, ordered_submits[1:]):
        if bi != ai+1:
            continue
        timeline.append({"submit": bi, "start": a.isoformat(), "startEpoch": a.timestamp(), "endEpoch": b.timestamp(),
                         "intervalMs": (b-a).total_seconds()*1000, "operationMs": {},
                         "submittedGpuSpanMs": gpu_by_submit.get(bi)})
    starts = [row["startEpoch"] for row in timeline]
    for start, length, operation in spans:
        at = datetime.fromisoformat(start.replace("Z", "+00:00")).timestamp()
        end = at + length/1000
        i = max(0, bisect.bisect_right(starts, at)-1)
        while i < len(timeline) and timeline[i]["startEpoch"] < end:
            row = timeline[i]
            overlap = max(0, min(end, row["endEpoch"])-max(at, row["startEpoch"]))*1000
            if overlap:
                row["operationMs"][operation] = row["operationMs"].get(operation, 0)+overlap
            i += 1
    return {
        "eventCounts": dict(count),
        "stackSamples": [{"thread": thread, "event": kind, "count": sum(tops.values()),
                          "topFrames": tops.most_common(12)} for (thread, kind), tops in sorted(samples.items())],
        "inclusiveStackSamples": {thread: counts.most_common(30) for thread, counts in sorted(inclusive_samples.items())},
        "cpuTimeline": cpu_timeline,
        "machineCpuPercent": stats([row["machinePercent"] for row in cpu_timeline]),
        "jvmCpuPercent": stats([row["jvmPercent"] for row in cpu_timeline]),
        "gpuPassMs": {label: {stage: stats(values) for stage, values in stages.items()} for label, stages in gpu_passes.items()},
        "threadCpuLoadPercentReportedByJfr": {thread: stats(values) for thread, values in thread_load.items()},
        "gcPauseMs": stats(gc_pauses),
        "renderAllocationStallMs": stats([row["durationMs"] for row in allocation_stalls if row["thread"] == "Render thread"]),
        "allocationStalls": allocation_stalls,
        "terrainArenaGrowth": arena_growth, "lodArenaGrowth": lod_arena_growth,
        "commandBufferTimelines": command_buffers,
        "commandBufferTimingMs": {kind: {metric: stats([row[metric] for row in command_buffers if row["kind"] == kind and row[metric] is not None])
                                         for metric in ("kernelMs", "gpuMs", "gpuStartAfterCommitMs", "completionAfterCommitMs", "cpuReleaseAfterGpuMs")}
                                  for kind in sorted({row["kind"] for row in command_buffers})},
        "presentations": presentations,
        "drawableCpuOwnership": drawable_lifetimes,
        "drawableCpuHoldMs": stats([row["cpuHoldMs"] for row in drawable_lifetimes if row["cpuHoldMs"] is not None]),
        "drawableOwnershipNote": "These are the surface caller's CPU references, released after presentation encoding. Command buffers/driver/compositor can retain resources longer; CPU release is not pool availability. Layer generation/size identify reconfiguration; native drawable IDs are identities, not extra retained objects.",
        "presentationCadenceMs": stats([(b-a)*1000 for a,b in zip(presentation_times, presentation_times[1:])]),
        "presentationNote": "presentedAt is the driver's reported presentedTime when nonzero. callbackAt is callback delivery, not scanout. Missing/zero presentedTime is not a zero-latency frame. Command-buffer GPU spans can overlap; do not add main and pre timings.",
        "pipelineCompiles": pipeline_compiles,
        "lodScheduling": lod_scheduling,
        "captureQueues": capture_queues,
        "clientCaptures": client_captures,
        "arenaAllocations": arena_allocations,
        "drawableWaits": sorted(drawable_waits, key=lambda row: row["durationMs"], reverse=True),
        "metalCpuOperationMs": {op: stats(values) for op, values in operations.items()},
        "mainGpuMsFromRetiredSubmits": stats([v["gpuMs"] for v in gpu_completed]),
        "mainSubmitCadenceMs": cadence,
        "frameBudget": {"targetHz": 60, "budgetMs": 1000/60,
                        "intervalsOverMs": {str(limit): sum(row["intervalMs"] > limit for row in timeline) for limit in (18, 20, 25, 33.4, 50, 100)}},
        "slowIntervals": sorted(timeline, key=lambda row: row["intervalMs"], reverse=True)[:30],
        "frameTimeline": timeline,
        "retiredSubmitGpuTimes": gpu_completed,
    }


def summarize(output, jfr_tool):
    metadata = json.loads((output / "capture.json").read_text())
    if not shutil.which(jfr_tool):
        recorded_jfr = Path(metadata.get("javaHome", "")) / "bin/jfr"
        if recorded_jfr.exists():
            jfr_tool = str(recorded_jfr)
    gpu = defaultdict(list)
    commits = defaultdict(dict)
    diagnostics = []
    seen = set()
    selected = []
    for path in (output / "latest.log", output / "stderr_stream.log"):
        for line in path.read_text().splitlines() if path.exists() else []:
            match = DIAG.search(line)
            if match:
                kind, submit, at, ks, ke, gs, ge, ms, copied, big = match.groups()
                identity = (kind, submit, at)
                if identity in seen or not metadata["startEpoch"] <= float(at) <= metadata["endEpoch"]:
                    continue
                seen.add(identity)
                gpu[kind].append(float(ms))
                commits[kind][int(submit)] = float(at)
                diagnostics.append({"kind": kind, "submit": int(submit), "commitEpoch": float(at), "gpuMs": float(ms),
                                    "gpuStartAfterCommitMs": float(gs), "kernelMs": float(ke)-float(ks),
                                    "copiedBytes": int(copied), "bigAllocationBytes": int(big)})
            if any(marker in line for marker in ("mcopt-metal waits:", "mcopt-metal stats:", "mcopt-lod stats:",
                                                "mcopt-lod: render ", "population ", "mcopt-own gputime")):
                selected.append(line)
    result = {"label": metadata["label"], "gpuCommandBufferMs": {kind: stats(values) for kind, values in gpu.items()},
              "mainSubmitIntervalMs": stats([(b-a)*1000 for a,b in zip(sorted(commits["main"].values()), sorted(commits["main"].values())[1:])]),
              "gpuNote": "Command buffer GPU spans include dependencies and can overlap across frames and main/pre buffers. Do not add them or interpret them as per-frame GPU busy time. Submit cadence is not measured scanout FPS. Durations do not attribute individual draw calls.",
              "gpuDiagnostics": diagnostics}
    event_filter = "jdk.ExecutionSample,jdk.NativeMethodSample,jdk.CPULoad,jdk.ThreadCPULoad,jdk.GCPhasePause,jdk.ZAllocationStall,mcopt.MetalOperation,mcopt.MetalArenaTransfer,mcopt.MetalGpuPass,mcopt.OwnArenaGrowth,mcopt.LodArenaGrowth,mcopt.MetalCommandBuffer,mcopt.MetalPresentation,mcopt.MetalDrawable,mcopt.MetalPipelineCompile,mcopt.LodScheduling,mcopt.LodCaptureQueue,mcopt.LodClientCapture,mcopt.OwnArenaAllocation"
    try:
        dump = run([jfr_tool, "print", "--json", "--events", event_filter, output / "recording.jfr"], timeout=90)
        events = json.loads(dump)["recording"]["events"]
        result.update(parse_events(events))
    except (OSError, RuntimeError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
        result["jfrAnalysisError"] = str(error)
    (output / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
    if result.get("frameTimeline"):
        with (output / "frame-times.csv").open("w", newline="") as stream:
            writer = csv.writer(stream)
            writer.writerow(["submit", "interval_start", "interval_ms", "drawable_wait_ms", "retire_wait_ms", "gc_pause_ms", "submitted_gpu_span_ms", "allocation_stall_ms"])
            for row in result["frameTimeline"]:
                writer.writerow([row["submit"], row["start"], row["intervalMs"], row["operationMs"].get("nextDrawable", 0),
                                 row["operationMs"].get("wait", 0), row["operationMs"].get("gcPause", 0), row["submittedGpuSpanMs"], row["operationMs"].get("allocationStall", 0)])
    (output / "timing-lines.txt").write_text("\n".join(selected) + "\n")
    if not gpu and not result.get("mainGpuMsFromRetiredSubmits", {}).get("count"):
        print("No GPU timing events: check that mcopt.metal.jfr=true was applied at launch and the game is rendering.", file=sys.stderr)
    return result


def restore(backup):
    manifest = backup / "profiling-settings.json" if backup.is_dir() else backup
    data = json.loads(manifest.read_text())
    config = Path(data["config"])
    lines = config.read_text().splitlines()
    current = dict(line.split("=", 1) for line in lines if "=" in line and not line.lstrip().startswith(("#", "!")))
    keys = {key for key, enabled in data["enabled"].items() if current.get(key) == enabled}
    updated = [line for line in lines if line.split("=", 1)[0].strip() not in keys
               and line != "# Temporary frame profiling; restore with tools/frame-profile.py restore BACKUP_DIR"]
    updated += [key + "=" + data["previous"][key] for key in sorted(keys) if data["previous"][key] is not None]
    staged = config.with_name(config.name + ".part")
    staged.write_text("\n".join(updated) + "\n")
    os.replace(staged, config)
    print("Restored profiling settings; unrelated preferences preserved. Restart Minecraft to apply.")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    cap = sub.add_parser("capture")
    cap.add_argument("--pid", type=int)
    cap.add_argument("--label", required=True)
    cap.add_argument("--seconds", type=int, default=20, choices=range(5, 121), metavar="5..120")
    cap.add_argument("--delay", type=int, default=5, choices=range(0, 31), metavar="0..30")
    cap.add_argument("--output", type=Path)
    cap.add_argument("--jcmd", default=shutil.which("jcmd") or "jcmd")
    cap.add_argument("--jfr", default=shutil.which("jfr") or "jfr")
    cap.add_argument("--no-gpu-passes", action="store_true", help="Keep diagnostic GPU pass splitting off; retain CPU and command-buffer timings")
    analysis = sub.add_parser("analyze")
    analysis.add_argument("output", type=Path)
    analysis.add_argument("--jfr", default=shutil.which("jfr") or "jfr")
    undo = sub.add_parser("restore")
    undo.add_argument("backup", type=Path, help="Backup directory containing profiling-settings.json")
    args = parser.parse_args()
    try:
        if args.command == "capture":
            capture(args)
        elif args.command == "analyze":
            summarize(args.output, args.jfr)
        else:
            restore(args.backup)
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
